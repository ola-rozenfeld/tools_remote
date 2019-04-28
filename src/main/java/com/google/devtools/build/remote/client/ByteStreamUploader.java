// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.remote.client;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;

import build.bazel.remote.execution.v2.BatchUpdateBlobsRequest;
import build.bazel.remote.execution.v2.BatchUpdateBlobsResponse;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.Digest;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.remote.proxy.RunRecord;
import com.google.devtools.build.remote.client.util.TracingMetadataUtils;
import com.google.devtools.build.remote.client.util.Utils;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A client implementing the {@code Write} method of the {@code ByteStream} gRPC service.
 *
 * <p>The uploader supports reference counting to easily be shared between components with
 * different lifecyles. After instantiation the reference count is {@code 1}.
 *
 * See {@link ReferenceCounted} for more information on reference counting.
 */
class ByteStreamUploader extends AbstractReferenceCounted {

  private static final Logger logger = Logger.getLogger(ByteStreamUploader.class.getName());

  private final String instanceName;
  private final ReferenceCountedChannel channel;
  private final CallCredentials callCredentials;
  private final long callTimeoutSecs;
  private final RemoteRetrier retrier;
  private final long batchMaxSize;
  private final int batchMaxNumBlobs;
  private int verbosity;

  private final Object lock = new Object();

  /** Contains the hash codes of already uploaded blobs. **/
  @GuardedBy("lock")
  private final Set<HashCode> uploadedBlobs = new HashSet<>();

  @GuardedBy("lock")
  private final Map<Digest, ListenableFuture<Void>> uploadsInProgress = new HashMap<>();

  @GuardedBy("lock")
  private boolean isShutdown;

  public static class Builder {
    private String instanceName = "";
    private ReferenceCountedChannel channel;
    private CallCredentials callCredentials;
    private long callTimeoutSecs = 60;
    private RemoteRetrier retrier;
    private long batchMaxSize;
    private int batchMaxNumBlobs;
    private int verbosity;

    public Builder setInstanceName(String instanceName) {
      this.instanceName = instanceName == null ? "" : instanceName;
      return this;
    }

    public Builder setChannel(ReferenceCountedChannel channel) {
      this.channel = channel;
      return this;
    }

    public Builder setCallCredentials(CallCredentials callCredentials) {
      this.callCredentials = callCredentials;
      return this;
    }

    public Builder setCallTimeoutSecs(long callTimeoutSecs) {
      this.callTimeoutSecs = callTimeoutSecs;
      return this;
    }

    public Builder setRetrier(RemoteRetrier retrier) {
      this.retrier = retrier;
      return this;
    }

    public Builder setBatchMaxNumBlobs(int batchMaxNumBlobs) {
      this.batchMaxNumBlobs = batchMaxNumBlobs;
      return this;
    }

    public Builder setBatchMaxSize(int batchMaxSize) {
      this.batchMaxSize = batchMaxSize;
      return this;
    }

    public Builder setVerbosity(int verbosity) {
      this.verbosity = verbosity;
      return this;
    }

    public ByteStreamUploader build() {
      checkArgument(callTimeoutSecs > 0, "callTimeoutSecs must be gt 0.");
      checkArgument(channel != null, "channel must be set.");
      checkArgument(retrier != null, "retrier must be set.");
      return new ByteStreamUploader(
          instanceName,
          channel,
          callCredentials,
          callTimeoutSecs,
          retrier,
          batchMaxSize,
          batchMaxNumBlobs,
          verbosity);
    }
  }
  /**
   * Creates a new instance.
   *
   * @param instanceName the instance name to be prepended to resource name of the {@code Write}
   *     call. See the {@code ByteStream} service definition for details
   * @param channel the {@link io.grpc.Channel} to use for calls
   * @param callCredentials the credentials to use for authentication. May be {@code null}, in which
   *     case no authentication is performed
   * @param callTimeoutSecs the timeout in seconds after which a {@code Write} gRPC call must be
   *     complete. The timeout resets between retries
   * @param retrier the {@link RemoteRetrier} whose backoff strategy to use for retry timings.
   */
  ByteStreamUploader(
      String instanceName,
      ReferenceCountedChannel channel,
      @Nullable CallCredentials callCredentials,
      long callTimeoutSecs,
      RemoteRetrier retrier,
      long batchMaxSize,
      int batchMaxNumBlobs,
      int verbosity) {
    this.batchMaxNumBlobs = batchMaxNumBlobs;
    this.batchMaxSize = batchMaxSize;
    this.instanceName = instanceName;
    this.channel = channel;
    this.callCredentials = callCredentials;
    this.callTimeoutSecs = callTimeoutSecs;
    this.retrier = retrier;
    this.verbosity = verbosity;
  }

