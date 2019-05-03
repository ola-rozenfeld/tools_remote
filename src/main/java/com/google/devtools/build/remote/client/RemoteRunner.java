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

import static com.google.devtools.build.remote.client.util.Utils.getFromFuture;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.LogFile;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.Platform;
import com.google.common.base.Throwables;
import com.google.devtools.build.lib.remote.commands.Command;
import com.google.devtools.build.lib.remote.commands.ExecutionOptions;
import com.google.devtools.build.lib.remote.commands.ExecutionOptions.LocalFallback;
import com.google.devtools.build.lib.remote.commands.Labels;
import com.google.devtools.build.lib.remote.commands.CommandResult;
import com.google.devtools.build.lib.remote.commands.CommandResult.Status;
import com.google.devtools.build.lib.remote.stats.ExecutionData;
import com.google.devtools.build.lib.remote.stats.LocalTimestamps;
import com.google.devtools.build.lib.remote.stats.RunRecord;
import com.google.devtools.build.lib.remote.stats.RunRecord.Stage;
import com.google.devtools.build.remote.client.LogParserUtils.ParamException;
import com.google.devtools.build.remote.client.RemoteClientOptions.RunRemoteCommand;
import com.google.devtools.build.remote.client.TreeNodeRepository.NodeStats;
import com.google.devtools.build.remote.client.TreeNodeRepository.TreeNode;
import com.google.devtools.build.remote.client.util.Clock;
import com.google.devtools.build.remote.client.util.DigestUtil;
import com.google.devtools.build.remote.client.util.DigestUtil.ActionKey;
import com.google.devtools.build.remote.client.util.TracingMetadataUtils;
import com.google.devtools.build.remote.client.util.Utils;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import io.grpc.Context;
import io.grpc.Status.Code;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** A client to execute actions remotely. */
public class RemoteRunner {
  public static final int TIMEOUT_EXIT_CODE = /*SIGNAL_BASE=*/ 128 + /*SIGALRM=*/ 14;
  public static final int REMOTE_ERROR_EXIT_CODE = 34;
  public static final int LOCAL_ERROR_EXIT_CODE = 35;
  public static final int INTERRUPTED_EXIT_CODE = 8;

  private final RemoteOptions remoteOptions;
  private final Path execRoot;
  private final DigestUtil digestUtil;
  private final GrpcRemoteCache cache;
  private final GrpcRemoteExecutor executor;
  private final RemoteRetrier retrier;
  private final FileCache inputFileCache;
  private final TreeNodeRepository treeNodeRepository;
  private final Clock clock;

  public RemoteRunner(
      RemoteOptions remoteOptions,
      RemoteClientOptions clientOptions,
      DigestUtil digestUtil,
      GrpcRemoteCache cache,
      GrpcRemoteExecutor executor,
      Clock clock) {
    this.remoteOptions = remoteOptions;
    this.execRoot = remoteOptions.execRoot.toAbsolutePath();
    this.digestUtil = digestUtil;
    this.cache = cache;
    this.executor = executor;
    this.clock = clock;
    retrier = RemoteRetrier.newExecRetrier(remoteOptions.remoteRetry);
    inputFileCache = new FileCache(digestUtil);
    treeNodeRepository =
        new TreeNodeRepository(execRoot, inputFileCache, digestUtil, clientOptions.dynamicInputs);
  }

