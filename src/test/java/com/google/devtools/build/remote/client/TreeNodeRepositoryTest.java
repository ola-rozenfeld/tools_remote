// Copyright 2019 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.devtools.build.remote.client.TreeNodeRepository.TreeNode;
import com.google.devtools.build.remote.client.util.DigestUtil;
import com.google.devtools.build.remote.client.util.Utils;
import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TreeNodeRepository}. */
@RunWith(JUnit4.class)
public class TreeNodeRepositoryTest {
  private DigestUtil digestUtil;
  private Path execRoot;

  @Rule
  public TemporaryFolder tmpRoot = new TemporaryFolder();

  @Before
  public final void setUp() throws Exception {
    digestUtil = new DigestUtil(Hashing.sha256());
    execRoot = tmpRoot.newFolder("exec", "root").toPath();
  }

  @After
  public final void tearDown() throws Exception {
    execRoot.toFile().delete();
  }

  private TreeNodeRepository createTestTreeNodeRepository() {
    return createTestTreeNodeRepository(ImmutableList.of());
  }

  private TreeNodeRepository createTestTreeNodeRepository(List<Path> dynamicInputs) {
    FileCache inputFileCache = new FileCache(digestUtil);
    return new TreeNodeRepository(execRoot, inputFileCache, digestUtil, dynamicInputs);
  }

  private TreeNode buildFromFiles(TreeNodeRepository repo, File... inputs) throws IOException {
    return buildFromFiles(repo, ImmutableList.of(), inputs);
  }

  private TreeNode buildFromFiles(
      TreeNodeRepository repo, List<String> ignoreInputs, File... inputs) throws IOException {
    return repo.buildFromFiles(
        Arrays.stream(inputs)
            .map(f -> execRoot.relativize(f.toPath()))
            .collect(Collectors.toList()),
        ignoreInputs);
  }

  private File createFile(String relativePath, String contents) throws IOException {
    Path path = execRoot.resolve(relativePath);
    Files.createDirectories(path.getParent());
    Files.write(path, contents.getBytes(UTF_8));
    return path.toFile();
  }

  private File createFile(String relativePath) throws IOException {
    return createFile(relativePath, "");
  }

  @Test
  @SuppressWarnings("ReferenceEquality")
  public void testSubtreeReusage() throws Exception {
    File fooCc = createFile("a/foo.cc");
    File fooH = createFile("a/foo.h");
    File bar = createFile("b/bar.txt");
    File baz = createFile("c/baz.txt");

    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root1 = buildFromFiles(repo, fooCc, fooH, bar);
    TreeNode root2 = buildFromFiles(repo, fooCc, fooH, baz);
    // Reusing same node for the "a" subtree.
    assertThat(
            root1.getChildEntries().get(0).getChild() == root2.getChildEntries().get(0).getChild())
        .isTrue();
  }

  @Test
  @SuppressWarnings("ReferenceEquality")
  public void testIgnoreInputs() throws Exception {
    File fooCc = createFile("a/foo.cc");
    File fooH = createFile("a/foo.h");
    File bar = createFile("b/bar.txt");
    File baz = createFile("c/baz.txt");

    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = buildFromFiles(
        repo,
        ImmutableList.of("\\.cc$", "\\.txt$"),
        fooCc,
        fooH,
        bar.getParentFile(),
        baz.getParentFile());
    assertThat(root.getChildEntries().get(0).getChild().getChildEntries().size()).isEqualTo(1);
    assertThat(root.getChildEntries().get(1).getChild().getChildEntries()).isEmpty();
    assertThat(root.getChildEntries().get(2).getChild().getChildEntries()).isEmpty();
  }

