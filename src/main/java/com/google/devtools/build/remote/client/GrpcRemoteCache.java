// Copyright 2018 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionCacheGrpc;
import build.bazel.remote.execution.v2.ActionCacheGrpc.ActionCacheBlockingStub;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageBlockingStub;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import build.bazel.remote.execution.v2.FindMissingBlobsResponse;
import build.bazel.remote.execution.v2.GetActionResultRequest;
import build.bazel.remote.execution.v2.GetTreeRequest;
import build.bazel.remote.execution.v2.GetTreeResponse;
import build.bazel.remote.execution.v2.Tree;
import com.google.bytestream.ByteStreamGrpc;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashingOutputStream;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.remote.stats.ExecutionData;
import com.google.devtools.build.lib.remote.stats.RunRecord;
import com.google.devtools.build.remote.client.TreeNodeRepository.TreeNode;
import com.google.devtools.build.remote.client.util.DigestUtil;
import com.google.devtools.build.remote.client.util.DigestUtil.ActionKey;
import com.google.devtools.build.remote.client.util.TracingMetadataUtils;
import com.google.devtools.build.remote.client.util.Utils;
import io.grpc.CallCredentials;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/** A RemoteActionCache implementation that uses gRPC calls to a remote cache server. */
public class GrpcRemoteCache extends AbstractRemoteActionCache {

  private final CallCredentials credentials;
  private final ReferenceCountedChannel channel;
  private final ByteStreamUploader uploader;
  private final int maxMissingBlobsDigestsPerMessage;

  private AtomicBoolean closed = new AtomicBoolean();

  public static class Builder {
    private CallCredentials credentials;
    private ReferenceCountedChannel channel;
    private ByteStreamUploader uploader;
    private RemoteOptions options;
    private DigestUtil digestUtil;
    private RemoteRetrier retrier;

    public Builder setChannel(ReferenceCountedChannel channel) {
      this.channel = channel;
      return this;
    }

    public Builder setCallCredentials(CallCredentials credentials) {
      this.credentials = credentials;
      return this;
    }

    public Builder setRetrier(RemoteRetrier retrier) {
      this.retrier = retrier;
      return this;
    }

    public Builder setRemoteOptions(RemoteOptions options) {
      this.options = options;
      return this;
    }

    public Builder setDigestUtil(DigestUtil digestUtil) {
      this.digestUtil = digestUtil;
      return this;
    }

    public Builder setUploader(ByteStreamUploader uploader) {
      this.uploader = uploader;
      return this;
    }

    public GrpcRemoteCache build() {
      checkArgument(channel != null, "channel must be set.");
      checkArgument(retrier != null, "retrier must be set.");
      checkArgument(uploader != null, "uploader must be set.");
      if (options == null) {
        options = new RemoteOptions();
      }
      return new GrpcRemoteCache(channel, credentials, options, retrier, digestUtil, uploader);
    }
  }

  GrpcRemoteCache(
      ReferenceCountedChannel channel,
      CallCredentials credentials,
      RemoteOptions options,
      RemoteRetrier retrier,
      DigestUtil digestUtil,
      ByteStreamUploader uploader) {
    super(options, digestUtil, retrier);
    this.credentials = credentials;
    this.channel = channel;
    this.uploader = uploader;
    maxMissingBlobsDigestsPerMessage = computeMaxMissingBlobsDigestsPerMessage();
    Preconditions.checkState(
        maxMissingBlobsDigestsPerMessage > 0, "Error: gRPC message size too small.");
  }

  private int computeMaxMissingBlobsDigestsPerMessage() {
    final int overhead =
        FindMissingBlobsRequest.newBuilder()
            .setInstanceName(options.remoteInstanceName)
            .build()
            .getSerializedSize();
    final int tagSize =
        FindMissingBlobsRequest.newBuilder()
            .addBlobDigests(Digest.getDefaultInstance())
            .build()
            .getSerializedSize()
            - FindMissingBlobsRequest.getDefaultInstance().getSerializedSize();
    // We assume all non-empty digests have the same size. This is true for fixed-length hashes.
    final int digestSize = digestUtil.compute(new byte[] {1}).getSerializedSize() + tagSize;
    return (options.maxOutboundMessageSize - overhead) / digestSize;
  }