  /**
   * Splits a list of chunkers into batches of size no more than limit. Algorithm: first, we sort
   * all the blobs, then we make each batch by taking the largest available blob and then filling in
   * with as many small blobs as we can fit. This is a naive approach to the knapsack problem, and
   * may have suboptimal results in some cases, but it results in deterministic batches, runs in O(n
   * * log n) time, and avoids most of the pathological cases that result from scanning from one end
   * of the list only.
   */
  private Iterable<List<Chunker>> breakChunkersIntoBatches(
      Iterable<Chunker> chunkers, RunRecord.Builder record) {
    if (batchMaxSize <= 0 || batchMaxNumBlobs <= 0) {
      return Iterables.transform(chunkers, chunker -> ImmutableList.of(chunker));
    }
    ArrayList<Chunker> toUpload = Lists.newArrayList(chunkers);
    toUpload.sort(Comparator.comparing(c -> c.digest().getSizeBytes()));
    ArrayList<List<Chunker>> result = new ArrayList<>();
    int l = 0;
    int r = toUpload.size() - 1;
    final long tagSize = 10; // Max varint size -- overestimate.
    while (l <= r) {
      ArrayList<Chunker> batch = new ArrayList<>();
      batch.add(toUpload.get(r--));
      Digest digest = batch.get(0).digest();
      long size =
          2 * tagSize + instanceName.length() + digest.getSizeBytes() + digest.getSerializedSize();
      Digest nextDigest = toUpload.get(l).digest();
      long nextSize = 3 * tagSize + nextDigest.getSizeBytes() + nextDigest.getSerializedSize();
      while (l <= r && size + nextSize <= batchMaxSize && batch.size() < batchMaxNumBlobs) {
        size += nextSize;
        batch.add(toUpload.get(l++));
        nextDigest = toUpload.get(l).digest();
        nextSize = 3 * tagSize + nextDigest.getSizeBytes() + nextDigest.getSerializedSize();
      }
      if (batch.size() > 1) {
        Utils.vlog(
            verbosity,
            2,
            "%s> Uploading batch of %d blobs, total size %d bytes",
            record.getCommandParameters().getId(),
            batch.size(),
            size);
      } else {
        Utils.vlog(
            verbosity,
            2,
            "%s> Uploading %s as a single input",
            record.getCommandParameters().getId(),
            batch.get(0));
      }
      result.add(batch);
    }
    return result;
  }

  /**
   * Uploads a BLOB, as provided by the {@link Chunker}, to the remote {@code ByteStream} service.
   * The call blocks until the upload is complete, or throws an {@link Exception} in case of error.
   *
   * <p>Uploads are retried according to the specified {@link RemoteRetrier}. Retrying is
   * transparent to the user of this API.
   *
   * <p>Trying to upload the same BLOB multiple times concurrently, results in only one upload being
   * performed. This is transparent to the user of this API.
   *
   * @param chunker the data to upload.
   * @param forceUpload if {@code false} the blob is not uploaded if it has previously been
   *        uploaded, if {@code true} the blob is uploaded.
   * @throws IOException when reading of the {@link Chunker}s input source fails
   */
  public void uploadBlob(Chunker chunker, boolean forceUpload) throws IOException,
      InterruptedException {
    uploadBlobs(singletonList(chunker), forceUpload, RunRecord.newBuilder());
  }

