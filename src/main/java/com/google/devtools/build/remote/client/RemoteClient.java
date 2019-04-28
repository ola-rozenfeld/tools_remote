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

import static java.nio.charset.StandardCharsets.UTF_8;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Command.EnvironmentVariable;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.Tree;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.devtools.build.lib.remote.proxy.CommandServiceGrpc;
import com.google.devtools.build.lib.remote.proxy.CommandServiceGrpc.CommandServiceBlockingStub;
import com.google.devtools.build.lib.remote.proxy.LocalTimestamps;
import com.google.devtools.build.lib.remote.proxy.RunCommandParameters;
import com.google.devtools.build.lib.remote.proxy.RunRecord;
import com.google.devtools.build.lib.remote.proxy.RunRecord.Stage;
import com.google.devtools.build.lib.remote.proxy.RunRequest;
import com.google.devtools.build.lib.remote.proxy.RunResponse;
import com.google.devtools.build.lib.remote.proxy.RunResult;
import com.google.devtools.build.lib.remote.proxy.StatsRequest;
import com.google.devtools.build.lib.remote.proxy.StatsResponse;
import com.google.devtools.build.remote.client.LogParserUtils.ParamException;
import com.google.devtools.build.remote.client.RemoteClientOptions.CatCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.FailedActionsCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.GetDirCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.GetOutDirCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.LsCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.LsOutDirCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.PrintLogCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.ProxyPrintRemoteCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.ProxyStatsCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.RunCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.RunRemoteCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.ShowActionCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.ShowActionResultCommand;
import com.google.devtools.build.remote.client.RemoteClientOptions.ShowCommandCommand;
import com.google.devtools.build.remote.client.logging.LoggingInterceptor;
import com.google.devtools.build.remote.client.util.AsynchronousFileOutputStream;
import com.google.devtools.build.remote.client.util.Clock;
import com.google.devtools.build.remote.client.util.DigestUtil;
import com.google.devtools.build.remote.client.util.DockerUtil;
import com.google.devtools.build.remote.client.util.JavaClock;
import com.google.devtools.build.remote.client.util.ShellEscaper;
import com.google.devtools.build.remote.client.util.TracingMetadataUtils;
import com.google.devtools.build.remote.client.util.Utils;
import com.google.protobuf.TextFormat;
import com.google.protobuf.Timestamp;
import io.grpc.CallCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/** A standalone client for interacting with remote caches in Bazel. */
public class RemoteClient {

  private final DigestUtil digestUtil = new DigestUtil(Hashing.sha256());
  private final Clock clock = new JavaClock();
  private AbstractRemoteActionCache cache;
  private RemoteRunner runner;
  private AsynchronousFileOutputStream rpcLogFile;
  private RemoteClientOptions clientOptions;
  private RemoteOptions remoteOptions;
  private AuthAndTLSOptions authAndTlsOptions;
  private List<String> proxyTargets;
  private List<CommandServiceBlockingStub> proxyStubs;
  private Random rand = new Random();

  public RemoteClient(
      RemoteOptions remoteOptions,
      RemoteClientOptions clientOptions,
      AuthAndTLSOptions authAndTlsOptions)
      throws IOException {
    this.remoteOptions = remoteOptions;
    this.clientOptions = clientOptions;
    this.authAndTlsOptions = authAndTlsOptions;
    if (clientOptions.dynamicInputs == null) {
      clientOptions.dynamicInputs = new ArrayList<>();
    }
    if (!Strings.isNullOrEmpty(clientOptions.proxy)) {
      // Initialize proxy channels and stubs.
      proxyTargets = new ArrayList<>();
      proxyStubs = new ArrayList<>();
      for (int i = 0; i < clientOptions.proxyInstances; ++i) {
        String[] parts = clientOptions.proxy.split(":");
        Preconditions.checkArgument(parts.length == 2, "--proxy should be HOST:PORT");
        String target = parts[0] + ":" + (Integer.parseInt(parts[1]) + i);
        proxyTargets.add(target);
        ManagedChannel channel = GoogleAuthUtils.newChannel(target, authAndTlsOptions);
        CallCredentials credentials = GoogleAuthUtils.newCallCredentials(authAndTlsOptions);
        proxyStubs.add(
            CommandServiceGrpc.newBlockingStub(channel).withCallCredentials(credentials));
      }
      return;
    }
    if (!GrpcRemoteCache.isRemoteCacheOptions(remoteOptions)) {
      return;
    }
    List<ClientInterceptor> interceptors = new ArrayList<>();
    if (!Strings.isNullOrEmpty(clientOptions.grpcLog) &&
        Strings.isNullOrEmpty(clientOptions.proxy)) {
      rpcLogFile = new AsynchronousFileOutputStream(clientOptions.grpcLog);
      interceptors.add(new LoggingInterceptor(rpcLogFile, clock));
    }
    ReferenceCountedChannel cacheChannel =
        new ReferenceCountedChannel(
            GoogleAuthUtils.newChannel(
                remoteOptions.remoteCache,
                authAndTlsOptions,
                interceptors.toArray(new ClientInterceptor[0])));
    RemoteRetrier rpcRetrier = RemoteRetrier.newRpcRetrier(remoteOptions.remoteRetry);
    CallCredentials credentials = GoogleAuthUtils.newCallCredentials(authAndTlsOptions);
    ByteStreamUploader uploader =
        new ByteStreamUploader.Builder()
            .setInstanceName(remoteOptions.remoteInstanceName)
            .setChannel(cacheChannel.retain())
            .setCallCredentials(credentials)
            .setCallTimeoutSecs(60 * 15) // 15 minutes for each upload.
            .setRetrier(rpcRetrier)
            .setBatchMaxNumBlobs(4000)
            .setBatchMaxSize(4 * 1024 * 1024 - 1024)
            .setVerbosity(remoteOptions.verbosity)
            .build();
    cacheChannel.release();
    cache =
        new GrpcRemoteCache.Builder()
            .setCallCredentials(credentials)
            .setChannel(cacheChannel.retain())
            .setRemoteOptions(remoteOptions)
            .setRetrier(rpcRetrier)
            .setDigestUtil(digestUtil)
            .setUploader(uploader.retain())
            .build();
    uploader.release();
    if (Strings.isNullOrEmpty(remoteOptions.remoteExecutor)) {
      return;
    }

    ReferenceCountedChannel execChannel =
        remoteOptions.remoteCache.equals(remoteOptions.remoteExecutor)
            ? cacheChannel.retain()
            : new ReferenceCountedChannel(
                GoogleAuthUtils.newChannel(
                    remoteOptions.remoteExecutor,
                    authAndTlsOptions,
                    interceptors.toArray(new ClientInterceptor[0])));
    RemoteRetrier execRetrier = RemoteRetrier.newExecRpcRetrier(remoteOptions.remoteRetry);
    GrpcRemoteExecutor executor =
        new GrpcRemoteExecutor(
            execChannel.retain(),
            GoogleAuthUtils.newCallCredentials(authAndTlsOptions),
            execRetrier);
    execChannel.release();
    runner =
        new RemoteRunner(
            remoteOptions, clientOptions, digestUtil, (GrpcRemoteCache) cache, executor, clock);
  }

