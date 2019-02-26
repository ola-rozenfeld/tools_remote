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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.SymlinkNode;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.remote.client.util.DigestUtil;
import com.google.devtools.build.remote.client.util.DigestUtil.ActionKey;
import com.google.devtools.build.remote.client.util.Utils;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.File;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link AbstractRemoteActionCache}. */
@RunWith(JUnit4.class)
public class AbstractRemoteActionCacheTests {
  private Path execRoot;
  private final DigestUtil digestUtil = new DigestUtil(Hashing.sha256());

  @Rule
  public TemporaryFolder tmpRoot = new TemporaryFolder();

  @Before
  public void setUp() throws Exception {
    execRoot = tmpRoot.newFolder("execroot").toPath();
  }

  @After
  public final void tearDown() throws Exception {
    execRoot.toFile().delete();
  }

  @Test
  public void downloadRelativeFileSymlink() throws Exception {
    AbstractRemoteActionCache cache = newTestCache();
    ActionResult.Builder result = ActionResult.newBuilder();
    result.addOutputFileSymlinksBuilder().setPath("a/b/link").setTarget("../../foo");
    // Doesn't check for dangling links, hence download succeeds.
    cache.download(result.build(), execRoot, null);
    Path path = execRoot.resolve("a/b/link");
    assertThat(Files.isSymbolicLink(path)).isTrue();
    assertThat(Files.readSymbolicLink(path).toString()).isEqualTo("../../foo");
  }

  @Test
  public void downloadRelativeDirectorySymlink() throws Exception {
    AbstractRemoteActionCache cache = newTestCache();
    ActionResult.Builder result = ActionResult.newBuilder();
    result.addOutputDirectorySymlinksBuilder().setPath("a/b/link").setTarget("foo");
    // Doesn't check for dangling links, hence download succeeds.
    cache.download(result.build(), execRoot, null);
    Path path = execRoot.resolve("a/b/link");
    assertThat(Files.isSymbolicLink(path)).isTrue();
    assertThat(Files.readSymbolicLink(path).toString()).isEqualTo("foo");
  }

