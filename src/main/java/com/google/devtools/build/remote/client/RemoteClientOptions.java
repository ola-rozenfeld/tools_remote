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

import build.bazel.remote.execution.v2.Digest;
import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.EnumConverter;
import com.beust.jcommander.converters.FileConverter;
import com.beust.jcommander.converters.PathConverter;
import com.beust.jcommander.converters.IParameterSplitter;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.remote.commands.ExecutionOptions.LocalFallback;
import com.google.devtools.build.lib.remote.commands.CommandResult.Status;
import com.google.devtools.build.remote.client.util.DigestUtil;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Options for operation of a remote client. */
@Parameters(separators = "=")
public final class RemoteClientOptions {
  @Parameter(names = "--help", description = "This message.", help = true)
  public boolean help;

  @Parameter(names = "--grpc_log", description = "GRPC log to reference for additional information")
  public String grpcLog = "";

  @Parameter(
      names = "--dynamic_inputs",
      variableArity = true,
      converter = PathConverter.class,
      description = "Input directories relative to the exec_root that can change between actions.")
  public List<Path> dynamicInputs = null;

  @Parameter(
      names = "--proxy",
      description = "Optional address of the remote_client_proxy server as HOST:PORT.")
  public String proxy = "";

  @Parameter(
      names = "--proxy_instances",
      description = "Optional number of proxy instances. If it is > 1, it is assumed that all " +
          "instances listen on consecutive ports.")
  public int proxyInstances = 1;

  @Parameters(
      commandDescription = "Recursively lists a Directory in remote cache.",
      separators = "=")
  public static class LsCommand {
    @Parameter(
        names = {"--digest", "-d"},
        required = true,
        converter = DigestConverter.class,
        description = "The digest of the Directory to list in hex_hash/size_bytes.")
    public Digest digest = null;

    @Parameter(
        names = {"--limit", "-l"},
        description = "The maximum number of files in the Directory to list.")
    public int limit = 100;
  }

  @Parameters(
      commandDescription = "Recursively lists an OutputDirectory in remote cache.",
      separators = "=")
  public static class LsOutDirCommand {
    @Parameter(
        names = {"--digest", "-d"},
        required = true,
        converter = DigestConverter.class,
        description = "The digest of the OutputDirectory to list in hex_hash/size_bytes.")
    public Digest digest = null;

    @Parameter(
        names = {"--limit", "-l"},
        description = "The maximum number of files in the OutputDirectory to list.")
    public int limit = 100;
  }

  @Parameters(
      commandDescription = "Recursively downloads a Directory from remote cache.",
      separators = "=")
  public static class GetDirCommand {
    @Parameter(
        names = {"--digest", "-d"},
        required = true,
        converter = DigestConverter.class,
        description = "The digest of the Directory to download in hex_hash/size_bytes.")
    public Digest digest = null;

    @Parameter(
        names = {"--path", "-o"},
        converter = PathConverter.class,
        description = "The local path to download the Directory contents into.")
    public Path path = Paths.get("");
  }

  @Parameters(
      commandDescription = "Recursively downloads a OutputDirectory from remote cache.",
      separators = "=")
  public static class GetOutDirCommand {
    @Parameter(
        names = {"--digest", "-d"},
        required = true,
        converter = DigestConverter.class,
        description = "The digest of the OutputDirectory to download in hex_hash/size_bytes.")
    public Digest digest = null;

    @Parameter(
        names = {"--path", "-o"},
        converter = PathConverter.class,
        description = "The local path to download the OutputDirectory contents into.")
    public Path path = Paths.get("");
  }

  @Parameters(
      commandDescription =
          "Write contents of a blob from remote cache to stdout. If specified, "
              + "the contents of the blob can be written to a specific file instead of stdout.",
      separators = "=")
  public static class CatCommand {
    @Parameter(
        names = {"--digest", "-d"},
        required = true,
        converter = DigestConverter.class,
        description = "The digest in the format hex_hash/size_bytes of the blob to download.")
    public Digest digest = null;

    @Parameter(
        names = {"--file", "-o"},
        converter = FileConverter.class,
        description = "Specifies a file to write the blob contents to instead of stdout.")
    public File file = null;
  }

  @Parameters(
      commandDescription = "Find and print action ids of failed actions from grpc log.",
      separators = "=")
  public static class FailedActionsCommand {}

  @Parameters(
      commandDescription = "Parse and display an Action with its corresponding command.",
      separators = "=")
  public static class ShowActionCommand {
    @Parameter(
        names = {"--textproto", "-p"},
        converter = FileConverter.class,
        description = "Path to a V1 Action proto stored in protobuf text format.")
    public File file = null;

    @Parameter(
        names = {"--digest", "-d"},
        converter = DigestConverter.class,
        description = "Action digest in the form hex_hash/size_bytes. Use for V2 API.")
    public Digest actionDigest = null;

    @Parameter(
        names = {"--limit", "-l"},
        description = "The maximum number of input/output files to list.")
    public int limit = 100;
  }