  public int verbosity() {
    return remoteOptions.verbosity;
  }

  public AbstractRemoteActionCache getCache() {
    return Preconditions.checkNotNull(cache, "--remote_cache must be set");
  }

  public RemoteRunner getRunner() {
    return Preconditions.checkNotNull(runner, "--remote_executor must be set");
  }

  // Prints the details (path and digest) of a DirectoryNode.
  private void printDirectoryNodeDetails(DirectoryNode directoryNode, Path directoryPath) {
    System.out.printf(
        "%s [Directory digest: %s]\n",
        directoryPath.toString(), digestUtil.toString(directoryNode.getDigest()));
  }

  // Prints the details (path and content digest) of a FileNode.
  private void printFileNodeDetails(FileNode fileNode, Path filePath) {
    System.out.printf(
        "%s [File content digest: %s]\n",
        filePath.toString(), digestUtil.toString(fileNode.getDigest()));
  }

  // List the files in a directory assuming the directory is at the given path. Returns the number
  // of files listed.
  private int listFileNodes(Path path, Directory dir, int limit) {
    int numFilesListed = 0;
    for (FileNode child : dir.getFilesList()) {
      if (numFilesListed >= limit) {
        System.out.println(" ... (too many files to list, some omitted)");
        break;
      }
      Path childPath = path.resolve(child.getName());
      printFileNodeDetails(child, childPath);
      numFilesListed++;
    }
    return numFilesListed;
  }

  // Recursively list directory files/subdirectories with digests. Returns the number of files
  // listed.
  private int listDirectory(Path path, Directory dir, Map<Digest, Directory> childrenMap, int limit)
      throws IOException {
    // Try to list the files in this directory before listing the directories.
    int numFilesListed = listFileNodes(path, dir, limit);
    if (numFilesListed >= limit) {
      return numFilesListed;
    }
    for (DirectoryNode child : dir.getDirectoriesList()) {
      Path childPath = path.resolve(child.getName());
      printDirectoryNodeDetails(child, childPath);
      Digest childDigest = child.getDigest();
      Directory childDir = childrenMap.get(childDigest);
      numFilesListed += listDirectory(childPath, childDir, childrenMap, limit - numFilesListed);
      if (numFilesListed >= limit) {
        return numFilesListed;
      }
    }
    return numFilesListed;
  }

  // Recursively list OutputDirectory with digests.
  private void listOutputDirectory(OutputDirectory dir, int limit)
      throws IOException, InterruptedException {
    Tree tree;
    try {
      tree = Tree.parseFrom(cache.downloadBlob(dir.getTreeDigest()));
    } catch (IOException e) {
      throw new IOException("Failed to obtain Tree for OutputDirectory.", e);
    }
    Map<Digest, Directory> childrenMap = new HashMap<>();
    for (Directory child : tree.getChildrenList()) {
      childrenMap.put(digestUtil.compute(child), child);
    }
    System.out.printf("OutputDirectory rooted at %s:\n", dir.getPath());
    listDirectory(Paths.get(""), tree.getRoot(), childrenMap, limit);
  }

  // Recursively list directory files/subdirectories with digests given a Tree of the directory.
  private void listTree(Path path, Tree tree, int limit) throws IOException {
    Map<Digest, Directory> childrenMap = new HashMap<>();
    for (Directory child : tree.getChildrenList()) {
      childrenMap.put(digestUtil.compute(child), child);
    }
    listDirectory(path, tree.getRoot(), childrenMap, limit);
  }

