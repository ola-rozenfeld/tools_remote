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

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.OutputSymlink;
import build.bazel.remote.execution.v2.SymlinkNode;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashingOutputStream;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.remote.stats.ActionMetadata;
import com.google.devtools.build.lib.remote.stats.RunRecord;
import com.google.devtools.build.remote.client.util.DigestUtil;
import com.google.devtools.build.remote.client.util.Utils;
import com.google.protobuf.ByteString;
import io.grpc.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/** A cache for storing artifacts (input and output) as well as the output of running an action. */
public abstract class AbstractRemoteActionCache implements AutoCloseable {

  private static final ListenableFuture<Void> COMPLETED_SUCCESS = SettableFuture.create();
  private static final ListenableFuture<byte[]> EMPTY_BYTES = SettableFuture.create();

  static {
    ((SettableFuture<Void>) COMPLETED_SUCCESS).set(null);
    ((SettableFuture<byte[]>) EMPTY_BYTES).set(new byte[0]);
  }

  protected final RemoteOptions options;
  protected final DigestUtil digestUtil;
  protected final Retrier retrier;

  public DigestUtil getDigestUtil() {
    return digestUtil;
  }

  public AbstractRemoteActionCache(RemoteOptions options, DigestUtil digestUtil, Retrier retrier) {
    this.options = options;
    this.digestUtil = digestUtil;
    this.retrier = retrier;
  }

  /**
   * Attempts to look up the given action in the remote cache and return its result, if present.
   * Returns {@code null} if there is no such entry. Note that a successful result from this method
   * does not guarantee the availability of the corresponding output files in the remote cache.
   *
   * @throws IOException if the remote cache is unavailable.
   */
  abstract @Nullable ActionResult getCachedActionResult(DigestUtil.ActionKey actionKey)
      throws IOException, InterruptedException;

  /**
   * Download a remote blob and store it in memory.
   *
   * @param digest The digest of the remote blob.
   * @return The remote blob.
   * @throws IOException if download failed.
   */
  public byte[] downloadBlob(Digest digest) throws IOException, InterruptedException {
    Context ctx = Context.current();
    return getFromFuture(retrier.executeAsync(() -> ctx.call(() -> downloadBlobAsync(digest))));
  }

  /**
   * Download a single blob with the specified digest into an output stream.
   *
   * @throws CacheNotFoundException in case of a cache miss.
   */
  public void downloadBlob(Digest digest, OutputStream dest)
      throws IOException, InterruptedException {
    Context ctx = Context.current();
    getFromFuture(retrier.executeAsync(() -> ctx.call(() -> downloadBlobAsync(digest, dest))));
  }

  /**
   * Downloads a blob with a content hash {@code digest} to {@code out}.
   *
   * @return a future that completes after the download completes (succeeds / fails).
   */
  protected abstract ListenableFuture<Void> downloadBlobAsync(Digest digest, OutputStream out);