  @Parameters(
      commandDescription = "Parse and display an Command.",
      separators = "=")
  public static class ShowCommandCommand {
    @Parameter(
        names = {"--digest", "-d"},
        converter = DigestConverter.class,
        description = "Command digest in the form hex_hash/size_bytes.")
    public Digest digest = null;

    @Parameter(
        names = {"--limit", "-l"},
        description = "The maximum number of input/output files to list.")
    public int limit = 100;
  }

  @Parameters(commandDescription = "Parse and display an ActionResult.", separators = "=")
  public static class ShowActionResultCommand {
    @Parameter(
        names = {"--textproto", "-p"},
        required = true,
        converter = FileConverter.class,
        description = "Path to a ActionResult proto stored in protobuf text format.")
    public File file = null;

    @Parameter(
        names = {"--limit", "-l"},
        description = "The maximum number of output files to list.")
    public int limit = 100;
  }

  @Parameters(
      commandDescription =
          "Write all log entries from a Bazel gRPC log to standard output. The Bazel gRPC log "
              + "consists of a sequence of delimited serialized LogEntry protobufs, as produced by "
              + "the method LogEntry.writeDelimitedTo(OutputStream).",
      separators = "=")
  public static class PrintLogCommand {
    @Parameter(
        names = {"--group_by_action", "-g"},
        description =
            "Display entries grouped by action instead of individually. Entries are printed in order "
                + "of their call started timestamps (earliest first). Entries without action-id"
                + "metadata are skipped.")
    public boolean groupByAction;
  }

  @Parameters(
      commandDescription =
          "Sets up a directory and Docker command to locally run a single action "
              + "given its Action proto. This requires the Action's inputs to be stored in CAS so that "
              + "they can be retrieved.",
      separators = "=")
  public static class RunCommand {
    @Parameter(
        names = {"--textproto", "-p"},
        converter = FileConverter.class,
        description =
            "Path to the Action proto stored in protobuf text format to be run in the "
                + "container.")
    public File file = null;

    @Parameter(
        names = {"--digest", "-d"},
        converter = DigestConverter.class,
        description = "Action digest in the form hex_hash/size_bytes. Use for V2 API.")
    public Digest actionDigest = null;

    @Parameter(
        names = {"--path", "-o"},
        converter = PathConverter.class,
        description = "Path to set up the action inputs in.")
    public Path path = null;
  }

  @Parameters(
      commandDescription =
          "Runs a command remotely, uploading all required inputs to CAS, and downloading outputs.",
      separators = "=")
  public static class RunRemoteCommand {
    @Parameter(
        names = "--build_request_id",
        description = "An optional UUID to use for remote server requests to identify a build.")
    public String buildRequestId = "";

    @Parameter(
        names = "--invocation_id",
        description = "An optional UUID to use for remote server requests to identify an invocation.")
    public String invocationId = "";

    @Parameter(
        names = "--tool_name",
        description = "An optional tool name to provide to the remote server.")
    public String toolName = "";

    @Parameter(
        names = "--id",
        description = "An optional identifier of this command.")
    public String id = "";

    @Parameter(
        names = "--labels",
        converter = MapConverter.class,
        description = "Optional labels for identifying the command, formatted as a " +
            "comma-separated list of <label_name>=value pairs.")
    public Map<String,String> labels = new HashMap<>();

    @Parameter(
        names = "--accept_cached",
        arity=1,
        description = "Whether to accept remotely cached action results.")
    public boolean acceptCached = true;

    @Parameter(
        names = "--do_not_cache",
        arity=1,
        description = "When set, this action results will not be cached remotely.")
    public boolean doNotCache = false;

    @Parameter(
        names = "--inputs",
        variableArity = true,
        description = "Input paths (files or directories) relative to exec root to include for "+
            "command execution.")
    public List<String> inputs = new ArrayList<>();

    @Parameter(
        names = "--output_files",
        variableArity = true,
        description = "Output files relative to exec root to download after command executes.")
    public List<String> outputFiles = new ArrayList<>();

    @Parameter(
        names = "--output_directories",
        variableArity = true,
        description = "Output directories relative to exec root to download after execution.")
    public List<String> outputDirectories = new ArrayList<>();

    @Parameter(
        names = "--command",
        variableArity = true,
        splitter = NoSplittingSplitter.class,
        description = "Command line elements to execute.")
    public List<String> command = new ArrayList<>();

    @Parameter(
        names = "--ignore_inputs",
        variableArity = true,
        description = "Inputs to ignore, as regular expressions.")
    public List<String> ignoreInputs = new ArrayList<>();

    @Parameter(
        names = "--environment_variables",
        converter = MapConverter.class,
        description = "Environment variables to pass through to remote execution, formatted as a " +
            "comma-separated list of <variable_name>=value pairs.")
    public Map<String,String> environmentVariables = new HashMap<>();