  /**
   * Uploads a list of BLOBs concurrently to the remote {@code ByteStream} service. The call blocks
   * until the upload of all BLOBs is complete, or throws an {@link Exception} after the first
   * upload failed. Any other uploads will continue uploading in the background, until they complete
   * or the {@link #shutdown()} method is called. Errors encountered by these uploads are swallowed.
   *
   * <p>Uploads are retried according to the specified {@link RemoteRetrier}. Retrying is
   * transparent to the user of this API.
   *
   * <p>Trying to upload the same BLOB multiple times concurrently, results in only one upload being
   * performed. This is transparent to the user of this API.
   *
   * @param chunkers the data to upload.
   * @param forceUpload if {@code false} the blob is not uploaded if it has previously been
   *     uploaded, if {@code true} the blob is uploaded.
   * @throws IOException when reading of the {@link Chunker}s input source or uploading fails
   */
  public void uploadBlobs(
      Iterable<Chunker> chunkers, boolean forceUpload, RunRecord.Builder record)
      throws IOException, InterruptedException {
    try {
      for (ListenableFuture<Void> upload : uploadBlobsAsync(
          chunkers, forceUpload, record).values()) {
        upload.get();
      }
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Throwables.propagateIfInstanceOf(cause, IOException.class);
      Throwables.propagateIfInstanceOf(cause, InterruptedException.class);
      if (cause instanceof StatusRuntimeException) {
        throw new IOException(cause);
      }
      Throwables.propagate(cause);
    }
  }

  /**
   * Cancels all running uploads. The method returns immediately and does NOT wait for the uploads
   * to be cancelled.
   *
   * <p>This method should not be called directly, but will be called implicitly when the
   * reference count reaches {@code 0}.
   */
  @VisibleForTesting
  void shutdown() {
    synchronized (lock) {
      if (isShutdown) {
        return;
      }
      isShutdown = true;
      // Before cancelling, copy the futures to a separate list in order to avoid concurrently
      // iterating over and modifying the map (cancel triggers a listener that removes the entry
      // from the map. the listener is executed in the same thread.).
      List<Future<Void>> uploadsToCancel = new ArrayList<>(uploadsInProgress.values());
      for (Future<Void> upload : uploadsToCancel) {
        upload.cancel(true);
      }
    }
  }

  /**
   * Uploads multiple BLOBs asynchronously to the remote {@code ByteStream} service. The call
   * returns immediately and one can listen to the returned futures for the success/failure of the
   * uploads.
   *
   * <p>Uploads are retried according to the specified {@link RemoteRetrier}. Retrying is
   * transparent to the user of this API.
   *
   * <p>Trying to upload the same BLOB multiple times concurrently, results in only one upload being
   * performed. This is transparent to the user of this API.
   *
   * @param chunkers the data to upload.
   * @param forceUpload if {@code false} the blob is not uploaded if it has previously been
   *     uploaded, if {@code true} the blob is uploaded.
   * @return futures on which the upload can be waited, keyed by the BLOB digest. Some of the
   *     returned futures may be the same, if an upload was already in progress or completed (with
   *     no {@code forceUpload}), or the uploader decided to batch digests together.
   * @throws IOException when reading of the {@link Chunker}s input source fails
   */
  public Map<Digest, ListenableFuture<Void>> uploadBlobsAsync(
      Iterable<Chunker> chunkers, boolean forceUpload, RunRecord.Builder record) {
    Map<Digest, ListenableFuture<Void>> result = new HashMap<>();

    synchronized (lock) {
      checkState(!isShutdown, "Must not call uploadBlobs after shutdown.");

      List<Chunker> toUpload = new ArrayList<>();
      for (Chunker chunker : chunkers) {
        Digest digest = checkNotNull(chunker.digest());
        HashCode hash = HashCode.fromString(digest.getHash());
        if (!forceUpload && uploadedBlobs.contains(hash)) {
          result.put(digest, Futures.immediateFuture(null));
        }
        ListenableFuture<Void> inProgress = uploadsInProgress.get(digest);
        if (inProgress != null) {
          result.put(digest, inProgress);
        }
        if (!result.containsKey(digest)) {
          result.put(digest, null); // placeholder, to not upload same digests.
          toUpload.add(chunker);
        }
      }

      Context ctx = Context.current();
      for (List<Chunker> batch : breakChunkersIntoBatches(toUpload, record)) {
        ListenableFuture<Void> uploadResult =
            Futures.transform(
                retrier.executeAsync(() -> ctx.call(() -> startAsyncUpload(batch))),
                (v) -> {
                  synchronized (lock) {
                    for (Chunker chunker : batch) {
                      uploadedBlobs.add(HashCode.fromString(chunker.digest().getHash()));
                    }
                  }
                  return null;
                },
                MoreExecutors.directExecutor());
        // A future that only completes once the upload and internal state updates have
        // been completed.
        SettableFuture<Void> uploadAndBookkeepingComplete = SettableFuture.create();
        for (Chunker chunker : batch) {
          Digest digest = chunker.digest();
          uploadsInProgress.put(digest, uploadAndBookkeepingComplete);
          result.put(digest, uploadAndBookkeepingComplete);
        }
        uploadAndBookkeepingComplete.addListener(
            () -> {
              if (uploadAndBookkeepingComplete.isCancelled()) {
                uploadResult.cancel(true);
              }
            },
            MoreExecutors.directExecutor());
        uploadResult.addListener(
            () -> {
              synchronized (lock) {
                for (Chunker chunker : batch) {
                  uploadsInProgress.remove(chunker.digest());
                }
                try {
                  uploadResult.get();
                  uploadAndBookkeepingComplete.set(null);
                } catch (ExecutionException e) {
                  uploadAndBookkeepingComplete.setException(e.getCause());
                } catch (CancellationException e) {
                  uploadAndBookkeepingComplete.cancel(true);
                } catch (Throwable e) {
                  uploadAndBookkeepingComplete.setException(e);
                }
              }
            },
            MoreExecutors.directExecutor());
      }
    }
    return result;
  }