  private ContentAddressableStorageBlockingStub casBlockingStub() {
    return ContentAddressableStorageGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  private ContentAddressableStorageFutureStub casFutureStub() {
    return ContentAddressableStorageGrpc.newFutureStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  private ByteStreamStub bsAsyncStub() {
    return ByteStreamGrpc.newStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  private ActionCacheBlockingStub acBlockingStub() {
    return ActionCacheGrpc.newBlockingStub(channel)
        .withInterceptors(TracingMetadataUtils.attachMetadataFromContextInterceptor())
        .withCallCredentials(credentials)
        .withDeadlineAfter(options.remoteTimeout, TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    uploader.release();
    channel.release();
  }

  public static boolean isRemoteCacheOptions(RemoteOptions options) {
    return options.remoteCache != null;
  }

  /**
   * Download a tree with the {@link Directory} given by digest as the root directory of the tree.
   * This method attempts to retrieve the {@link Tree} using the GetTree RPC.
   *
   * @param rootDigest The digest of the root {@link Directory} of the tree
   * @return A tree with the given directory as the root.
   * @throws IOException in the case that retrieving the blobs required to reconstruct the tree
   *     failed.
   */
  @Override
  public Tree getTree(Digest rootDigest) throws IOException, InterruptedException {
    Directory dir;
    try {
      dir = Directory.parseFrom(downloadBlob(rootDigest));
    } catch (IOException e) {
      throw new IOException("Failed to download root Directory of tree.", e);
    }

    Tree.Builder result = Tree.newBuilder().setRoot(dir);

    GetTreeRequest.Builder requestBuilder =
        GetTreeRequest.newBuilder()
            .setRootDigest(rootDigest)
            .setInstanceName(options.remoteInstanceName);

    Iterator<GetTreeResponse> responses =
        ((RemoteRetrier) retrier).execute(() -> casBlockingStub().getTree(requestBuilder.build()));
    while (responses.hasNext()) {
      result.addAllChildren(responses.next().getDirectoriesList());
    }

    return result.build();
  }

  private ListenableFuture<FindMissingBlobsResponse> getMissingDigests(
      FindMissingBlobsRequest request) throws IOException, InterruptedException {
    Context ctx = Context.current();
    return retrier.executeAsync(() -> ctx.call(() -> casFutureStub().findMissingBlobs(request)));
  }

  private ImmutableSet<Digest> getMissingDigests(Iterable<Digest> digests, RunRecord.Builder record)
      throws IOException, InterruptedException {
    if (Iterables.isEmpty(digests)) {
      return ImmutableSet.of();
    }
    // Need to potentially split the digests into multiple requests.
    FindMissingBlobsRequest.Builder requestBuilder =
        FindMissingBlobsRequest.newBuilder().setInstanceName(options.remoteInstanceName);
    List<ListenableFuture<FindMissingBlobsResponse>> callFutures = new ArrayList<>();
    int numDigests = 0;
    long totalInputBytes = 0;
    for (Digest digest : digests) {
      numDigests++;
      requestBuilder.addBlobDigests(digest);
      if (requestBuilder.getBlobDigestsCount() == maxMissingBlobsDigestsPerMessage) {
        callFutures.add(getMissingDigests(requestBuilder.build()));
        requestBuilder.clearBlobDigests();
      }
    }
    if (requestBuilder.getBlobDigestsCount() > 0) {
      callFutures.add(getMissingDigests(requestBuilder.build()));
    }
    ImmutableSet.Builder<Digest> result = ImmutableSet.builder();
    try {
      for (ListenableFuture<FindMissingBlobsResponse> callFuture : callFutures) {
        result.addAll(callFuture.get().getMissingBlobDigestsList());
      }
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Throwables.propagateIfInstanceOf(cause, IOException.class);
      throw new RuntimeException(cause);
    }
    ImmutableSet<Digest> missing = result.build();
    record.getActionMetadataBuilder().setCasCacheMisses(missing.size());
    Utils.vlog(
        options.verbosity,
        2,
        "%s> Got %d CAS cache misses out of %d unique input blobs",
        record.getCommand().getLabels().getCommandId(),
        missing.size(),
        numDigests);
    return missing;
  }

  /**
   * Ensures that the tree structure of the inputs, the input files themselves, and the command are
   * available in the remote cache, such that the tree can be reassembled and executed on another
   * machine given the root digest.
   *
   * <p>The cache may check whether files or parts of the tree structure are already present, and do
   * not need to be uploaded again.
   *
   * <p>Note that this method is only required for remote execution, not for caching itself.
   * However, remote execution uses a cache to store input files, and that may be a separate
   * end-point from the executor itself, so the functionality lives here.
   */
  public void ensureInputsPresent(
      TreeNodeRepository repository,
      TreeNode root,
      Action action,
      Command command,
      RunRecord.Builder record)
      throws IOException, InterruptedException {
    repository.computeMerkleDigests(root);
    Digest actionDigest = digestUtil.compute(action);
    Digest commandDigest = digestUtil.compute(command);
    // TODO(olaola): avoid querying all the digests, only ask for novel subtrees.
    ImmutableSet<Digest> missingDigests =
        getMissingDigests(
            Iterables.concat(
                repository.getAllDigests(root), ImmutableList.of(actionDigest, commandDigest)),
            record);

    List<Chunker> toUpload = new ArrayList<>();
    // Only upload data that was missing from the cache.
    Map<Digest, File> missingFiles = new HashMap<>();
    Map<Digest, Directory> missingTreeNodes = new HashMap<>();
    HashSet<Digest> missingTreeDigests = new HashSet<>(missingDigests);
    missingTreeDigests.remove(commandDigest);
    missingTreeDigests.remove(actionDigest);
    repository.getDataFromDigests(missingTreeDigests, missingFiles, missingTreeNodes);

    if (missingDigests.contains(actionDigest)) {
      toUpload.add(
          Chunker.builder(digestUtil).setInput(actionDigest, action.toByteArray()).build());
    }
    if (missingDigests.contains(commandDigest)) {
      toUpload.add(
          Chunker.builder(digestUtil).setInput(commandDigest, command.toByteArray()).build());
    }
    if (!missingTreeNodes.isEmpty()) {
      for (Map.Entry<Digest, Directory> entry : missingTreeNodes.entrySet()) {
        Digest digest = entry.getKey();
        Directory d = entry.getValue();
        toUpload.add(Chunker.builder(digestUtil).setInput(digest, d.toByteArray()).build());
      }
    }
    if (!missingFiles.isEmpty()) {
      for (Map.Entry<Digest, File> entry : missingFiles.entrySet()) {
        Digest digest = entry.getKey();
        File file = entry.getValue();
        toUpload.add(Chunker.builder(digestUtil).setInput(digest, file).build());
        Utils.vlog(
            options.verbosity,
            3,
            "%s> CAS cache file miss: %s",
            record.getCommand().getLabels().getCommandId(),
            file.toString());
        if (record.getCommand().getExecutionOptions().getSaveExecutionData()) {
          record.getExecutionDataBuilder().addInputFileCasMisses(file.toString());
        }
      }
    }
    uploader.uploadBlobs(toUpload, true, record);
  }

  // Execution Cache API

  @Override
  public ActionResult getCachedActionResult(ActionKey actionKey)
      throws IOException, InterruptedException {
    try {
      return ((RemoteRetrier) retrier)
          .execute(
              () ->
                  acBlockingStub()
                      .getActionResult(
                          GetActionResultRequest.newBuilder()
                              .setInstanceName(options.remoteInstanceName)
                              .setActionDigest(actionKey.getDigest())
                              .build()));
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        // Return null to indicate that it was a cache miss.
        return null;
      }
      throw new IOException(e);
    }
  }

  @Override
  protected ListenableFuture<Void> downloadBlobAsync(Digest digest, OutputStream out) {
    if (digest.getSizeBytes() == 0) {
      return Futures.immediateFuture(null);
    }
    String resourceName = "";
    if (!options.remoteInstanceName.isEmpty()) {
      resourceName += options.remoteInstanceName + "/";
    }
    resourceName += "blobs/" + digestUtil.toString(digest);

    @Nullable
    HashingOutputStream hashOut =
        options.remoteVerifyDownloads ? digestUtil.newHashingOutputStream(out) : null;
    SettableFuture<Void> outerF = SettableFuture.create();
    bsAsyncStub()
        .read(
            ReadRequest.newBuilder().setResourceName(resourceName).build(),
            new StreamObserver<ReadResponse>() {
              @Override
              public void onNext(ReadResponse readResponse) {
                try {
                  readResponse.getData().writeTo(hashOut != null ? hashOut : out);
                } catch (IOException e) {
                  outerF.setException(e);
                  // Cancel the call.
                  throw new RuntimeException(e);
                }
              }

              @Override
              public void onError(Throwable t) {
                if (t instanceof StatusRuntimeException
                    && ((StatusRuntimeException) t).getStatus().getCode()
                    == Status.NOT_FOUND.getCode()) {
                  outerF.setException(new CacheNotFoundException(digest, digestUtil));
                } else {
                  outerF.setException(t);
                }
              }

              @Override
              public void onCompleted() {
                try {
                  if (hashOut != null) {
                    verifyContents(digest, hashOut);
                  }
                  out.flush();
                  outerF.set(null);
                } catch (IOException e) {
                  outerF.setException(e);
                }
              }
            });
    return outerF;
  }
}