  private static int getNumFiles(Tree tree) {
    return tree.getChildrenList().stream().mapToInt(dir -> dir.getFilesCount()).sum();
  }

  // Outputs a bash executable line that corresponds to executing the given command.
  private static void printCommand(Command command) {
    for (EnvironmentVariable var : command.getEnvironmentVariablesList()) {
      System.out.printf("%s=%s \\\n", var.getName(), ShellEscaper.escapeString(var.getValue()));
    }
    System.out.print("  ");

    System.out.println(ShellEscaper.escapeJoinAll(command.getArgumentsList()));
  }

  private static void printList(List<String> list, int limit) {
    if (list.isEmpty()) {
      System.out.println("(none)");
      return;
    }
    list.stream().limit(limit).forEach(name -> System.out.println(name));
    if (list.size() > limit) {
      System.out.println(" ... (too many to list, some omitted)");
    }
  }

  private static Digest toV2(com.google.devtools.remoteexecution.v1test.Digest d)
      throws IOException {
    // Digest is binary compatible between v1 and v2
    return Digest.parseFrom(d.toByteArray());
  }

  private static Platform toV2(com.google.devtools.remoteexecution.v1test.Platform p)
      throws IOException {
    // Platform is binary compatible between v1 and v2
    return Platform.parseFrom(p.toByteArray());
  }

  // Output for print action command.
  private void printActionV1(com.google.devtools.remoteexecution.v1test.Action action, int limit)
      throws IOException, InterruptedException {
    // Note: Command V2 is backward compatible to V1. It adds fields but does not remove them, so we
    // can use it here.
    Command command = getCommand(toV2(action.getCommandDigest()));
    System.out.printf(
        "Command [digest: %s]:\n", digestUtil.toString(toV2(action.getCommandDigest())));
    printCommand(command);

    Tree tree = cache.getTree(toV2(action.getInputRootDigest()));
    System.out.printf(
        "\nInput files [total: %d, root Directory digest: %s]:\n",
        getNumFiles(tree), digestUtil.toString(toV2(action.getInputRootDigest())));
    listTree(Paths.get(""), tree, limit);

    System.out.println("\nOutput files:");
    printList(action.getOutputFilesList(), limit);

    System.out.println("\nOutput directories:");
    printList(action.getOutputDirectoriesList(), limit);

    System.out.println("\nPlatform:");
    if (action.hasPlatform() && !action.getPlatform().getPropertiesList().isEmpty()) {
      System.out.println(action.getPlatform().toString());
    } else {
      System.out.println("(none)");
    }
  }

  private Action getAction(Digest actionDigest) throws IOException, InterruptedException {
    Action action;
    try {
      action = Action.parseFrom(cache.downloadBlob(actionDigest));
    } catch (IOException e) {
      throw new IOException("Could not obtain Action from digest.", e);
    }
    return action;
  }

  private Command getCommand(Digest commandDigest) throws IOException, InterruptedException {
    Command command;
    try {
      command = Command.parseFrom(cache.downloadBlob(commandDigest));
    } catch (IOException e) {
      throw new IOException("Could not obtain Command from digest.", e);
    }
    return command;
  }

  private static com.google.devtools.remoteexecution.v1test.Action getActionV1FromFile(File file)
      throws IOException {
    com.google.devtools.remoteexecution.v1test.Action.Builder builder =
        com.google.devtools.remoteexecution.v1test.Action.newBuilder();
    try (FileInputStream fin = new FileInputStream(file)) {
      TextFormat.getParser().merge(new InputStreamReader(fin), builder);
    }
    return builder.build();
  }

  // Output for print action command.
  private void printAction(Digest actionDigest, int limit)
      throws IOException, InterruptedException {
    Action action = getAction(actionDigest);
    Command command = getCommand(action.getCommandDigest());

    System.out.printf("Command [digest: %s]:\n", digestUtil.toString(action.getCommandDigest()));
    printCommand(command);

    Tree tree = cache.getTree(action.getInputRootDigest());
    System.out.printf(
        "\nInput files [total: %d, root Directory digest: %s]:\n",
        getNumFiles(tree), digestUtil.toString(action.getInputRootDigest()));
    listTree(Paths.get(""), tree, limit);

    System.out.println("\nOutput files:");
    printList(command.getOutputFilesList(), limit);

    System.out.println("\nOutput directories:");
    printList(command.getOutputDirectoriesList(), limit);

    System.out.println("\nPlatform:");
    if (command.hasPlatform() && !command.getPlatform().getPropertiesList().isEmpty()) {
      System.out.println(command.getPlatform().toString());
    } else {
      System.out.println("(none)");
    }
  }

  // Display output file (either digest or raw bytes).
  private void printOutputFile(OutputFile file) {
    String contentString;
    if (file.hasDigest()) {
      contentString = "Content digest: " + digestUtil.toString(file.getDigest());
    } else {
      contentString = "No digest included. This likely indicates a server error.";
    }
    System.out.printf(
        "%s [%s, executable: %b]\n", file.getPath(), contentString, file.getIsExecutable());
  }