  @VisibleForTesting
  boolean uploadsInProgress() {
    synchronized (lock) {
      return !uploadsInProgress.isEmpty();
    }
  }

  /** Starts an upload and returns a future representing the upload. */
  private ListenableFuture<Void> startAsyncUpload(List<Chunker> batch) {
    try {
      for (Chunker chunker : batch) {
        chunker.reset();
      }
    } catch (IOException e) {
      return Futures.immediateFailedFuture(e);
    }

    SettableFuture<Void> currUpload = SettableFuture.create();
    AsyncUpload newUpload =
        new AsyncUpload(channel, callCredentials, callTimeoutSecs, instanceName, batch, currUpload);
    currUpload.addListener(
        () -> {
          if (currUpload.isCancelled()) {
            newUpload.cancel();
          }
        },
        MoreExecutors.directExecutor());
    newUpload.start();
    return currUpload;
  }

  @Override
  public ByteStreamUploader retain() {
    return (ByteStreamUploader) super.retain();
  }

  @Override
  public ByteStreamUploader retain(int increment) {
    return (ByteStreamUploader) super.retain(increment);
  }

  @Override
  protected void deallocate() {
    shutdown();
    channel.release();
  }

  @Override
  public ReferenceCounted touch(Object o) {
    return this;
  }

  private static class AsyncUpload {

    private final Channel channel;
    private final CallCredentials callCredentials;
    private final long callTimeoutSecs;
    private final String instanceName;
    private final List<Chunker> batch;
    private final SettableFuture<Void> uploadResult;

    private ClientCall<WriteRequest, WriteResponse> call;
    private ClientCall<BatchUpdateBlobsRequest, BatchUpdateBlobsResponse> batchCall;

    AsyncUpload(
        Channel channel,
        CallCredentials callCredentials,
        long callTimeoutSecs,
        String instanceName,
        List<Chunker> batch,
        SettableFuture<Void> uploadResult) {
      this.channel = channel;
      this.callCredentials = callCredentials;
      this.callTimeoutSecs = callTimeoutSecs;
      this.instanceName = instanceName;
      this.batch = batch;
      this.uploadResult = uploadResult;
    }

    void start() {
      CallOptions callOptions =
          CallOptions.DEFAULT
              .withCallCredentials(callCredentials)
              .withDeadlineAfter(callTimeoutSecs, SECONDS);
      if (batch.size() == 1) {
        startWriteCall(batch.get(0), callOptions);
      } else {
        startBatchCall(callOptions);
      }
    }