  private static build.bazel.remote.execution.v2.Command buildRemoteCommand(Command command)
      throws ParamException {
    build.bazel.remote.execution.v2.Command.Builder result =
        build.bazel.remote.execution.v2.Command.newBuilder();

    result.addAllOutputFiles(
        command.getOutputFilesList().stream().sorted().collect(Collectors.toList()));
    result.addAllOutputDirectories(
        command.getOutputDirectoriesList().stream()
            .sorted()
            .collect(Collectors.toList()));

    if (command.getArgsCount() == 0) {
      throw new ParamException("At least one command line argument should be specified.");
    }
    result.addAllArguments(command.getArgsList());

    ExecutionOptions execOptions = command.getExecutionOptions();
    Map<String,String> inputPlatform = execOptions.getPlatform();
    if (inputPlatform.isEmpty()) {
      throw new ParamException("A platform should be specified.");
    }
    TreeSet<String> platformEntries = new TreeSet<>(inputPlatform.keySet());
    Platform.Builder platform = Platform.newBuilder();
    for (String var : platformEntries) {
      platform.addPropertiesBuilder().setName(var).setValue(inputPlatform.get(var));
    }
    result.setPlatform(platform.build());

    // Sorting the environment pairs by variable name.
    Map<String,String> env = execOptions.getEnvironmentVariables();
    if (!env.isEmpty()) {
      TreeSet<String> variables = new TreeSet<>(env.keySet());
      for (String var : variables) {
        result.addEnvironmentVariablesBuilder().setName(var).setValue(env.get(var));
      }
    }
    result.setWorkingDirectory(execOptions.getWorkingDirectory());
    return result.build();
  }

  private static Action buildAction(
      Digest cmdDigest, Digest inputRoot, ExecutionOptions execOptions) {
    Action.Builder action = Action.newBuilder()
        .setCommandDigest(cmdDigest)
        .setInputRootDigest(inputRoot)
        .setDoNotCache(execOptions.getDoNotCache());
    int timeoutSeconds = execOptions.getExecutionTimeout();
    if (timeoutSeconds > 0) {
      action.setTimeout(Duration.newBuilder().setSeconds(timeoutSeconds));
    }
    return action.build();
  }

  private CommandResult.Builder downloadRemoteResults(
      ActionResult result, OutErr outErr, RunRecord.Builder record)
      throws IOException, InterruptedException {
    cache.download(result, execRoot, outErr, record);
    Utils.vlog(
        remoteOptions.verbosity,
        2,
        "%s> Number of outputs: %d, total bytes: %d",
        record.getCommand().getLabels().getCommandId(),
        record.getActionMetadata().getNumOutputs(),
        record.getActionMetadata().getTotalOutputBytes());
    int exitCode = result.getExitCode();
    if (record.getCommand().getExecutionOptions().getSaveExecutionData()) {
      ExecutionData.Builder execData = record.getExecutionDataBuilder();
      for (OutputFile o : result.getOutputFilesList()) {
        execData.addOutputFilesBuilder().setPath(o.getPath()).setDigest(o.getDigest());
      }
    }
    return CommandResult.newBuilder()
        .setStatus(exitCode == 0 ? Status.SUCCESS : Status.NON_ZERO_EXIT)
        .setExitCode(exitCode);
  }

  private void maybeDownloadServerLogs(
      ExecuteResponse resp, ActionKey actionKey, Path logDir, OutErr outErr)
      throws InterruptedException {
    if (logDir == null) {
      return;
    }
    ActionResult result = resp.getResult();
    if (resp.getServerLogsCount() > 0
        && (result.getExitCode() != 0 || resp.getStatus().getCode() != Code.OK.value())) {
      Path parent = logDir.resolve(actionKey.getDigest().getHash());
      Path logPath = null;
      int logCount = 0;
      for (Map.Entry<String, LogFile> e : resp.getServerLogsMap().entrySet()) {
        if (e.getValue().getHumanReadable()) {
          logPath = parent.resolve(e.getKey());
          logCount++;
          try {
            getFromFuture(cache.downloadFile(logPath, e.getValue().getDigest()));
          } catch (IOException ex) {
            outErr.printErrLn("Failed downloading server logs from the remote cache.");
          }
        }
      }
      if (logCount > 0 && remoteOptions.verbosity > 0) {
        outErr.printErrLn(
            "Server logs of failing action:\n   " + (logCount > 1 ? parent : logPath));
      }
    }
  }

