// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static java.nio.charset.StandardCharsets.US_ASCII;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.graph.Traverser;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.remote.proxy.ExecutionData;
import com.google.devtools.build.remote.client.util.DigestUtil;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * A factory and repository for {@link TreeNode} objects. Provides directory structure traversals,
 * computing and caching Merkle hashes on all objects.
 */
public final class TreeNodeRepository {
  private static final BaseEncoding LOWER_CASE_HEX = BaseEncoding.base16().lowerCase();

  private final Traverser<TreeNode> traverser =
      Traverser.forTree((TreeNode node) -> children(node));

  /**
   * A single node in a hierarchical directory structure. Leaves are the Files.
   */
  public static final class TreeNode {

    private final int hashCode;
    private final ImmutableList<ChildEntry> childEntries; // no need to make it a map thus far.
    @Nullable private final File file;
    private final boolean isLeaf;

    /** A pair of path segment, TreeNode. */
    public static final class ChildEntry {

      private final String segment;
      private final TreeNode child;

      public ChildEntry(String segment, TreeNode child) {
        this.segment = segment;
        this.child = child;
      }

      public TreeNode getChild() {
        return child;
      }

      public String getSegment() {
        return segment;
      }

      @Override
      @SuppressWarnings("ReferenceEquality")
      public boolean equals(Object o) {
        if (o == this) {
          return true;
        }
        if (!(o instanceof ChildEntry)) {
          return false;
        }
        ChildEntry other = (ChildEntry) o;
        // Pointer comparison for the TreeNode as it is interned
        return other.segment.equals(segment) && other.child == child;
      }

      @Override
      public int hashCode() {
        return Objects.hash(segment, child);
      }

      public static Comparator<ChildEntry> segmentOrder =
          Comparator.comparing(ChildEntry::getSegment);
    }

    // Should only be called by the TreeNodeRepository.
    private TreeNode(Iterable<ChildEntry> childEntries, @Nullable File file) {
      isLeaf = false;
      this.file = file;
      this.childEntries = ImmutableList.copyOf(childEntries);
      if (file != null) {
        hashCode = file.hashCode(); // This will ensure efficient interning of TreeNodes as
        // files are hashed by path.
      } else {
        hashCode = Arrays.hashCode(this.childEntries.toArray());
      }
    }

    // Should only be called by the TreeNodeRepository.
    private TreeNode(File file) {
      isLeaf = true;
      this.file =
          Preconditions.checkNotNull(file, "a TreeNode leaf should have a file");
      this.childEntries = ImmutableList.of();
      hashCode = file.hashCode(); // This will ensure efficient interning of TreeNodes as
      // files are hashed by path.
    }

    public File getFile() {
      return file;
    }

    public ImmutableList<ChildEntry> getChildEntries() {
      return childEntries;
    }