  /**
   * Downloads a blob with content hash {@code digest} and stores its content in memory.
   *
   * @return a future that completes after the download completes (succeeds / fails). If successful,
   *     the content is stored in the future's {@code byte[]}.
   */
  public ListenableFuture<byte[]> downloadBlobAsync(Digest digest) {
    if (digest.getSizeBytes() == 0) {
      return EMPTY_BYTES;
    }
    ByteArrayOutputStream bOut = new ByteArrayOutputStream((int) digest.getSizeBytes());
    SettableFuture<byte[]> outerF = SettableFuture.create();
    Futures.addCallback(
        downloadBlobAsync(digest, bOut),
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void aVoid) {
            outerF.set(bOut.toByteArray());
          }

          @Override
          public void onFailure(Throwable t) {
            outerF.setException(t);
          }
        },
        MoreExecutors.directExecutor());
    return outerF;
  }

  /**
   * Download the output files and directory trees of a remotely executed action to the local
   * machine, as well as stderr/ stdout.
   *
   * <p>In case of failure, this method deletes any output files it might have already created.
   *
   * @throws IOException in case of a cache miss or if the remote cache is unavailable, or
   * in case clean up after a failed download failed.
   */
  // TODO(olaola): will need to amend to include the TreeNodeRepository for updating.
  public void download(
      ActionResult result, Path execRoot, OutErr outErr, RunRecord.Builder record)
      throws IOException, InterruptedException {
    int numOutputs = 0;
    long totalOutputBytes = 0;
    Context ctx = Context.current();
    List<FuturePathBooleanTuple> fileDownloads =
        Collections.synchronizedList(
            new ArrayList<>(result.getOutputFilesCount() + result.getOutputDirectoriesCount()));
    for (OutputFile file : result.getOutputFilesList()) {
      Path path = execRoot.resolve(file.getPath());
      ListenableFuture<Void> download =
          retrier.executeAsync(
              () -> ctx.call(() -> downloadFile(path, file.getDigest())));
      numOutputs++;
      totalOutputBytes += file.getDigest().getSizeBytes();
      fileDownloads.add(new FuturePathBooleanTuple(download, path, file.getIsExecutable()));
    }
    ActionMetadata.Builder meta = record.getActionMetadataBuilder();
    meta.setNumOutputs(numOutputs).setTotalOutputBytes(totalOutputBytes);

    List<ListenableFuture<Void>> dirDownloads = new ArrayList<>(result.getOutputDirectoriesCount());
    for (OutputDirectory dir : result.getOutputDirectoriesList()) {
      SettableFuture<Void> dirDownload = SettableFuture.create();
      ListenableFuture<byte[]> protoDownload =
          retrier.executeAsync(() -> ctx.call(() -> downloadBlobAsync(dir.getTreeDigest())));
      Futures.addCallback(
          protoDownload,
          new FutureCallback<byte[]>() {
            @Override
            public void onSuccess(byte[] b) {
              try {
                addTreeFilesForDownload(
                    execRoot.resolve(dir.getPath()), Tree.parseFrom(b), fileDownloads, ctx, meta);
                dirDownload.set(null);
              } catch (IOException e) {
                dirDownload.setException(e);
              }
            }

            @Override
            public void onFailure(Throwable t) {
              dirDownload.setException(t);
            }
          },
          MoreExecutors.directExecutor());
      dirDownloads.add(dirDownload);
    }

    // Subsequently we need to wait for *every* download to finish, even if we already know that
    // one failed. That's so that when exiting this method we can be sure that all downloads have
    // finished and don't race with the cleanup routine.
    // TODO(buchgr): Look into cancellation.

    IOException downloadException = null;
    try {
      fileDownloads.addAll(downloadOutErr(result, ctx, outErr));
    } catch (IOException e) {
      downloadException = e;
    }
    for (ListenableFuture<Void> dirDownload : dirDownloads) {
      // Block on all directory download futures, so that we can be sure that we have discovered
      // all file downloads and can subsequently safely iterate over the list of file downloads.
      try {
        getFromFuture(dirDownload);
      } catch (IOException e) {
        downloadException = downloadException == null ? e : downloadException;
      }
    }
    try {
      downloadAllFiles(fileDownloads, true);
    } catch (IOException e) {
      downloadException = downloadException == null ? e : downloadException;
    }

    if (downloadException != null) {
      try {
        // Delete any (partially) downloaded output files, since any subsequent local execution
        // of this action may expect none of the output files to exist.
        for (OutputFile file : result.getOutputFilesList()) {
          Files.deleteIfExists(execRoot.resolve(file.getPath()));
        }
        for (OutputDirectory directory : result.getOutputDirectoriesList()) {
          // Note: in Bazel, this only deletes the contents of the output directory, not the
          // directory itself.
          Files.deleteIfExists(execRoot.resolve(directory.getPath()));
        }
      } catch (IOException e) {
        // If deleting of output files failed, we abort the build with a decent error message as
        // any subsequent local execution failure would likely be incomprehensible.

        // We don't propagate the downloadException, as this is a recoverable error and the cause
        // of the build failure is really that we couldn't delete output files.
        throw new RuntimeException(
            "Failed to delete output files after incomplete "
                + "download. Cannot continue with local execution.",
            e);
      }
      throw downloadException;
    }

    // We create the symbolic links after all regular downloads are finished, because dangling
    // links will not work on Windows.
    createSymbolicLinks(
        execRoot,
        Iterables.concat(
            result.getOutputFileSymlinksList(), result.getOutputDirectorySymlinksList()));
  }

  private void downloadAllFiles(List<FuturePathBooleanTuple> fileDownloads, boolean continueOnFail)
      throws IOException, InterruptedException {
    IOException ex = null;
    for (FuturePathBooleanTuple download : fileDownloads) {
      try {
        getFromFuture(download.getFuture());
        if (download.getPath() != null) {
          Utils.setExecutable(download.getPath(), download.isExecutable());
        }
      } catch (IOException e) {
        if (!continueOnFail) {
          throw e;
        }
        ex = ex == null ? e : ex;
      }
    }
    if (ex != null) {
      throw ex;
    }
  }

  private void addTreeFilesForDownload(
      Path path, Tree tree, List<FuturePathBooleanTuple> fileDownloads, Context ctx,
      ActionMetadata.Builder meta) throws IOException {
    Map<Digest, Directory> childrenMap = new HashMap<>();
    for (Directory child : tree.getChildrenList()) {
      childrenMap.put(digestUtil.compute(child), child);
    }
    fileDownloads.addAll(downloadDirectory(path, tree.getRoot(), childrenMap, ctx, meta));
  }

  // Creates a local symbolic link. Only relative symlinks are supported.
  private void createSymbolicLink(Path path, String target) throws IOException {
    Path targetPath = Paths.get(target);
    if (targetPath.isAbsolute()) {
      // Error, we do not support absolute symlinks as outputs.
      throw new IOException(
          String.format(
              "Action output %s is a symbolic link to an absolute path %s. "
                  + "Symlinks to absolute paths in action outputs are not supported.",
              path, target));
    }
    Files.createSymbolicLink(path, targetPath);
  }

  // Creates symbolic links locally as created remotely by the action. Only relative symbolic
  // links are supported, because absolute symlinks break action hermeticity.
  private void createSymbolicLinks(Path execRoot, Iterable<OutputSymlink> symlinks)
      throws IOException {
    for (OutputSymlink symlink : symlinks) {
      Path path = execRoot.resolve(symlink.getPath());
      Files.createDirectories(path.getParent());
      createSymbolicLink(path, symlink.getTarget());
    }
  }

  // Wrapper for testing.
  protected <T> T getFromFuture(ListenableFuture<T> f) throws IOException, InterruptedException {
    return Utils.getFromFuture(f);
  }

  /** Tuple of {@code ListenableFuture, Path, boolean}. */
  private static class FuturePathBooleanTuple {
    private final ListenableFuture<?> future;
    private final Path path;
    private final boolean isExecutable;

    public FuturePathBooleanTuple(ListenableFuture<?> future, Path path, boolean isExecutable) {
      this.future = future;
      this.path = path;
      this.isExecutable = isExecutable;
    }

    public ListenableFuture<?> getFuture() {
      return future;
    }

    public Path getPath() {
      return path;
    }

    public boolean isExecutable() {
      return isExecutable;
    }
  }

  /**
   * Download a directory recursively. The directory is represented by a {@link Directory} protobuf
   * message, and the descendant directories are in {@code childrenMap}, accessible through their
   * digest.
   */
  private List<FuturePathBooleanTuple> downloadDirectory(
      Path path, Directory dir, Map<Digest, Directory> childrenMap, Context ctx,
      ActionMetadata.Builder meta) throws IOException {
    // Ensure that the directory is created here even though the directory might be empty
    Files.createDirectories(path);

    for (SymlinkNode symlink : dir.getSymlinksList()) {
      createSymbolicLink(path.resolve(symlink.getName()), symlink.getTarget());
    }

    List<FuturePathBooleanTuple> downloads = new ArrayList<>(dir.getFilesCount());
    // Any listed output files that are also children of output directories will be
    // double counted here. I don't care.
    int numOutputs = 0;
    long totalOutputBytes = 0;
    for (FileNode child : dir.getFilesList()) {
      Path childPath = path.resolve(child.getName());
      numOutputs++;
      totalOutputBytes += child.getDigest().getSizeBytes();
      downloads.add(
          new FuturePathBooleanTuple(
              retrier.executeAsync(
                  () -> ctx.call(() -> downloadFile(childPath, child.getDigest()))),
              childPath,
              child.getIsExecutable()));
    }
    if (meta != null) {
      meta.setNumOutputs(meta.getNumOutputs() + numOutputs);
      meta.setTotalOutputBytes(meta.getTotalOutputBytes() + totalOutputBytes);
    }

    for (DirectoryNode child : dir.getDirectoriesList()) {
      Path childPath = path.resolve(child.getName());
      Digest childDigest = child.getDigest();
      Directory childDir = childrenMap.get(childDigest);
      if (childDir == null) {
        throw new IOException(
            "could not find subdirectory "
                + child.getName()
                + " of directory "
                + path
                + " for download: digest "
                + childDigest
                + "not found");
      }
      downloads.addAll(downloadDirectory(childPath, childDir, childrenMap, ctx, meta));
    }

    return downloads;
  }

  /** Download a file (that is not a directory). The content is fetched from the digest. */
  public ListenableFuture<Void> downloadFile(Path path, Digest digest) throws IOException {
    try {
      Files.createDirectories(path.getParent());
    } catch (FileAlreadyExistsException e) {
      // This can only happen if the path parent is a symlink.
      // TODO(olaola): verify the symlink target it a directory..
    }
    if (digest.getSizeBytes() == 0) {
      // Handle empty file locally.
      Files.write(path, new byte[0]);
      return COMPLETED_SUCCESS;
    }

    OutputStream out = new LazyFileOutputStream(path);
    SettableFuture<Void> outerF = SettableFuture.create();
    ListenableFuture<Void> f = downloadBlobAsync(digest, out);
    Futures.addCallback(
        f,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            try {
              out.close();
              outerF.set(null);
            } catch (IOException e) {
              outerF.setException(e);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            try {
              out.close();
            } catch (IOException e) {
              // Intentionally left empty. The download already failed, so we can ignore
              // the error on close().
            } finally {
              outerF.setException(t);
            }
          }
        },
        MoreExecutors.directExecutor());
    return outerF;
  }

  private List<FuturePathBooleanTuple> downloadOutErr(
      ActionResult result, Context ctx, OutErr outErr) throws IOException {
    List<FuturePathBooleanTuple> downloads = new ArrayList<>();
    if (!result.getStdoutRaw().isEmpty()) {
      result.getStdoutRaw().writeTo(outErr.getOutputStream());
      outErr.getOutputStream().flush();
    } else if (result.hasStdoutDigest()) {
      downloads.add(
          new FuturePathBooleanTuple(
              retrier.executeAsync(
                  () ->
                      ctx.call(
                          () ->
                              downloadBlobAsync(
                                  result.getStdoutDigest(), outErr.getOutputStream()))),
              null,
              false));
    }
    if (!result.getStderrRaw().isEmpty()) {
      result.getStderrRaw().writeTo(outErr.getErrorStream());
      outErr.getErrorStream().flush();
    } else if (result.hasStderrDigest()) {
      downloads.add(
          new FuturePathBooleanTuple(
              retrier.executeAsync(
                  () ->
                      ctx.call(
                          () ->
                              downloadBlobAsync(
                                  result.getStderrDigest(), outErr.getErrorStream()))),
              null,
              false));
    }
    return downloads;
  }

  protected void verifyContents(Digest expected, HashingOutputStream actual) throws IOException {
    String expectedHash = expected.getHash();
    String actualHash = DigestUtil.hashCodeToString(actual.hash());
    if (!expectedHash.equals(actualHash)) {
      String msg =
          String.format(
              "Download an output failed, because the expected hash"
                  + "'%s' did not match the received hash '%s'.",
              expectedHash, actualHash);
      throw new IOException(msg);
    }
  }

  /** Release resources associated with the cache. The cache may not be used after calling this. */
  @Override
  public abstract void close();

  /**
   * Creates an {@link OutputStream} that isn't actually opened until the first data is written.
   * This is useful to only have as many open file descriptors as necessary at a time to avoid
   * running into system limits.
   */
  private static class LazyFileOutputStream extends OutputStream {

    private final Path path;
    private OutputStream out;

    public LazyFileOutputStream(Path path) {
      this.path = path;
    }

    @Override
    public void write(byte[] b) throws IOException {
      ensureOpen();
      out.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      ensureOpen();
      out.write(b, off, len);
    }

    @Override
    public void write(int b) throws IOException {
      ensureOpen();
      out.write(b);
    }

    @Override
    public void flush() throws IOException {
      ensureOpen();
      out.flush();
    }

    @Override
    public void close() throws IOException {
      ensureOpen();
      out.close();
    }

    private void ensureOpen() throws IOException {
      if (out == null) {
        out = Files.newOutputStream(path);
      }
    }
  }

  /**
   * Download a tree with the {@link Directory} given by digest as the root directory of the tree.
   *
   * @param rootDigest The digest of the root {@link Directory} of the tree
   * @return A tree with the given directory as the root.
   * @throws IOException in the case that retrieving the blobs required to reconstruct the tree
   *     failed.
   */
  public abstract Tree getTree(Digest rootDigest) throws IOException, InterruptedException;

  /**
   * Download the full contents of a Directory to a local path given its digest.
   *
   * @param downloadPath The path to download the directory contents to.
   * @param directoryDigest The digest of the Directory to download.
   * @throws IOException, InterruptedException
   */
  public void downloadDirectory(Path downloadPath, Digest directoryDigest)
      throws IOException, InterruptedException {
    downloadTree(downloadPath, getTree(directoryDigest));
  }

  /**
   * Download the full contents of a Directory to a local path given its digest.
   *
   * @param downloadPath The path to download the directory contents to.
   * @param tree The Tree to download.
   * @throws IOException, InterruptedException
   */
  public void downloadTree(Path downloadPath, Tree tree)
      throws IOException, InterruptedException {
    List<FuturePathBooleanTuple> fileDownloads = new ArrayList<>();
    addTreeFilesForDownload(downloadPath, tree, fileDownloads, Context.current(), null);
    downloadAllFiles(fileDownloads, false);
  }

  /**
   * Download the full contents of a OutputDirectory to a local path.
   *
   * @param dir The OutputDirectory to download.
   * @param path The path to download the directory to.
   * @throws IOException, InterruptedException

   * Note: dir.getPath() will be ignored by this method in favor of the provided path!
   */
  public void downloadOutputDirectory(OutputDirectory dir, Path path)
      throws IOException, InterruptedException {
    Tree tree;
    try {
      tree = Tree.parseFrom(downloadBlob(dir.getTreeDigest()));
    } catch (IOException e) {
      throw new IOException("Could not obtain tree for OutputDirectory.", e);
    }
    downloadTree(path, tree);
  }
}