    @Parameter(
        names = "--platform",
        converter = MapConverter.class,
        description = "The platform to use for the remote execution, formatted as a " +
            "comma-separated list of <property_name>=value pairs.")
    public Map<String,String> platform = new HashMap<>();

    @Parameter(
        names = "--working_directory",
        description = "The working directory, relative to the exec root, for the command to run " +
            "in. It must be a directory which exists in the input tree. If it is left " +
            "empty, then the action is run in the exec root.")
    public String workingDirectory = "";

    @Parameter(
        names = "--server_logs_path",
        description = "Optional path to save server logs for failed actions.")
    public String serverLogsPath = "";

    @Parameter(
        names = "--execution_timeout",
        description = "The maximum number of seconds to wait for remote action execution.")
    public int executionTimeout = 0;

    @Parameter(
        names = "--save_execution_data",
        arity=1,
        description = "When set, saves full execution data for the executed action.")
    public boolean saveExecutionData = false;

    @Parameter(
        names = "--local_fallback",
        arity=1,
        converter = LocalFallbackConverter.class,
        description = "How/whether to fall back locally if the action fails remotely.")
    public LocalFallback localFallback = LocalFallback.NONE;
  }

  @Parameters(
      commandDescription = "Queries the current stats of a remote client proxy.",
      separators = "=")
  public static class ProxyStatsCommand {
    @Parameter(
        names = "--full",
        arity=1,
        description = "Whether to get the full list of records.")
    public boolean full = false;

    @Parameter(
        names = "--invocation_id",
        description = "A particular invocation id to get the stats for.")
    public String invocationId = "";

    @Parameter(
        names = {"--command_id", "-c"},
        description = "A particular command to get the stats for.")
    public String commandId = "";

    @Parameter(
        names = "--from_ts",
        description = "A timestamp (seconds from epoch) to collect stats from.")
    public long fromTs = 0;

    @Parameter(
        names = "--to_ts",
        description = "A timestamp (seconds from epoch) to collect stats to.")
    public long toTs = 0;

    @Parameter(
        names = "--status",
        description = "An action status to filter by.",
        converter = StatusConverter.class)
    public Status status = Status.UNKNOWN;

    @Parameter(
        names = "--labels",
        converter = MapConverter.class,
        description = "Optional command labels for slicing the stats, formatted as a " +
            "comma-separated list of <label_name>=value pairs.")
    public Map<String,String> labels = new HashMap<>();

    @Parameter(
        names = {"--proxy_stats_file", "-i"},
        converter = PathConverter.class,
        description = "If specified, the stats will be parsed from a text file containing " +
            "the output of a previous full --proxy_stats command.")
    public File proxyStatsFile = null;
  }

  @Parameters(
      commandDescription = "Prints an executed command from a remote client proxy or input file.",
      separators = "=")
  public static class ProxyPrintRemoteCommand {
    @Parameter(
        names = {"--command_id", "-c"},
        description = "The command to print.")
    public String commandId = "";

    @Parameter(
        names = "--invocation_id",
        description = "A particular invocation id to get the command for.")
    public String invocationId = "";

    @Parameter(
        names = {"--proxy_stats_file", "-i"},
        converter = PathConverter.class,
        description = "If specified, the command will be parsed from a text file containing " +
            "the output of a full --proxy_stats command.")
    public File proxyStatsFile = null;
  }

  public static final class StatusConverter extends EnumConverter<Status> {
    StatusConverter(String optionName, Class<Status> clazz) {
      super(optionName, clazz);
    }
  }

  public static final class LocalFallbackConverter extends EnumConverter<LocalFallback> {
    LocalFallbackConverter(String optionName, Class<LocalFallback> clazz) {
      super(optionName, clazz);
    }
  }

  public static class NoSplittingSplitter implements IParameterSplitter {
    @Override
    public List<String> split(String value) {
      return ImmutableList.of(value);
    }
  }

  /** Converter for hex_hash/size_bytes string to a Digest object. */
  public static class DigestConverter implements IStringConverter<Digest> {
    @Override
    public Digest convert(String input) {
      int slash = input.indexOf('/');
      if (slash < 0) {
        throw new ParameterException("'" + input + "' is not as hex_hash/size_bytes");
      }
      try {
        long size = Long.parseLong(input.substring(slash + 1));
        return DigestUtil.buildDigest(input.substring(0, slash), size);
      } catch (NumberFormatException e) {
        throw new ParameterException("'" + input + "' is not a hex_hash/size_bytes: " + e);
      }
    }
  }

  /** Converter for string to a Map of strings. */
  public static class MapConverter implements IStringConverter<Map<String,String>> {
    @Override
    public Map<String,String> convert(String value) {
      Map<String,String> result = new HashMap<>();
      for (String pair : value.split(",")) {
        int eq = pair.indexOf("=");
        if (eq < 0) {
          throw new ParameterException("'" + value + "' is not a list of name=value pairs");
        }
        result.put(pair.substring(0, eq),  pair.substring(eq+1));
      }
      return result;
    }
  }
}