    public boolean isLeaf() {
      return isLeaf;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TreeNode)) {
        return false;
      }
      TreeNode otherNode = (TreeNode) o;
      // Full comparison of files. This will compare paths.
      return Objects.equals(otherNode.file, file)
          && childEntries.equals(otherNode.childEntries);
    }

    private String toDebugStringAtLevel(int level) {
      char[] prefix = new char[level];
      Arrays.fill(prefix, ' ');
      StringBuilder sb = new StringBuilder();

      if (isLeaf()) {
        sb.append('\n');
        sb.append(prefix);
        sb.append("leaf: ");
        sb.append(file);
      } else {
        for (ChildEntry entry : childEntries) {
          sb.append('\n');
          sb.append(prefix);
          sb.append(entry.segment);
          sb.append(entry.child.toDebugStringAtLevel(level + 1));
        }
      }
      return sb.toString();
    }

    public String toDebugString() {
      return toDebugStringAtLevel(0);
    }
  }

  public static class NodeStats {
    private final int numInputs;
    private final long totalInputBytes;

    public int getNumInputs() {
      return numInputs;
    }

    public long getTotalInputBytes() {
      return totalInputBytes;
    }

    public NodeStats(int numInputs, long totalInputBytes) {
      this.numInputs = numInputs;
      this.totalInputBytes = totalInputBytes;
    }
  }

  private static final TreeNode EMPTY_NODE =
      new TreeNode(ImmutableList.<TreeNode.ChildEntry>of(), null);

  // Keep only one canonical instance of every TreeNode in the repository.
  private final Interner<TreeNode> interner =
      Interners.newBuilder()
          .weak()
          .concurrencyLevel(Runtime.getRuntime().availableProcessors())
          .build();
  // Merkle hashes are computed and cached by the repository, therefore execRoot must
  // be part of the state.
  private final Path execRoot;
  private final FileCache inputFileCache;
  private final ConcurrentMap<File, TreeNode> staticDirectoryCache = Maps.newConcurrentMap();
  private final ConcurrentMap<Digest, File> reverseInputMap = Maps.newConcurrentMap();
  // For directories that are themselves artifacts, map of the File to the Merkle hash
  private final ConcurrentMap<TreeNode, Digest> treeNodeDigestCache = Maps.newConcurrentMap();
  private final ConcurrentMap<Digest, TreeNode> digestTreeNodeCache = Maps.newConcurrentMap();
  private final ConcurrentMap<TreeNode, Directory> directoryCache = Maps.newConcurrentMap();
  private final ConcurrentMap<TreeNode, NodeStats> directoryStatCache = Maps.newConcurrentMap();
  private final DigestUtil digestUtil;
  private final List<Path> dynamicInputs;

  public TreeNodeRepository(
      Path execRoot,
      FileCache inputFileCache,
      DigestUtil digestUtil,
      List<Path> dynamicInputs) {
    this.execRoot = execRoot.toAbsolutePath();
    this.inputFileCache = inputFileCache;
    this.digestUtil = digestUtil;
    this.dynamicInputs =
        removeChildren(
            dynamicInputs.stream()
                .map(p -> execRoot.resolve(p).normalize())
                .collect(Collectors.toList()));
  }

  // An input directory cannot be cached if it either contains a dynamic directory or
  // is under one.
  private boolean isDynamicInput(File input) {
    Path inputPath = input.toPath();
    return dynamicInputs.stream().anyMatch(
        p -> inputPath.startsWith(p) || p.startsWith(inputPath));
  }

  public FileCache getInputFileCache() {
    return inputFileCache;
  }

  public Iterable<TreeNode> children(TreeNode node) {
    return Iterables.transform(node.getChildEntries(), TreeNode.ChildEntry::getChild);
  }

  /** Traverse the directory structure in order (pre-order tree traversal). */
  public Iterable<TreeNode> descendants(TreeNode node) {
    return traverser.depthFirstPreOrder(node);
  }

  /**
   * Traverse the directory structure in order (pre-order tree traversal), return only the leaves.
   */
  public Iterable<TreeNode> leaves(TreeNode node) {
    return Iterables.filter(descendants(node), n -> n.isLeaf());
  }

  // normalize!
  private List<Path> removeChildren(List<Path> sortedPaths) {
    List<Path> result = new ArrayList<>();
    if (sortedPaths.isEmpty()) {
      return result;
    }
    result.add(sortedPaths.get(0));
    for (int i = 1; i < sortedPaths.size(); ++i) {
      if (!sortedPaths.get(i).startsWith(result.get(result.size()-1))) {
        result.add(sortedPaths.get(i));
      }
    }
    return result;
  }

  public TreeNode buildFromFiles(List<Path> paths) throws IOException {
    return buildFromFiles(paths, ImmutableList.of());
  }

  private boolean shouldIgnore(Path path, List<Predicate<String>> ignorePredicates) {
    return ignorePredicates.stream().anyMatch(p -> p.test(path.toString()));
  }

  public TreeNode buildFromFiles(List<Path> paths, List<String> ignoreInputs)
      throws IOException {
    List<Predicate<String>> ignorePredicates =
        ignoreInputs.stream()
            .map(p -> Pattern.compile(p).asPredicate())
            .collect(Collectors.toList());
    List<Path> sortedPaths =
        removeChildren(
            paths.stream()
                .map(Path::normalize)
                .filter(p -> !shouldIgnore(p, ignorePredicates))
                .sorted()
                .collect(Collectors.toList()));
    List<File> inputs = new ArrayList<>();
    ArrayList<ArrayList<String>> segments = new ArrayList<>();
    for (Path path : sortedPaths) {
      segments.add(Lists.newArrayList(Iterators.transform(path.iterator(), Path::toString)));
      inputs.add(execRoot.resolve(path).toFile());
    }
    return buildParentNode(
        inputs, segments, 0, inputs.size(), 0, ignorePredicates);
  }

  // Expand the descendants of an input directory.
  private List<TreeNode.ChildEntry> buildInputDirectoryEntries(
      File path, List<Predicate<String>> ignorePredicates, boolean dynamic)
      throws IOException {
    List<File> children = Arrays.asList(path.listFiles());
    List<TreeNode.ChildEntry> entries = new ArrayList<>(children.size());
    for (File file : children.stream().sorted().collect(Collectors.toList())) {
      TreeNode childNode;
      if (shouldIgnore(file.toPath(), ignorePredicates)) {
        continue;
      }
      if (file.isDirectory()) {
        if (!dynamic) {
          synchronized (file) {
            childNode = staticDirectoryCache.get(file);
            if (childNode == null) {
              childNode = interner.intern(
                  new TreeNode(
                      buildInputDirectoryEntries(file, ignorePredicates, dynamic), null));
              staticDirectoryCache.put(file, childNode);
            }
          }
        } else {
          childNode = interner.intern(
              new TreeNode(
                  buildInputDirectoryEntries(file, ignorePredicates, dynamic), null));
        }
      } else {
        childNode = interner.intern(new TreeNode(file));
      }
      entries.add(new TreeNode.ChildEntry(file.getName(), childNode));
    }

    return entries;
  }

  private TreeNode buildParentNode(
      List<File> inputs,
      ArrayList<ArrayList<String>> segments,
      int inputsStart,
      int inputsEnd,
      int segmentIndex,
      List<Predicate<String>> ignorePredicates)
      throws IOException {
    if (segments.isEmpty()) {
      // We sometimes have actions with no inputs (e.g., echo "xyz" > $@), so we need to handle that
      // case here.
      Preconditions.checkState(inputs.isEmpty());
      return EMPTY_NODE;
    }
    if (segmentIndex == segments.get(inputsStart).size()) {
      // Leaf node reached. Must be unique.
      Preconditions.checkArgument(
          inputsStart == inputsEnd - 1, "Encountered two inputs with the same path.");
      File input = inputs.get(inputsStart);
      if (input.isDirectory()) {
        boolean dynamic = isDynamicInput(input);
        TreeNode result = dynamic ? null : staticDirectoryCache.get(input);
        if (result == null) {
          result = interner.intern(
              new TreeNode(
                  buildInputDirectoryEntries(input, ignorePredicates, dynamic), input));
        }
        if (!dynamic) {
          staticDirectoryCache.put(input, result);
        }
        return result;
      }
      return interner.intern(new TreeNode(input));
    }
    ArrayList<TreeNode.ChildEntry> entries = new ArrayList<>();
    String segment = segments.get(inputsStart).get(segmentIndex);
    for (int inputIndex = inputsStart; inputIndex < inputsEnd; ++inputIndex) {
      if (inputIndex + 1 == inputsEnd
          || !segment.equals(segments.get(inputIndex + 1).get(segmentIndex))) {
        entries.add(
            new TreeNode.ChildEntry(
                segment,
                buildParentNode(
                    inputs, segments, inputsStart, inputIndex + 1, segmentIndex + 1, ignorePredicates)));
        if (inputIndex + 1 < inputsEnd) {
          inputsStart = inputIndex + 1;
          segment = segments.get(inputsStart).get(segmentIndex);
        }
      }
    }
    Collections.sort(entries, TreeNode.ChildEntry.segmentOrder);
    return interner.intern(new TreeNode(entries, null));
  }

  private Directory getOrComputeDirectory(TreeNode node) throws IOException {
    // Assumes all child digests have already been computed!
    Preconditions.checkArgument(!node.isLeaf());
    Directory directory = directoryCache.get(node);
    if (directory == null) {
      int numInputs = 0;
      long totalInputBytes = 0;
      Directory.Builder b = Directory.newBuilder();
      for (TreeNode.ChildEntry entry : node.getChildEntries()) {
        TreeNode child = entry.getChild();
        if (child.isLeaf()) {
          File input = child.getFile();
          try {
            FileMetadata metadata = inputFileCache.getMetadata(input);
            Digest digest = metadata.getDigest();
            numInputs++;
            totalInputBytes += digest.getSizeBytes();
            reverseInputMap.put(digest, input);
            b.addFilesBuilder()
                .setName(entry.getSegment())
                .setDigest(digest)
                .setIsExecutable(metadata.isExecutable());
          } catch (IOException e) {
            // Dangling symbolic link. Ignore it. This is a terrible hack!
          }
        } else {
          Digest childDigest = Preconditions.checkNotNull(treeNodeDigestCache.get(child));
          b.addDirectoriesBuilder().setName(entry.getSegment()).setDigest(childDigest);
          NodeStats childStat = Preconditions.checkNotNull(directoryStatCache.get(child));
          numInputs += childStat.getNumInputs();
          totalInputBytes += childStat.getTotalInputBytes();
        }
      }
      directory = b.build();
      directoryCache.put(node, directory);
      Digest digest = digestUtil.compute(directory);
      treeNodeDigestCache.put(node, digest);
      digestTreeNodeCache.put(digest, node);
      numInputs++;
      totalInputBytes += digest.getSizeBytes();
      directoryStatCache.put(node, new NodeStats(numInputs, totalInputBytes));
    }
    return directory;
  }

  public NodeStats getStats(TreeNode root) {
    return Preconditions.checkNotNull(directoryStatCache.get(root));
  }

  // Recursively traverses the tree, expanding and computing Merkle digests for nodes for which
  // they have not yet been computed and cached.
  public void computeMerkleDigests(TreeNode root) throws IOException {
    synchronized (root) {
      if (directoryCache.get(root) != null) {
        // Strong assumption: the cache is valid, i.e. parent present implies children present.
        return;
      }
    }
    if (!root.isLeaf()) {
      for (TreeNode child : children(root)) {
        computeMerkleDigests(child);
      }
      synchronized (root) {
        getOrComputeDirectory(root);
      }
    }
  }

  // Merkle digests should be computed prior to calling this.
  public void saveInputData(TreeNode root, ExecutionData.Builder execData) throws IOException {
    for (TreeNode node : descendants(root)) {
      if (node.isLeaf()) {
        File f = node.getFile();
        Digest digest = getMerkleDigest(node);
        if (digest != null) {
          execData.addInputFilesBuilder().setPath(f.toString()).setDigest(digest);
        }
      }
    }
  }

  /**
   * Should only be used after computeMerkleDigests has been called on one of the node ancestors.
   * Returns the precomputed digest.
   */
  public Digest getMerkleDigest(TreeNode node) throws IOException {
    if (node.isLeaf()) {
      try {
        return inputFileCache.getMetadata(node.getFile()).getDigest();
      } catch (IOException e) {
        // Dangling symbolic link which we ignore. This is a terrible hack!
        return null;
      }
    }
    return Preconditions.checkNotNull(treeNodeDigestCache.get(node));
  }

  /**
   * Returns the precomputed digests for both data and metadata. Should only be used after
   * computeMerkleDigests has been called on one of the node ancestors.
   */
  public ImmutableCollection<Digest> getAllDigests(TreeNode root) throws IOException {
    ImmutableSet.Builder<Digest> digests = ImmutableSet.builder();
    for (TreeNode node : descendants(root)) {
      Digest digest = getMerkleDigest(node);
      if (digest != null) {
        digests.add(digest);
      }
    }
    return digests.build();
  }

  /**
   * Serializes all of the subtree to a Directory list. TODO(olaola): add a version that only copies
   * a part of the tree that we are interested in. Should only be used after computeMerkleDigests
   * has been called on one of the node ancestors.
   */
  // Note: this is not, strictly speaking, thread safe. If someone is deleting cached Merkle hashes
  // while this is executing, it will trigger an exception. But I think this is WAI.
  public ImmutableList<Directory> treeToDirectories(TreeNode root) {
    ImmutableList.Builder<Directory> directories = ImmutableList.builder();
    for (TreeNode node : descendants(root)) {
      if (!node.isLeaf()) {
        directories.add(Preconditions.checkNotNull(directoryCache.get(node)));
      }
    }
    return directories.build();
  }

  /**
   * Should only be used on digests created by a call to computeMerkleDigests. Looks up Files or
   * Directory messages by cached digests and adds them to the lists.
   */
  public void getDataFromDigests(
      Iterable<Digest> digests, Map<Digest, File> files, Map<Digest, Directory> nodes) {
    for (Digest digest : digests) {
      TreeNode treeNode = digestTreeNodeCache.get(digest);
      if (treeNode != null) {
        nodes.put(digest, Preconditions.checkNotNull(directoryCache.get(treeNode)));
      } else { // If not there, it must be a File.
        files.put(digest, Preconditions.checkNotNull(reverseInputMap.get(digest)));
      }
    }
  }
}