  @Test
  public void downloadRelativeSymlinkInDirectory() throws Exception {
    DefaultRemoteActionCache cache = newTestCache();
    Tree tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("../foo")))
            .build();
    Digest treeDigest = cache.addContents(tree.toByteArray());
    ActionResult.Builder result = ActionResult.newBuilder();
    result.addOutputDirectoriesBuilder().setPath("dir").setTreeDigest(treeDigest);
    // Doesn't check for dangling links, hence download succeeds.
    cache.download(result.build(), execRoot, null);
    Path path = execRoot.resolve("dir/link");
    assertThat(Files.isSymbolicLink(path)).isTrue();
    assertThat(Files.readSymbolicLink(path).toString()).isEqualTo("../foo");
  }

  @Test
  public void downloadAbsoluteDirectorySymlinkError() throws Exception {
    AbstractRemoteActionCache cache = newTestCache();
    ActionResult.Builder result = ActionResult.newBuilder();
    result.addOutputDirectorySymlinksBuilder().setPath("foo").setTarget("/abs/link");
    try {
      cache.download(result.build(), execRoot, null);
      fail("Expected exception");
    } catch (IOException expected) {
      assertThat(expected).hasMessageThat().contains("/abs/link");
      assertThat(expected).hasMessageThat().contains("absolute path");
    }
  }

  @Test
  public void downloadAbsoluteFileSymlinkError() throws Exception {
    AbstractRemoteActionCache cache = newTestCache();
    ActionResult.Builder result = ActionResult.newBuilder();
    result.addOutputFileSymlinksBuilder().setPath("foo").setTarget("/abs/link");
    try {
      cache.download(result.build(), execRoot, null);
      fail("Expected exception");
    } catch (IOException expected) {
      assertThat(expected).hasMessageThat().contains("/abs/link");
      assertThat(expected).hasMessageThat().contains("absolute path");
    }
  }

  @Test
  public void downloadAbsoluteSymlinkInDirectoryError() throws Exception {
    DefaultRemoteActionCache cache = newTestCache();
    Tree tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addSymlinks(SymlinkNode.newBuilder().setName("link").setTarget("/foo")))
            .build();
    Digest treeDigest = cache.addContents(tree.toByteArray());
    ActionResult.Builder result = ActionResult.newBuilder();
    result.addOutputDirectoriesBuilder().setPath("dir").setTreeDigest(treeDigest);
    try {
      cache.download(result.build(), execRoot, null);
      fail("Expected exception");
    } catch (IOException expected) {
      assertThat(expected).hasMessageThat().contains("dir/link");
      assertThat(expected).hasMessageThat().contains("/foo");
      assertThat(expected).hasMessageThat().contains("absolute path");
    }
  }

  @Test
  public void downloadFilesDirectoriesAndStreamsSuccess() throws Exception {
    DefaultRemoteActionCache cache = newTestCache();
    Digest fooDigest = cache.addContents("1");
    Digest barDigest = cache.addContents("2");
    Directory aDir = Directory.newBuilder()
        .addFiles(FileNode.newBuilder().setName("foo").setDigest(fooDigest)).build();
    Digest aDigest = cache.addContents(aDir.toByteArray());
    Tree tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(FileNode.newBuilder().setName("foo").setDigest(fooDigest))
                    .addDirectories(DirectoryNode.newBuilder().setName("a").setDigest(aDigest)))
            .addChildren(aDir)
            .build();
    Digest treeDigest = cache.addContents(tree.toByteArray());

    ActionResult.Builder result = ActionResult.newBuilder();
    result.addOutputDirectoriesBuilder().setPath("dir").setTreeDigest(treeDigest);
    result.addOutputFiles(OutputFile.newBuilder().setPath("a/b/bar").setDigest(barDigest));
    result.setStdoutRaw(ByteString.copyFromUtf8("stdout"));
    result.setStderrDigest(cache.addContents("stderr".getBytes(UTF_8)));
    RecordingOutErr outErr = new RecordingOutErr();
    cache.download(result.build(), execRoot, outErr);
    assertThat(outErr.outAsLatin1()).isEqualTo("stdout");
    assertThat(outErr.errAsLatin1()).isEqualTo("stderr");

    assertThat(cache.getNumFailedDownloads()).isEqualTo(0);
    assertThat(cache.getDownloadQueueSize()).isEqualTo(5);
    assertThat(Files.readAllBytes(execRoot.resolve("dir/foo"))).isEqualTo("1".getBytes(UTF_8));
    assertThat(Files.readAllBytes(execRoot.resolve("dir/a/foo"))).isEqualTo("1".getBytes(UTF_8));
    assertThat(Files.readAllBytes(execRoot.resolve("a/b/bar"))).isEqualTo("2".getBytes(UTF_8));
  }

  @Test
  public void downloadFailureCleansUp() throws Exception {
    DefaultRemoteActionCache cache = newTestCache();
    Tree tree = Tree.newBuilder().setRoot(Directory.newBuilder()).build();
    Digest treeDigest = cache.addContents(tree.toByteArray());
    Digest outputFileDigest =
        cache.addException("outputdir/outputfile", new IOException("download failed"));
    Digest otherFileDigest = cache.addContents("otherfile");

    ActionResult.Builder result = ActionResult.newBuilder();
    result.addOutputDirectoriesBuilder().setPath("outputdir").setTreeDigest(treeDigest);
    result.addOutputFiles(
        OutputFile.newBuilder().setPath("outputdir/outputfile").setDigest(outputFileDigest));
    result.addOutputFiles(OutputFile.newBuilder().setPath("otherfile").setDigest(otherFileDigest));
    try {
      cache.download(result.build(), execRoot, null);
      fail("Expected exception");
    } catch (IOException expected) {
      assertThat(cache.getNumFailedDownloads()).isEqualTo(1);
      assertThat(execRoot.resolve("outputdir").toFile().exists()).isFalse();
      assertThat(execRoot.resolve("otherfile").toFile().exists()).isFalse();
    }
  }

  @Test
  public void onErrorWaitForRemainingDownloadsToComplete() throws Exception {
    // If one or more downloads of output files / directories fail then the code should
    // wait for all downloads to have been completed before it tries to clean up partially
    // downloaded files.

    DefaultRemoteActionCache cache = newTestCache();
    Digest digest1 = cache.addContents("file1");
    Digest digest2 = cache.addException("file2", new IOException("download failed"));
    Digest digest3 = cache.addContents("file3");

    ActionResult result =
        ActionResult.newBuilder()
            .setExitCode(0)
            .addOutputFiles(OutputFile.newBuilder().setPath("file1").setDigest(digest1))
            .addOutputFiles(OutputFile.newBuilder().setPath("file2").setDigest(digest2))
            .addOutputFiles(OutputFile.newBuilder().setPath("file3").setDigest(digest3))
            .build();
    try {
      cache.download(result, execRoot, null);
      fail("Expected IOException");
    } catch (IOException e) {
      assertThat(cache.getNumSuccessfulDownloads()).isEqualTo(2);
      assertThat(cache.getNumFailedDownloads()).isEqualTo(1);
      assertThat(cache.getDownloadQueueSize()).isEqualTo(3);
      assertThat(Throwables.getRootCause(e)).hasMessageThat().isEqualTo("download failed");
    }
  }

  private DefaultRemoteActionCache newTestCache() {
    RemoteRetrier retrier = RemoteRetrier.newRpcRetrier(false);
    return new DefaultRemoteActionCache(new RemoteOptions(), digestUtil, retrier);
  }

  private static class DefaultRemoteActionCache extends AbstractRemoteActionCache {

    Map<Digest, ListenableFuture<byte[]>> downloadResults = new HashMap<>();
    List<ListenableFuture<?>> blockingDownloads = new ArrayList<>();
    AtomicInteger numSuccess = new AtomicInteger();
    AtomicInteger numFailures = new AtomicInteger();

    public DefaultRemoteActionCache(RemoteOptions options, DigestUtil digestUtil, Retrier retrier) {
      super(options, digestUtil, retrier);
    }

    public Digest addContents(String txt) throws UnsupportedEncodingException {
      return addContents(txt.getBytes(UTF_8));
    }

    public Digest addContents(byte[] bytes) throws UnsupportedEncodingException {
      Digest digest = digestUtil.compute(bytes);
      downloadResults.put(digest, Futures.immediateFuture(bytes));
      return digest;
    }

    public Digest addException(String txt, Exception e) throws UnsupportedEncodingException {
      Digest digest = digestUtil.compute(txt.getBytes(UTF_8));
      downloadResults.put(digest, Futures.immediateFailedFuture(e));
      return digest;
    }

    public int getNumSuccessfulDownloads() {
      return numSuccess.get();
    }

    public int getNumFailedDownloads() {
      return numFailures.get();
    }

    public int getDownloadQueueSize() {
      return blockingDownloads.size();
    }

    @Override
    protected <T> T getFromFuture(ListenableFuture<T> f) throws IOException, InterruptedException {
      blockingDownloads.add(f);
      return Utils.getFromFuture(f);
    }

    @Nullable
    @Override
    ActionResult getCachedActionResult(ActionKey actionKey)
        throws IOException, InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    protected ListenableFuture<Void> downloadBlobAsync(Digest digest, OutputStream out) {
      SettableFuture<Void> result = SettableFuture.create();
      Futures.addCallback(
          downloadResults.get(digest),
          new FutureCallback<byte[]>() {
            @Override
            public void onSuccess(byte[] bytes) {
              numSuccess.incrementAndGet();
              try {
                out.write(bytes);
                out.close();
                result.set(null);
              } catch (IOException e) {
                result.setException(e);
              }
            }

            @Override
            public void onFailure(Throwable throwable) {
              numFailures.incrementAndGet();
              result.setException(throwable);
            }
          },
          MoreExecutors.directExecutor());
      return result;
    }

    @Override
    public void close() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Tree getTree(Digest rootDigest) throws IOException, InterruptedException {
      throw new UnsupportedOperationException();
    }
  }
}