  private CommandResult handleError(
      IOException exception, OutErr outErr, ActionKey actionKey, Path logDir,
      RunRecord.Builder record) throws InterruptedException {
    // Regardless of cause, if we are interrupted, we should stop without displaying a user-visible
    // failure/stack trace.
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException();
    }
    if (exception.getCause() instanceof ExecutionStatusException) {
      ExecutionStatusException e = (ExecutionStatusException) exception.getCause();
      if (e.getResponse() != null) {
        ExecuteResponse resp = e.getResponse();
        maybeDownloadServerLogs(resp, actionKey, logDir, outErr);
        if (resp.hasResult()) {
          // We try to download all (partial) results even on server error, for debuggability.
          try {
            cache.download(resp.getResult(), execRoot, outErr, record);
          } catch (IOException ex) {
            // Ignore this error, propagate the original.
            outErr.printErrLn("Failed downloading results from the remote cache.");
          }
        }
      }
      if (e.isExecutionTimeout()) {
        return CommandResult.newBuilder()
            .setStatus(Status.TIMEOUT)
            .setExitCode(TIMEOUT_EXIT_CODE)
            .build();
      }
    }
    return CommandResult.newBuilder()
        .setStatus(Status.REMOTE_ERROR)
        .setExitCode(REMOTE_ERROR_EXIT_CODE)
        .setMessage(exceptionMessage(exception))
        .build();
  }

  private String exceptionMessage(Exception e) {
    return remoteOptions.verbosity > 0 ? Throwables.getStackTraceAsString(e) : e.getMessage();
  }

  public static boolean isFailureStatus(Status status) {
    return status == Status.REMOTE_ERROR
        || status == Status.LOCAL_ERROR
        || status == Status.NON_ZERO_EXIT
        || status == Status.TIMEOUT;
  }

  private void nextStage(Stage stage, RunRecord.Builder record) {
    Stage prevStage = record.getStage();
    record.setStage(stage);
    LocalTimestamps.Builder ts = record.getLocalTimestampsBuilder();
    Timestamp currTimestamp = Utils.getCurrentTimestamp(clock);
    // Assumes stages follow the natural order. This gets really hairy with the
    // UPLOADING_INPUTS+EXECUTE+DOWNLOADING_OUTPUTS outer retry workflow!
    switch (stage) {
      case COMPUTING_INPUT_TREE:
        ts.setQueuedEnd(currTimestamp);
        ts.setInputTreeStart(currTimestamp);
        break;
      case CHECKING_ACTION_CACHE:
        ts.setInputTreeEnd(currTimestamp);
        ts.setCheckActionCacheStart(currTimestamp);
        break;
      case UPLOADING_INPUTS:
        if (prevStage == Stage.CHECKING_ACTION_CACHE) {
          ts.setCheckActionCacheEnd(currTimestamp); // It may be the outer retry.
        }
        ts.setUploadInputsStart(currTimestamp);
        break;
      case EXECUTING:
        ts.setUploadInputsEnd(currTimestamp);
        ts.setExecuteStart(currTimestamp);
        break;
      case DOWNLOADING_OUTPUTS:
        if (prevStage == Stage.EXECUTING) {
          ts.setExecuteEnd(currTimestamp);
        } else {
          ts.setCheckActionCacheEnd(currTimestamp);
        }
        ts.setDownloadOutputsStart(currTimestamp);
        break;
      case FINISHED:
        // We can get here after an error in any previous stage.
        switch (prevStage) {
          case QUEUED:
            ts.setQueuedEnd(currTimestamp);
            break;
          case COMPUTING_INPUT_TREE:
            ts.setInputTreeEnd(currTimestamp);
            break;
          case CHECKING_ACTION_CACHE:
            ts.setCheckActionCacheEnd(currTimestamp);
            break;
          case UPLOADING_INPUTS:
            ts.setUploadInputsEnd(currTimestamp);
            break;
          case EXECUTING:
            ts.setExecuteEnd(currTimestamp);
            break;
          case DOWNLOADING_OUTPUTS:
            ts.setDownloadOutputsEnd(currTimestamp);
            break;
          default:
        }
        break;
      default:
        // Don't support other things for now.
    }
  }

  // Runs remotely, no local fallback.
  public void runRemoteOnly(RunRecord.Builder record, OutErr outErr) {
    Command command = record.getCommand();
    Labels labels = command.getLabels();
    ExecutionOptions execOptions = command.getExecutionOptions();
    String id = labels.getCommandId();
    Utils.vlog(
        remoteOptions.verbosity, 2, "%s> Build request ID: %s", id, labels.getBuildRequestId());
    Utils.vlog(remoteOptions.verbosity, 2, "%s> Invocation ID: %s", id, labels.getInvocationId());
    TreeNode inputRoot;
    build.bazel.remote.execution.v2.Command reCmd;
    Action action;
    Digest cmdDigest;
    try {
      reCmd = buildRemoteCommand(command);
      nextStage(Stage.COMPUTING_INPUT_TREE, record);
      Utils.vlog(remoteOptions.verbosity, 2, "%s> Command: \n%s", id, reCmd);
      Utils.vlog(remoteOptions.verbosity, 2, "%s> Computing input Merkle tree...", id);
      inputRoot = treeNodeRepository.buildFromFiles(
          command.getInputsList().stream().map(Paths::get).collect(Collectors.toList()),
          command.getIgnoreInputsList());
      treeNodeRepository.computeMerkleDigests(inputRoot);
      cmdDigest = digestUtil.compute(reCmd);
      action = buildAction(cmdDigest, treeNodeRepository.getMerkleDigest(inputRoot), execOptions);
      Utils.vlog(remoteOptions.verbosity, 2, "%s> Action: \n%s", id, action);
    } catch (Exception e) {
      nextStage(Stage.FINISHED, record);
      record.setResult(
          CommandResult.newBuilder()
              .setStatus(Status.REMOTE_ERROR)
              .setExitCode(LOCAL_ERROR_EXIT_CODE)
              .setMessage(exceptionMessage(e)));
      return;
    }
    nextStage(Stage.CHECKING_ACTION_CACHE, record);
    ActionKey actionKey = digestUtil.computeActionKey(action);
    Utils.vlog(
        remoteOptions.verbosity,
        2,
        "%s> Action ID: %s",
        id,
        digestUtil.toString(actionKey.getDigest()));
    // Stats computation:
    NodeStats stats = treeNodeRepository.getStats(inputRoot);
    int numInputs = stats.getNumInputs() + 2;
    long totalInputBytes =
        stats.getTotalInputBytes()
            + cmdDigest.getSizeBytes()
            + actionKey.getDigest().getSizeBytes();
    Utils.vlog(
        remoteOptions.verbosity,
        2,
        "%s> Number of inputs: %d, total bytes: %d",
        id,
        numInputs,
        totalInputBytes);
    record
        .getActionMetadataBuilder()
        .setNumInputs(numInputs)
        .setTotalInputBytes(totalInputBytes);
    Context withMetadata =
        TracingMetadataUtils.contextWithMetadata(
            labels.getBuildRequestId(), labels.getInvocationId(), actionKey, labels.getToolName());
    Context previous = withMetadata.attach();
    try {
      if (execOptions.getSaveExecutionData()) {
        ExecutionData.Builder execData = record.getExecutionDataBuilder();
        treeNodeRepository.saveInputData(inputRoot, execData);
        execData.setCommandDigest(cmdDigest);
        execData.setActionDigest(actionKey.getDigest());
      }
      boolean acceptCachedResult = execOptions.getAcceptCached() && !execOptions.getDoNotCache();
      ActionResult cachedResult =
          acceptCachedResult ? cache.getCachedActionResult(actionKey) : null;
      if (cachedResult != null) {
        if (cachedResult.getExitCode() != 0) {
          // The remote cache must never serve a failed action.
          throw new RuntimeException(
              "The remote cache is in an invalid state as it"
                  + " served a failed action. Action digest: "
                  + digestUtil.toString(actionKey.getDigest()));
        }
        try {
          Utils.vlog(
              remoteOptions.verbosity, 2, "%s> Found cached result, downloading outputs...", id);
          nextStage(Stage.DOWNLOADING_OUTPUTS, record);
          CommandResult.Builder result = downloadRemoteResults(cachedResult, outErr, record);
          record.setResult(result.setStatus(Status.CACHE_HIT));
          return;
        } catch (CacheNotFoundException e) {
          // No cache hit, so we fall through to remote execution.
          // We set acceptCachedResult to false in order to force the action re-execution.
          acceptCachedResult = false;
        }
      }
      ExecuteRequest request =
          ExecuteRequest.newBuilder()
              .setInstanceName(remoteOptions.remoteInstanceName)
              .setActionDigest(actionKey.getDigest())
              .setSkipCacheLookup(!acceptCachedResult)
              .build();
      Path logDir = Paths.get(execOptions.getServerLogsPath());
      try {
        record.setResult(
            retrier.execute(
                () -> {
                  Utils.vlog(remoteOptions.verbosity, 2, "%s> Checking inputs to upload...", id);
                  nextStage(Stage.UPLOADING_INPUTS, record);
                  cache.ensureInputsPresent(treeNodeRepository, inputRoot, action, reCmd, record);
                  nextStage(Stage.EXECUTING, record);
                  Utils.vlog(
                      remoteOptions.verbosity,
                      2,
                      "%s> Executing remotely:\n%s",
                      id,
                      String.join(" ", command.getArgsList()));
                  ExecuteResponse reply = executor.executeRemotely(request);
                  String message = reply.getMessage();
                  if ((reply.getResult().getExitCode() != 0
                      || reply.getStatus().getCode() != Code.OK.value())
                      && !message.isEmpty()) {
                    outErr.printErrLn(message);
                  }
                  nextStage(Stage.DOWNLOADING_OUTPUTS, record);
                  Utils.vlog(remoteOptions.verbosity, 2, "%s> Downloading outputs...", id);
                  maybeDownloadServerLogs(reply, actionKey, logDir, outErr);
                  ActionResult res = reply.getResult();
                  CommandResult.Builder result = downloadRemoteResults(res, outErr, record);
                  if (res.hasExecutionMetadata()) {
                    record.setRemoteMetadata(res.getExecutionMetadata());
                  }
                  if (reply.getCachedResult()) {
                    result.setStatus(Status.CACHE_HIT);
                  }
                  return result.build();
                }));
      } catch (IOException e) {
        record.setResult(handleError(e, outErr, actionKey, logDir, record));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      record.setResult(
          CommandResult.newBuilder().setStatus(Status.INTERRUPTED).setExitCode(INTERRUPTED_EXIT_CODE));
    } catch (Exception e) {
      record.setResult(
          CommandResult.newBuilder()
              .setStatus(Status.REMOTE_ERROR)
              .setExitCode(REMOTE_ERROR_EXIT_CODE)
              .setMessage(exceptionMessage(e)));
    } finally {
      nextStage(Stage.FINISHED, record);
      Utils.vlog(remoteOptions.verbosity, 2, "%s> Done.", id);
      withMetadata.detach(previous);
    }
  }

  public void runRemote(RunRecord.Builder record, OutErr outErr) {
    runRemoteOnly(record, outErr);
    Command command = record.getCommand();
    ExecutionOptions execOptions = command.getExecutionOptions();
    Status status = record.getResult().getStatus();
    if (execOptions.getLocalFallback().equals(LocalFallback.NONE) || !isFailureStatus(status) ||
        status == Status.TIMEOUT) {
      return;
    }
    // Execute the action locally.
    String id = command.getLabels().getCommandId();
    Utils.vlog(remoteOptions.verbosity, 2, "%s> Falling back to local execution... %s", id);
    record.setStage(Stage.LOCAL_FALLBACK_EXECUTING);
    record.setResultBeforeLocalFallback(record.getResult());
    record.clearResult();

    // TODO(olaola): fall back to docker.
    try {
      // Set up the local directory.
      for (String path : command.getOutputDirectoriesList()) {
        Files.createDirectories(Paths.get(path));
      }
      for (String path : command.getOutputFilesList()) {
        Files.createDirectories(Paths.get(path).getParent());
      }
    } catch (Exception e) {
      record.setResult(
          CommandResult.newBuilder()
              .setStatus(Status.LOCAL_ERROR)
              .setExitCode(LOCAL_ERROR_EXIT_CODE)
              .setMessage(exceptionMessage(e)));
    } finally {
      record.setStage(Stage.FINISHED);
      Utils.vlog(remoteOptions.verbosity, 2, "%s> Done local fallback.", id);
    }
  }
}