  // Output for print action result command.
  private void printActionResult(ActionResult result, int limit)
      throws IOException, InterruptedException {
    System.out.println("Output files:");
    result.getOutputFilesList().stream().limit(limit).forEach(name -> printOutputFile(name));
    if (result.getOutputFilesList().size() > limit) {
      System.out.println(" ... (too many to list, some omitted)");
    } else if (result.getOutputFilesList().isEmpty()) {
      System.out.println("(none)");
    }

    System.out.println("\nOutput directories:");
    if (!result.getOutputDirectoriesList().isEmpty()) {
      for (OutputDirectory dir : result.getOutputDirectoriesList()) {
        listOutputDirectory(dir, limit);
      }
    } else {
      System.out.println("(none)");
    }

    System.out.println(String.format("\nExit code: %d", result.getExitCode()));

    System.out.println("\nStderr buffer:");
    if (result.hasStderrDigest()) {
      byte[] stderr = cache.downloadBlob(result.getStderrDigest());
      System.out.println(new String(stderr, UTF_8));
    } else {
      System.out.println(result.getStderrRaw().toStringUtf8());
    }

    System.out.println("\nStdout buffer:");
    if (result.hasStdoutDigest()) {
      byte[] stdout = cache.downloadBlob(result.getStdoutDigest());
      System.out.println(new String(stdout, UTF_8));
    } else {
      System.out.println(result.getStdoutRaw().toStringUtf8());
    }
  }

  // Given a docker run action, sets up a directory for an Action to be run in (download Action
  // inputs, set up output directories), and display a docker command that will run the Action.
  private void setupDocker(com.google.devtools.remoteexecution.v1test.Action action, Path root)
      throws IOException, InterruptedException {
    com.google.devtools.remoteexecution.v1test.Command command;
    try {
      command =
          com.google.devtools.remoteexecution.v1test.Command.parseFrom(
              cache.downloadBlob(toV2(action.getCommandDigest())));
    } catch (IOException e) {
      throw new IOException("Failed to get Command for Action.", e);
    }
    Command.Builder builder =
        Command.newBuilder()
            .addAllArguments(command.getArgumentsList())
            .addAllOutputFiles(action.getOutputFilesList())
            .addAllOutputDirectories(action.getOutputDirectoriesList());
    for (com.google.devtools.remoteexecution.v1test.Command.EnvironmentVariable var :
        command.getEnvironmentVariablesList()) {
      builder.addEnvironmentVariables(
          Command.EnvironmentVariable.newBuilder()
              .setName(var.getName())
              .setValue(var.getValue())
              .build());
    }
    if (action.hasPlatform()) {
      builder.setPlatform(toV2(action.getPlatform()));
    }

    setupDocker(builder.build(), toV2(action.getInputRootDigest()), root);
  }

  // Given a docker run action, sets up a directory for an Action to be run in (download Action
  // inputs, set up output directories), and display a docker command that will run the Action.
  private void setupDocker(Action action, Path root) throws IOException, InterruptedException {
    Command command = getCommand(action.getCommandDigest());
    setupDocker(command, action.getInputRootDigest(), root);
  }

  private void setupDocker(Command command, Digest inputRootDigest, Path root)
      throws IOException, InterruptedException {
    System.out.printf("Setting up Action in directory %s...\n", root.toAbsolutePath());

    try {
      cache.downloadDirectory(root, inputRootDigest);
    } catch (IOException e) {
      throw new IOException("Failed to download action inputs.", e);
    }

    // Setup directory structure for outputs.
    for (String output : command.getOutputFilesList()) {
      Path file = root.resolve(output);
      if (java.nio.file.Files.exists(file)) {
        throw new FileSystemAlreadyExistsException("Output file already exists: " + file);
      }
      Files.createParentDirs(file.toFile());
    }
    for (String output : command.getOutputDirectoriesList()) {
      Path dir = root.resolve(output);
      if (java.nio.file.Files.exists(dir)) {
        throw new FileSystemAlreadyExistsException("Output directory already exists: " + dir);
      }
      java.nio.file.Files.createDirectories(dir);
    }
    DockerUtil util = new DockerUtil();
    String dockerCommand = util.getDockerCommand(command, root.toString());
    System.out.println("\nSuccessfully setup Action in directory " + root.toString() + ".");
    System.out.println("\nTo run the Action locally, run:");
    System.out.println("  " + dockerCommand);
  }

  private void doPrintLog(PrintLogCommand options) throws IOException {
    LogParserUtils parser = new LogParserUtils(clientOptions.grpcLog);
    parser.printLog(options);
  }

  private void doFailedActions(FailedActionsCommand options) throws IOException, ParamException {
    LogParserUtils parser = new LogParserUtils(clientOptions.grpcLog);
    parser.printFailedActions();
  }

  private void doLs(LsCommand options) throws IOException, InterruptedException {
    Context withMetadata = TracingMetadataUtils.contextWithMetadata("ls");
    Context previous = withMetadata.attach();
    try {
      Tree tree = getCache().getTree(options.digest);
      listTree(Paths.get(""), tree, options.limit);
    } finally {
      withMetadata.detach(previous);
    }
  }