  @Test
  @SuppressWarnings("ReferenceEquality")
  public void testDedupChildren() throws Exception {
    File fooCc = createFile("a/foo.cc");
    File fooH = createFile("a/foo.h");
    File bar = createFile("b/c/bar.txt");
    File baz = createFile("b/c/d/baz.txt");

    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = buildFromFiles(
        repo,
        fooCc,
        fooH,
        fooCc.getParentFile(),
        bar.getParentFile().toPath().resolve("../c").toFile(),
        bar);
    assertThat(root.getChildEntries().size()).isEqualTo(2);
    assertThat(root.getChildEntries().get(0).getChild().getChildEntries().size()).isEqualTo(2);
    assertThat(root.getChildEntries().get(1).getChild().getChildEntries().size()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("ReferenceEquality")
  public void testStaticDirectoryCache() throws Exception {
    createFile("a/foo1");
    createFile("a/foo2");
    File aDir = execRoot.resolve("a").toFile();

    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = buildFromFiles(repo, aDir);
    assertThat(root.getChildEntries().size()).isEqualTo(1);
    assertThat(root.getChildEntries().get(0).getChild().getChildEntries().size()).isEqualTo(2);

    createFile("a/foo3");
    TreeNode root1 = buildFromFiles(repo, aDir);
    assertThat(root).isEqualTo(root1); // Did not stat a/ again and find foo3.
  }

  @Test
  @SuppressWarnings("ReferenceEquality")
  public void testDynamicDirectoryNoCache() throws Exception {
    createFile("a/foo1");
    createFile("a/foo2");
    Path aDirPath = execRoot.resolve("a");
    File aDir = aDirPath.toFile();

    TreeNodeRepository repo = createTestTreeNodeRepository(ImmutableList.of(aDirPath));
    TreeNode root = buildFromFiles(repo, aDir);
    assertThat(root.getChildEntries().size()).isEqualTo(1);
    assertThat(root.getChildEntries().get(0).getChild().getChildEntries().size()).isEqualTo(2);

    createFile("a/foo3");
    TreeNode root1 = buildFromFiles(repo, aDir);
    assertThat(root).isNotEqualTo(root1); // stat a/ again and find foo3.
    assertThat(root1.getChildEntries().size()).isEqualTo(1);
    assertThat(root1.getChildEntries().get(0).getChild().getChildEntries().size()).isEqualTo(3);
  }

  @Test
  public void testMerkleDigests() throws Exception {
    Files.createDirectories(execRoot.resolve("a"));
    File foo = createFile("a/foo", "1");
    File bar = createFile("a/bar", "11");
    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = buildFromFiles(repo, foo, bar);
    TreeNode aNode = root.getChildEntries().get(0).getChild();
    TreeNode fooNode = aNode.getChildEntries().get(1).getChild(); // foo > bar in sort order!
    TreeNode barNode = aNode.getChildEntries().get(0).getChild();

    repo.computeMerkleDigests(root);
    ImmutableCollection<Digest> digests = repo.getAllDigests(root);
    Digest rootDigest = repo.getMerkleDigest(root);
    Digest aDigest = repo.getMerkleDigest(aNode);
    Digest fooDigest = repo.getMerkleDigest(fooNode); // The contents digest.
    Digest barDigest = repo.getMerkleDigest(barNode);
    assertThat(digests).containsExactly(rootDigest, aDigest, barDigest, fooDigest);

    Map<Digest, Directory> directories = new HashMap<>();
    Map<Digest, File> files = new HashMap<>();
    repo.getDataFromDigests(digests, files, directories);
    assertThat(files.values()).containsExactly(bar, foo);
    assertThat(directories).hasSize(2);
    Directory rootDirectory = directories.get(rootDigest);
    assertThat(rootDirectory.getDirectories(0).getName()).isEqualTo("a");
    assertThat(rootDirectory.getDirectories(0).getDigest()).isEqualTo(aDigest);
    Directory aDirectory = directories.get(aDigest);
    assertThat(aDirectory.getFiles(0).getName()).isEqualTo("bar");
    assertThat(aDirectory.getFiles(0).getDigest()).isEqualTo(barDigest);
    assertThat(aDirectory.getFiles(1).getName()).isEqualTo("foo");
    assertThat(aDirectory.getFiles(1).getDigest()).isEqualTo(fooDigest);
  }

  @Test
  public void testGetAllDigests() throws Exception {
    File foo1 = createFile("a/foo", "1");
    File foo2 = createFile("b/foo", "1");
    File foo3 = createFile("c/foo", "1");
    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = buildFromFiles(repo, foo1, foo2, foo3);
    repo.computeMerkleDigests(root);
    // Reusing same node for the "foo" subtree: only need the root, root child, and foo contents:
    assertThat(repo.getAllDigests(root)).hasSize(3);
  }

  @Test
  public void testEmptyTree() throws Exception {
    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = repo.buildFromFiles(Collections.emptyList());
    repo.computeMerkleDigests(root);

    assertThat(root.getChildEntries()).isEmpty();
  }

  @Test
  public void testAbsoluteFileSymlink() throws Exception {
    Path link = execRoot.resolve("link");
    Path target = tmpRoot.newFolder("other").toPath().resolve("foo");
    Files.createDirectories(target.getParent());
    Files.write(target, "foo".getBytes(UTF_8));
    Files.createSymbolicLink(link, target);
    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = buildFromFiles(repo, link.toFile());
    repo.computeMerkleDigests(root);
    Digest digest = digestUtil.compute(target);
    Directory rootDir =
        Directory.newBuilder()
            .addFiles(FileNode.newBuilder().setName("link").setDigest(digest))
            .build();
    assertThat(repo.treeToDirectories(root)).containsExactly(rootDir);
  }

  @Test
  public void testRelativeDirectorySymlinkInDirectory() throws Exception {
    File foo = createFile("bardir/foo", "bla");
    Utils.setExecutable(foo.toPath(), true);
    Path link = execRoot.resolve("dir/link");
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(link, Paths.get("../bardir"));
    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = buildFromFiles(repo, link.getParent().toFile());
    repo.computeMerkleDigests(root);
    Directory barDir =
        Directory.newBuilder()
            .addFiles(
                FileNode.newBuilder()
                    .setName("foo")
                    .setDigest(digestUtil.compute(foo.toPath()))
                    .setIsExecutable(true))
            .build();
    Digest barDigest = digestUtil.compute(barDir);
    Directory dirNode =
        Directory.newBuilder()
            .addDirectories(DirectoryNode.newBuilder().setName("link").setDigest(barDigest))
            .build();
    Digest dirDigest = digestUtil.compute(dirNode);
    Directory rootDir =
        Directory.newBuilder()
            .addDirectories(DirectoryNode.newBuilder().setName("dir").setDigest(dirDigest))
            .build();
    assertThat(repo.treeToDirectories(root)).containsExactly(rootDir, dirNode, barDir);
  }

  @Test
  public void testDirectoryInput() throws Exception {
    File foo = execRoot.resolve("a/foo").toFile();
    File fooH = createFile("a/foo/foo.h", "1");
    File fooCc = createFile("a/foo/foo.cc", "2");

    File bar = createFile("a/bar.txt");
    TreeNodeRepository repo = createTestTreeNodeRepository();

    File aClient = execRoot.resolve("a-client").toFile();
    File baz = createFile("a-client/baz.txt", "3");

    TreeNode root = buildFromFiles(repo, baz, foo, aClient, bar);
    TreeNode aNode = root.getChildEntries().get(0).getChild();
    TreeNode fooNode = aNode.getChildEntries().get(1).getChild(); // foo > bar in sort order!
    TreeNode barNode = aNode.getChildEntries().get(0).getChild();
    TreeNode aClientNode = root.getChildEntries().get(1).getChild(); // a-client > a in sort order
    TreeNode bazNode = aClientNode.getChildEntries().get(0).getChild();

    TreeNode fooHNode =
        fooNode.getChildEntries().get(1).getChild(); // foo.h > foo.cc in sort order!
    TreeNode fooCcNode = fooNode.getChildEntries().get(0).getChild();

    repo.computeMerkleDigests(root);
    ImmutableCollection<Digest> digests = repo.getAllDigests(root);
    Digest rootDigest = repo.getMerkleDigest(root);
    Digest aDigest = repo.getMerkleDigest(aNode);
    Digest fooDigest = repo.getMerkleDigest(fooNode);
    Digest fooHDigest = repo.getMerkleDigest(fooHNode);
    Digest fooCcDigest = repo.getMerkleDigest(fooCcNode);
    Digest aClientDigest = repo.getMerkleDigest(aClientNode);
    Digest bazDigest = repo.getMerkleDigest(bazNode);
    Digest barDigest = repo.getMerkleDigest(barNode);
    assertThat(digests)
        .containsExactly(
            rootDigest,
            aDigest,
            barDigest,
            fooDigest,
            fooCcDigest,
            fooHDigest,
            aClientDigest,
            bazDigest);

    Map<Digest, Directory> directories = new HashMap<>();
    Map<Digest, File> files = new HashMap<>();
    repo.getDataFromDigests(digests, files, directories);
    assertThat(files.values()).containsExactly(bar, fooH, fooCc, baz);
    assertThat(directories).hasSize(4); // root, root/a, root/a/foo, and root/a-client
    Directory rootDirectory = directories.get(rootDigest);
    assertThat(rootDirectory.getDirectories(0).getName()).isEqualTo("a");
    assertThat(rootDirectory.getDirectories(0).getDigest()).isEqualTo(aDigest);
    assertThat(rootDirectory.getDirectories(1).getName()).isEqualTo("a-client");
    assertThat(rootDirectory.getDirectories(1).getDigest()).isEqualTo(aClientDigest);
    Directory aDirectory = directories.get(aDigest);
    assertThat(aDirectory.getFiles(0).getName()).isEqualTo("bar.txt");
    assertThat(aDirectory.getFiles(0).getDigest()).isEqualTo(barDigest);
    assertThat(aDirectory.getDirectories(0).getName()).isEqualTo("foo");
    assertThat(aDirectory.getDirectories(0).getDigest()).isEqualTo(fooDigest);
    Directory fooDirectory = directories.get(fooDigest);
    assertThat(fooDirectory.getFiles(0).getName()).isEqualTo("foo.cc");
    assertThat(fooDirectory.getFiles(0).getDigest()).isEqualTo(fooCcDigest);
    assertThat(fooDirectory.getFiles(1).getName()).isEqualTo("foo.h");
    assertThat(fooDirectory.getFiles(1).getDigest()).isEqualTo(fooHDigest);
    Directory aClientDirectory = directories.get(aClientDigest);
    assertThat(aClientDirectory.getFiles(0).getName()).isEqualTo("baz.txt");
    assertThat(aClientDirectory.getFiles(0).getDigest()).isEqualTo(bazDigest);
  }
}