    private void startWriteCall(Chunker chunker, CallOptions callOptions) {
      call = channel.newCall(ByteStreamGrpc.getWriteMethod(), callOptions);

      ClientCall.Listener<WriteResponse> callListener =
          new ClientCall.Listener<WriteResponse>() {

            private final WriteRequest.Builder requestBuilder = WriteRequest.newBuilder();
            private boolean callHalfClosed = false;

            @Override
            public void onMessage(WriteResponse response) {
              // TODO(buchgr): The ByteStream API allows to resume the upload at the committedSize.
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
              if (status.isOk()) {
                uploadResult.set(null);
              } else {
                uploadResult.setException(
                    status.augmentDescription(chunker.toString()).asRuntimeException());
              }
            }

            @Override
            public void onReady() {
              while (call.isReady()) {
                if (!chunker.hasNext()) {
                  // call.halfClose() may only be called once. Guard against it being called more
                  // often.
                  // See: https://github.com/grpc/grpc-java/issues/3201
                  if (!callHalfClosed) {
                    callHalfClosed = true;
                    // Every chunk has been written. No more work to do.
                    call.halfClose();
                  }
                  return;
                }

                try {
                  requestBuilder.clear();
                  Chunker.Chunk chunk = chunker.next();

                  if (chunk.getOffset() == 0) {
                    // Resource name only needs to be set on the first write for each file.
                    requestBuilder.setResourceName(newResourceName(chunk.getDigest()));
                  }

                  boolean isLastChunk = !chunker.hasNext();
                  WriteRequest request =
                      requestBuilder
                          .setData(chunk.getData())
                          .setWriteOffset(chunk.getOffset())
                          .setFinishWrite(isLastChunk)
                          .build();

                  call.sendMessage(request);
                } catch (IOException e) {
                  try {
                    chunker.reset();
                  } catch (IOException e1) {
                    // This exception indicates that closing the underlying input stream failed.
                    // We don't expect this to ever happen, but don't want to swallow the exception
                    // completely.
                    logger.log(Level.WARNING, "Chunker failed closing data source.", e1);
                  } finally {
                    call.cancel("Failed to read next chunk from " + chunker, e);
                  }
                }
              }
            }

            private String newResourceName(Digest digest) {
              String resourceName =
                  format(
                      "uploads/%s/blobs/%s/%d",
                      UUID.randomUUID(), digest.getHash(), digest.getSizeBytes());
              if (!Strings.isNullOrEmpty(instanceName)) {
                resourceName = instanceName + "/" + resourceName;
              }
              return resourceName;
            }
          };
      call.start(callListener, TracingMetadataUtils.headersFromCurrentContext());
      call.request(1);
    }

    private void startBatchCall(CallOptions callOptions) {
      BatchUpdateBlobsRequest.Builder requestBuilder =
          BatchUpdateBlobsRequest.newBuilder().setInstanceName(instanceName);
      for (Chunker chunker : batch) {
        try {
          requestBuilder.addRequests(
              BatchUpdateBlobsRequest.Request.newBuilder()
                  .setDigest(chunker.digest())
                  .setData(chunker.fullData()));
        } catch (IOException e) {
          uploadResult.setException(
              Status.FAILED_PRECONDITION
                  .withDescription("Failed to read Chunker data from " + chunker)
                  .asRuntimeException());
          return;
        }
      }
      ClientCall.Listener<BatchUpdateBlobsResponse> callListener =
          new ClientCall.Listener<BatchUpdateBlobsResponse>() {
            private boolean serverError = false;

            @Override
            public void onMessage(BatchUpdateBlobsResponse response) {
              // TODO(olaola): in both batch and regular, check that all digests succeeded.
              for (BatchUpdateBlobsResponse.Response resp : response.getResponsesList()) {
                Status status = Status.fromCodeValue(resp.getStatus().getCode());
                if (!status.isOk()) {
                  uploadResult.setException(
                      status.withDescription(resp.getStatus().getMessage()).asRuntimeException());
                  serverError = true;
                  return; // TODO(olaola): handle multiple blob failures better; print details.
                }
              }
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
              if (serverError) {
                return; // Status was already passed to listener
              }
              if (status.isOk()) {
                uploadResult.set(null);
              } else {
                uploadResult.setException(status.asRuntimeException());
              }
            }
          };
      batchCall =
          channel.newCall(ContentAddressableStorageGrpc.getBatchUpdateBlobsMethod(), callOptions);
      batchCall.start(callListener, TracingMetadataUtils.headersFromCurrentContext());
      batchCall.request(2);
      try {
        batchCall.sendMessage(requestBuilder.build());
        batchCall.halfClose();
      } catch (Exception e) {
        call.cancel("Error sending to server", e);
      }
    }

    void cancel() {
      if (call != null) {
        call.cancel("Cancelled by user.", null);
      }
      if (batchCall != null) {
        batchCall.cancel("Cancelled by user.", null);
      }
    }
  }
}