  private void doLsOutDir(LsOutDirCommand options) throws IOException, InterruptedException {
    Context withMetadata = TracingMetadataUtils.contextWithMetadata("lsoutdir");
    Context previous = withMetadata.attach();
    OutputDirectory dir;
    try {
      dir = OutputDirectory.parseFrom(getCache().downloadBlob(options.digest));
      listOutputDirectory(dir, options.limit);
    } catch (IOException e) {
      throw new IOException("Failed to obtain OutputDirectory.", e);
    } finally {
      withMetadata.detach(previous);
    }
  }

  private void doGetDir(GetDirCommand options) throws IOException, InterruptedException {
    Context withMetadata = TracingMetadataUtils.contextWithMetadata("getdir");
    Context previous = withMetadata.attach();
    try {
      getCache().downloadDirectory(options.path, options.digest);
    } finally {
      withMetadata.detach(previous);
    }
  }

  private void doGetOutDir(GetOutDirCommand options) throws IOException, InterruptedException {
    Context withMetadata = TracingMetadataUtils.contextWithMetadata("getoutdir");
    Context previous = withMetadata.attach();
    OutputDirectory dir;
    try {
      dir = OutputDirectory.parseFrom(getCache().downloadBlob(options.digest));
      getCache().downloadOutputDirectory(dir, options.path);
    } catch (IOException e) {
      throw new IOException("Failed to obtain OutputDirectory.", e);
    } finally {
      withMetadata.detach(previous);
    }
  }

  private void doCat(CatCommand options) throws IOException, InterruptedException {
    OutputStream output;
    if (options.file != null) {
      output = new FileOutputStream(options.file);

      if (!options.file.exists()) {
        options.file.createNewFile();
      }
    } else {
      output = System.out;
    }

    Context withMetadata = TracingMetadataUtils.contextWithMetadata("cat");
    Context previous = withMetadata.attach();
    OutputDirectory dir;
    try {
      getCache().downloadBlob(options.digest, output);
    } catch (CacheNotFoundException e) {
      System.err.println("Error: " + e);
    } finally {
      withMetadata.detach(previous);
      output.close();
    }
  }

  private void doShowAction(ShowActionCommand options) throws IOException, InterruptedException {
    if (options.file != null && options.actionDigest != null) {
      System.err.println("Only one of --file or --action_digest should be specified");
      System.exit(1);
    }
    if (options.file != null) {
      printActionV1(getActionV1FromFile(options.file), options.limit);
    } else if (options.actionDigest != null) {
      printAction(options.actionDigest, options.limit);
    } else {
      System.err.println("Specify --file or --action_digest");
      System.exit(1);
    }
  }

  private void doShowCommand(ShowCommandCommand options) throws IOException, InterruptedException {
    if (options.digest != null) {
      printCommand(getCommand(options.digest));
    } else {
      System.err.println("Specify --digest");
      System.exit(1);
    }
  }

  private void doShowActionResult(ShowActionResultCommand options)
      throws IOException, InterruptedException {
    ActionResult.Builder builder = ActionResult.newBuilder();
    FileInputStream fin = new FileInputStream(options.file);
    TextFormat.getParser().merge(new InputStreamReader(fin), builder);
    printActionResult(builder.build(), options.limit);
  }

  private void doRun(RunCommand options) throws IOException, InterruptedException, ParamException {
    Path path = options.path != null ? options.path : Files.createTempDir().toPath();

    if (options.file != null && options.actionDigest != null) {
      System.err.println("Only one of --file or --action_digest should be specified");
      System.exit(1);
    }
    if (options.file != null) {
      setupDocker(getActionV1FromFile(options.file), path);
    } else if (options.actionDigest != null) {
      setupDocker(getAction(options.actionDigest), path);
    } else if (!clientOptions.grpcLog.isEmpty()) {
      LogParserUtils parser = new LogParserUtils(clientOptions.grpcLog);
      List<Digest> actions = parser.failedActions();
      if (actions.size() == 0) {
        System.err.println("No action specified. No failed actions found in GRPC log.");
        System.exit(1);
      } else if (actions.size() > 1) {
        System.err.println(
            "No action specified. Multiple failed actions found in GRPC log. Add one of the following options:");
        for (Digest d : actions) {
          System.err.println(" --digest " + d.getHash() + "/" + d.getSizeBytes());
        }
        System.exit(1);
      }
      Digest action = actions.get(0);
      setupDocker(getAction(action), path);
    } else {
      System.err.println("Specify --file or --action_digest");
      System.exit(1);
    }
  }

  private void runRemoteProxy(RunRecord.Builder record, OutErr outErr) {
    Preconditions.checkNotNull(proxyStubs, "--proxy should be set");
    int proxyInstance = rand.nextInt(proxyStubs.size());
    Utils.vlog(
        remoteOptions.verbosity,
        2,
        "Connecting to proxy at %s...",
        proxyTargets.get(proxyInstance));
    Iterator<RunResponse> replies =
        proxyStubs
            .get(proxyInstance)
            .run(RunRequest.newBuilder().setCommand(record.getCommandParameters()).build());
    RunResult result = null;
    while (replies.hasNext()) {
      RunResponse resp = replies.next();
      if (!resp.getStdout().isEmpty()) {
        outErr.printOut(resp.getStdout());
      }
      if (resp.getStderr().isEmpty()) {
        outErr.printErr(resp.getStderr());
      }
      if (resp.hasResult()) {
        result = resp.getResult();
      }
    } // Always read the entire stream.
    if (result == null) {
      result = RunResult.newBuilder()
          .setStatus(RunResult.Status.REMOTE_ERROR)
          .setExitCode(RemoteRunner.REMOTE_ERROR_EXIT_CODE)
          .setMessage("Remote client proxy failed to return a run result.")
          .build();
    }
    record.setResult(result);
  }

  private static String runCommandParametersToString(RunCommandParameters params) {
    // TODO(olaola): properly quote this.
    StringBuilder sb = new StringBuilder();
    if (!params.getBuildRequestId().isEmpty()) {
      sb.append("--build_request_id ");
      sb.append(params.getBuildRequestId());
      sb.append(" ");
    }
    if (!params.getInvocationId().isEmpty()) {
      sb.append("--invocation_id ");
      sb.append(params.getInvocationId());
      sb.append(" ");
    }
    if (!params.getId().isEmpty()) {
      sb.append("--id ");
      sb.append(params.getId());
      sb.append(" ");
    }
    if (params.getAcceptCached()) {
      sb.append("--accept_cached ");
      sb.append(params.getAcceptCached());
      sb.append(" ");
    }
    if (params.getDoNotCache()) {
      sb.append("--do_not_cache ");
      sb.append(params.getDoNotCache());
      sb.append(" ");
    }
    if (params.getInputsCount() > 0) {
      sb.append("--inputs ");
      for (String i : params.getInputsList()) {
        sb.append(i);
        sb.append(" ");
      }
    }
    if (params.getOutputFilesCount() > 0) {
      sb.append("--output_files ");
      for (String i : params.getOutputFilesList()) {
        sb.append(i);
        sb.append(" ");
      }
    }
    if (params.getOutputDirectoriesCount() > 0) {
      sb.append("--output_directories ");
      for (String i : params.getOutputDirectoriesList()) {
        sb.append(i);
        sb.append(" ");
      }
    }
    if (params.getCommandCount() > 0) {
      sb.append("--command ");
      for (String i : params.getCommandList()) {
        sb.append(i);
        sb.append(" ");
      }
    }
    if (params.getIgnoreInputsCount() > 0) {
      sb.append("--ignore_inputs ");
      for (String i : params.getIgnoreInputsList()) {
        sb.append(i);
        sb.append(" ");
      }
    }
    if (params.getEnvironmentVariablesCount() > 0) {
      sb.append("--environment_variables ");
      for (Map.Entry<String, String> e : params.getEnvironmentVariablesMap().entrySet()) {
        sb.append(e.getKey());
        sb.append("=");
        sb.append(e.getValue());
        sb.append(",");
      }
      sb.deleteCharAt(sb.length() - 1);
      sb.append(" ");
    }
    if (params.getPlatformCount() > 0) {
      sb.append("--platform ");
      for (Map.Entry<String, String> e : params.getPlatformMap().entrySet()) {
        sb.append(e.getKey());
        sb.append("=");
        sb.append(e.getValue());
        sb.append(",");
      }
      sb.deleteCharAt(sb.length() - 1);
      sb.append(" ");
    }
    if (!params.getServerLogsPath().isEmpty()) {
      sb.append("--server_logs_path ");
      sb.append(params.getServerLogsPath());
      sb.append(" ");
    }
    if (params.getExecutionTimeout() != 0) {
      sb.append("--execution_timeout ");
      sb.append(params.getExecutionTimeout());
      sb.append(" ");
    }
    if (params.getSaveExecutionData()) {
      sb.append("--save_execution_data true ");
    }
    if (params.getLocalFallback()) {
      sb.append("--local_fallback true ");
    }
    sb.deleteCharAt(sb.length() - 1);
    return sb.toString();
  }

  public RunRecord.Builder newFromCommandParameters(RunCommandParameters params) {
    RunCommandParameters.Builder paramsWithIds = params.toBuilder();
    if (paramsWithIds.getInvocationId().isEmpty()) {
      paramsWithIds.setInvocationId(UUID.randomUUID().toString());
    }
    if (paramsWithIds.getBuildRequestId().isEmpty()) {
      paramsWithIds.setBuildRequestId(UUID.randomUUID().toString());
    }
    if (paramsWithIds.getId().isEmpty()) {
      // TODO(olaola): switch to a stable command id.
      paramsWithIds.setId(UUID.randomUUID().toString().substring(0, 8));
    }
    return RunRecord.newBuilder()
        .setCommandParameters(paramsWithIds)
        .setStage(Stage.QUEUED)
        .setLocalTimestamps(
            LocalTimestamps.newBuilder().setQueuedStart(Utils.getCurrentTimestamp(clock)));
  }

  public RunRecord.Builder newFromCommandOptions(RunRemoteCommand options) {
    return newFromCommandParameters(RunCommandParameters.newBuilder()
        .setBuildRequestId(options.buildRequestId)
        .setInvocationId(options.invocationId)
        .setId(options.id)
        .setWorkingDirectory(options.workingDirectory)
        .setToolName(options.toolName)
        .setAcceptCached(options.acceptCached)
        .setDoNotCache(options.doNotCache)
        .addAllInputs(options.inputs)
        .addAllOutputFiles(options.outputFiles)
        .addAllOutputDirectories(options.outputDirectories)
        .addAllCommand(options.command)
        .addAllIgnoreInputs(options.ignoreInputs)
        .putAllEnvironmentVariables(options.environmentVariables)
        .putAllPlatform(options.platform)
        .setServerLogsPath(options.serverLogsPath)
        .setExecutionTimeout(options.executionTimeout)
        .setSaveExecutionData(options.saveExecutionData)
        .setLocalFallback(options.localFallback)
        .build());
  }

  public void runRemote(RunRecord.Builder record, OutErr outErr) {
    if (Strings.isNullOrEmpty(clientOptions.proxy)) {
      getRunner().runRemote(record, outErr);
    } else {
      runRemoteProxy(record, outErr);
    }
    RunResult result = record.getResult();
    switch (result.getStatus()) {
      case NON_ZERO_EXIT:
        outErr.printErrLn("Remote action FAILED with exit code " + result.getExitCode());
        break;
      case TIMEOUT:
        int timeout = record.getCommandParameters().getExecutionTimeout();
        outErr.printErrLn("Remote action TIMED OUT after " + timeout + " seconds.");
        break;
      case INTERRUPTED:
        outErr.printErrLn("Remote execution was INTERRUPTED.");
        break;
      case REMOTE_ERROR:
        outErr.printErrLn("Remote execution error: " + result.getMessage());
        break;
      case LOCAL_ERROR:
        outErr.printErrLn("Local setup error: " + result.getMessage());
        break;
    }
  }

  private RunCommandParameters findRemoteCommand(ProxyPrintRemoteCommand options)
      throws IOException {
    StatsRequest req =
        StatsRequest.newBuilder()
            .setFull(true)
            .setInvocationId(options.invocationId)
            .setCommandId(options.commandId)
            .build();
    if (options.proxyStatsFile != null) {
      StatsResponse.Builder builder = StatsResponse.newBuilder();
      try (FileInputStream fin = new FileInputStream(options.proxyStatsFile)) {
        TextFormat.getParser().merge(new InputStreamReader(fin), builder);
      }
      StatsResponse resp = builder.build();
      for (RunRecord record : resp.getRunRecordsList()) {
        if (Stats.shouldCountRecord(record.toBuilder(), req)) {
          return record.getCommandParameters(); // Return the first one that matched.
        }
      }
    }
    Preconditions.checkNotNull(proxyStubs, "--proxy should be set");
    for (CommandServiceBlockingStub proxyStub : proxyStubs) {
      Iterator<StatsResponse> replies = proxyStub.stats(req);
      if (replies.hasNext()) {
        StatsResponse resp = replies.next();
        if (resp.getRunRecordsCount() > 0) {
          return resp.getRunRecords(0).getCommandParameters(); // Return the first one that matched.
        }
      }
    }
    return null;
  }

  private void doProxyPrintRemoteCommand(ProxyPrintRemoteCommand options) throws IOException {
    RunCommandParameters params = findRemoteCommand(options);
    System.out.println(params == null ?
        "Record with id " + options.commandId + " was not found." :
        runCommandParametersToString(params));
  }

  private void doProxyStats(ProxyStatsCommand options) throws IOException {
    StatsRequest.Builder req =
        StatsRequest.newBuilder()
            .setFull(options.full || proxyStubs.size() > 1)
            .setInvocationId(options.invocationId)
            .setCommandId(options.commandId)
            .setStatus(options.status)
            .setSummary(proxyStubs.size() == 1);
    if (options.fromTs > 0) {
      req.setFromTs(Timestamp.newBuilder().setSeconds(options.fromTs));
    }
    if (options.toTs > 0) {
      req.setToTs(Timestamp.newBuilder().setSeconds(options.toTs));
    }
    if (options.proxyStatsFile != null) {
      StatsResponse.Builder builder = StatsResponse.newBuilder();
      try (FileInputStream fin = new FileInputStream(options.proxyStatsFile)) {
        TextFormat.getParser().merge(new InputStreamReader(fin), builder);
      }
      StatsResponse.Builder aggr = StatsResponse.newBuilder();
      List<RunRecord.Builder> records = builder.build().getRunRecordsList().stream()
          .map(RunRecord::toBuilder)
          .sorted((r1, r2) ->
              r1.getCommandParameters().getId().compareTo(r2.getCommandParameters().getId()))
          .collect(Collectors.toList());
      aggr.setProxyStats(Stats.computeStats(req.build(), records));
      if (options.full) {
        aggr.addAllRunRecords(builder.build().getRunRecordsList());
      }
      System.out.println(aggr.toString());
      return;
    }
    Preconditions.checkNotNull(proxyStubs, "--proxy should be set");
    StatsResponse.Builder aggr = StatsResponse.newBuilder();
    List<RunRecord.Builder> records = new ArrayList<>();
    for (CommandServiceBlockingStub proxyStub : proxyStubs) {
      Iterator<StatsResponse> replies = proxyStub.stats(req.build());
      while (replies.hasNext()) {
        StatsResponse resp = replies.next();
        records.addAll(
            resp.getRunRecordsList().stream()
                .map(RunRecord::toBuilder)
                .collect(Collectors.toList()));
        if (resp.hasProxyStats()) {
          aggr.setProxyStats(resp.getProxyStats());
        }
      }
    }
    if (options.full) {
      records.sort((r1, r2) ->
          r1.getCommandParameters().getId().compareTo(r2.getCommandParameters().getId()));
      aggr.addAllRunRecords(
          records.stream().map(RunRecord.Builder::build).collect(Collectors.toList()));
    }
    if (proxyStubs.size() > 1) {
      aggr.setProxyStats(Stats.computeStats(req.build(), records));
    }
    System.out.println(aggr.toString());
  }

  private void doRunRemote(RunRemoteCommand options) {
    RunRecord.Builder record = newFromCommandOptions(options);
    runRemote(record, OutErr.SYSTEM_OUT_ERR);
    close();
    System.exit(record.getResult().getExitCode());
  }

  // Shutdown remote service.
  public void close() {
    if (rpcLogFile != null) {
      try {
        rpcLogFile.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    try {
      selectAndPerformCommand(args);
    } catch (io.grpc.StatusRuntimeException e) {
      Status s = Status.fromThrowable(e);
      if (s.getCode() == Status.Code.INTERNAL && s.getDescription().contains("http2")) {
        System.err.println("http2 exception. Did you forget --tls_enabled?");
      }
      throw e;
    }
  }

  public static void selectAndPerformCommand(String[] args) throws Exception {
    AuthAndTLSOptions authAndTlsOptions = new AuthAndTLSOptions();
    RemoteOptions remoteOptions = new RemoteOptions();
    RemoteClientOptions remoteClientOptions = new RemoteClientOptions();
    LsCommand lsCommand = new LsCommand();
    LsOutDirCommand lsOutDirCommand = new LsOutDirCommand();
    GetDirCommand getDirCommand = new GetDirCommand();
    GetOutDirCommand getOutDirCommand = new GetOutDirCommand();
    CatCommand catCommand = new CatCommand();
    FailedActionsCommand failedActionsCommand = new FailedActionsCommand();
    ShowActionCommand showActionCommand = new ShowActionCommand();
    ShowCommandCommand showCommandCommand = new ShowCommandCommand();
    ShowActionResultCommand showActionResultCommand = new ShowActionResultCommand();
    PrintLogCommand printLogCommand = new PrintLogCommand();
    ProxyStatsCommand proxyStatsCommand = new ProxyStatsCommand();
    ProxyPrintRemoteCommand proxyPrintRemoteCommand = new ProxyPrintRemoteCommand();
    RunCommand runCommand = new RunCommand();
    RunRemoteCommand runRemoteCommand = new RunRemoteCommand();

    JCommander optionsParser =
        JCommander.newBuilder()
            .programName("remote_client")
            .addObject(authAndTlsOptions)
            .addObject(remoteOptions)
            .addObject(remoteClientOptions)
            .addCommand("ls", lsCommand)
            .addCommand("lsoutdir", lsOutDirCommand)
            .addCommand("getdir", getDirCommand)
            .addCommand("getoutdir", getOutDirCommand)
            .addCommand("cat", catCommand)
            .addCommand("show_action", showActionCommand, "sa")
            .addCommand("show_command", showCommandCommand)
            .addCommand("show_action_result", showActionResultCommand, "sar")
            .addCommand("printlog", printLogCommand)
            .addCommand("proxy_stats", proxyStatsCommand)
            .addCommand("proxy_print_command", proxyPrintRemoteCommand)
            .addCommand("run", runCommand)
            .addCommand("run_remote", runRemoteCommand)
            .addCommand("failed_actions", failedActionsCommand)
            .build();
    optionsParser.setExpandAtSign(false);

    try {
      optionsParser.parse(args);
    } catch (ParameterException e) {
      System.err.println("Unable to parse options: " + e.getLocalizedMessage());
      optionsParser.usage();
      System.exit(1);
    }

    if (remoteClientOptions.help) {
      optionsParser.usage();
      return;
    }

    if (optionsParser.getParsedCommand() == null) {
      System.err.println("No command specified.");
      optionsParser.usage();
      System.exit(1);
    }

    RemoteClient client = new RemoteClient(remoteOptions, remoteClientOptions, authAndTlsOptions);
    switch (optionsParser.getParsedCommand()) {
      case "printlog":
        client.doPrintLog(printLogCommand);
        break;
      case "ls":
        client.doLs(lsCommand);
        break;
      case "lsoutdir":
        client.doLsOutDir(lsOutDirCommand);
        break;
      case "getdir":
        client.doGetDir(getDirCommand);
        break;
      case "getoutdir":
        client.doGetOutDir(getOutDirCommand);
        break;
      case "cat":
        client.doCat(catCommand);
        break;
      case "show_action":
        client.doShowAction(showActionCommand);
        break;
      case "show_command":
        client.doShowCommand(showCommandCommand);
        break;
      case "show_action_result":
        client.doShowActionResult(showActionResultCommand);
        break;
      case "run":
        client.doRun(runCommand);
        break;
      case "proxy_print_command":
        client.doProxyPrintRemoteCommand(proxyPrintRemoteCommand);
        break;
      case "proxy_stats":
        client.doProxyStats(proxyStatsCommand);
        break;
      case "run_remote":
        client.doRunRemote(runRemoteCommand);
        break;
      case "failed_actions":
        client.doFailedActions(failedActionsCommand);
        break;
      default:
        throw new IllegalArgumentException("Unknown command.");
    }
  }
}
