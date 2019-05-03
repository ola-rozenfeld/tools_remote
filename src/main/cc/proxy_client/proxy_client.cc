#include "src/main/cc/proxy_client/proxy_client.h"
#include "src/main/cc/proxy_client/common_utils.h"

#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <streambuf>
#include <stdlib.h>

#include <grpc/grpc.h>
#include <grpcpp/channel.h>
#include <grpcpp/client_context.h>
#include <grpcpp/create_channel.h>
#include <grpcpp/security/credentials.h>

#include <chrono>
#include <iostream>
#include <set>
#include <string>
#include <thread>

#include "absl/strings/numbers.h"
#include "absl/strings/str_cat.h"
#include "absl/strings/str_join.h"
#include "absl/strings/str_split.h"
#include "gflags/gflags.h"
#include "src/main/cc/ipc/goma_ipc.h"
#include "src/main/cc/proxy_client/javac_remote_actions.h"
#include "src/main/cc/proxy_client/link_command_inputs.h"
#include "src/main/proto/commands.grpc.pb.h"
#include "src/main/proto/commands.pb.h"
#include "src/main/proto/include_processor.pb.h"
#include "src/main/proto/include_processor.grpc.pb.h"

#define INCLUDE_PROCESSOR_PROXY_FAILURE 44

DEFINE_bool(local_fallback, true, "Fallback to local execution if remote "
            "execution fails");
DEFINE_bool(verbose, false, "Verbose mode");
DEFINE_bool(force_remote, false, "Force the command to be run remotely");
DEFINE_bool(accept_cached, true, "Indicate whether to accept action cache results");
DEFINE_bool(save_exec_data, false, "Indicate whether to save full execution data");
DEFINE_bool(allow_out_under_in, false, "Indicate whether to allow outputs under"
            " input directories");
DEFINE_bool(allow_out_dirs, false, "Indicate whether to allow output directories"
            " as inputs");
DEFINE_string(out_dir, "out", "Name of the output directory");
DEFINE_string(invocation_id, "", "Invocation ID of the build");
DEFINE_int32(retry_count, 1, "Number of remote retries to attempt");
DEFINE_int32(proxy_instances, 1, "Number of remote client proxy instances");
DEFINE_string(proxy_address, "localhost:8080", "Address of the remote client proxy");
DEFINE_int32(include_processor_server_instances, 1, "Number of include processor server instances");
DEFINE_string(include_processor_server_address, "localhost:8070", "Address of the include processor server");
DEFINE_string(command, "", "Type of command: run, list_includes, include_stats");
DEFINE_string(inputs, "", "Comma-seperated list of input files/directories");
DEFINE_string(outputs, "", "Comma-seperated list of output files");
DEFINE_string(env_whitelist, "", "Comma-seperated list of environment variables to "
              "pass-through to the remote environment");
DEFINE_string(labels, "", "Comma-seperated list of name=value pairs of command labels");

static bool IsValidCommand(const char *flagname, const std::string &value) {
  return value == "run" || value == "list_includes" || value == "include_stats";
}
DEFINE_validator(command, &IsValidCommand);

namespace remote_client {

using devtools_goma::GomaIPC;
using grpc::Channel;
using grpc::ClientContext;
using grpc::ClientReader;
using grpc::Status;
using include_processor::ProcessIncludesRequest;
using include_processor::ProcessIncludesResponse;
using include_processor::ProcessIncludesService;
using std::cerr;
using std::chrono::milliseconds;
using std::chrono::duration_cast;
using std::cout;
using std::getenv;
using std::set;
using std::string;
using std::vector;
using std::ifstream;
using std::istreambuf_iterator;
using std::max;

const char* kFileArgPrefix = "file:";
const string kPWDOverride = "/proc/self/cwd";

string GetCwd() {
  char temp[PATH_MAX];
  return string(getcwd(temp, sizeof(temp)) ? temp : "");
}

string NormalizedRelativePath(const string& cwd, const string& path) {
  // We don't use path functions to find a true relative path, because nothing
  // is allowed to escape the current working directory.
  string rel_path = absl::StartsWith(path, cwd)
      ? path.substr(cwd.length() + 1, path.length())
      : path;
  vector<string> segments = absl::StrSplit(rel_path, '/', absl::SkipEmpty());
  auto iter = segments.begin();
  while (iter != segments.end()) {
    if (*iter == ".") {
      iter = segments.erase(iter);
      continue;
    }

    auto prev_iter = iter - 1;
    if (*iter == ".." && prev_iter->find("$") == string::npos) {
      // If the previous segment has any one of the following characters,
      // don't erase the whole previous segment but instead erase only the
      // relevant portions of the previous segment.
      const int single_quote_pos = prev_iter->find_last_of("'");
      const int space_pos = prev_iter->find_last_of(" ");
      const int double_quote_pos = prev_iter->find_last_of("\"");

      const int last_valid_char_pos = max(space_pos, max(double_quote_pos, single_quote_pos));
      if (last_valid_char_pos >= 0) {
        prev_iter->erase(last_valid_char_pos + 1);
        iter = segments.erase(iter, iter + 1);
      } else {
        iter = segments.erase(iter - 1, iter + 1);
      }
      continue;
    }
    ++iter;
  }

  string res;
  res.reserve(path.length());

  for (auto iter = segments.begin(); iter != segments.end(); ++iter) {
    res += *iter;
    if (iter->length() == 0) {
      continue;
    }

    const char last_char = iter->at(iter->length() - 1);
    if (last_char != ' ' &&
        last_char != '\'' &&
        last_char != '"' &&
        iter != segments.end() - 1) {
      res += '/';
    }
  }
  return res;
}

string RelativeToAbsolutePath(const string& cwd, const char* path) {
  return path[0] == '/' ? string(path) : absl::StrCat(cwd, "/", path);
}

string GetCompilerDir(const char* compiler) {
  string compiler_dir = string(compiler);
  auto pos = compiler_dir.rfind('/');
  if (pos != string::npos) {
    pos = compiler_dir.rfind('/', pos - 1);
    if (pos != string::npos) {
      compiler_dir = compiler_dir.substr(0, pos);
    }
  }
  return compiler_dir;
}

string Trim(const string& cmd) {
  if (cmd.length() == 0) {
    return "";
  }

  size_t st_idx = 0;
  size_t ed_idx = cmd.length() - 1;

  while (st_idx <= ed_idx && !absl::ascii_isalpha(cmd[st_idx])) ++st_idx;
  while (ed_idx > st_idx && !absl::ascii_isalpha(cmd[ed_idx])) --ed_idx;

  return cmd.substr(st_idx, ed_idx - st_idx + 1);
}

bool IsFile(const string& name) {
  struct stat buffer;   
  return stat(name.c_str(), &buffer) == 0;
}

void FindFiles(const string& cmd, set<string>* files) {
  if (cmd.find(" ") != string::npos) {
    for (const auto& c : absl::StrSplit(cmd, ' ', absl::SkipEmpty())) {
      FindFiles(string(c), files);
    }
    return;
  }
  if (cmd.find(":") != string::npos) {
    for (const auto& c : absl::StrSplit(cmd, ':', absl::SkipEmpty())) {
      FindFiles(string(c), files);
    }
    return;
  }
  // Certain link commands have a "," in their options that refers to a file,
  // so split the names by "," as well and check if each segment is a file.
  // Ex: -Wl,--version-script,frameworks/rs/libRS.map
  if (cmd.find(",") != string::npos) {
    for (const auto& c : absl::StrSplit(cmd, ',', absl::SkipEmpty())) {
      FindFiles(string(c), files);
    }
    return;
  }

  const string trimmed_cmd = Trim(cmd);
  if (IsFile(trimmed_cmd)) {
    files->insert(trimmed_cmd);
  }
}

// FindAllFilesFromCommand splits each of the given args by space and colon,
// checks if each individual piece of command is a file and if it is, appends
// the file to the given set.
// This is useful to find "hidden inputs" i.e., inputs not explicitly specified
// to rbecc (from Ninja), but those that the command depends on for it to be
// successfully remotely executed.
void FindAllFilesFromCommand(int argc, char** argv, set<string>* files) {
  if (files == nullptr) {
    return;
  }

  for (int i = 0; i < argc; ++i) {
    FindFiles(string(argv[i]), files);
  }
}

int IncludeProcessorStats() {
  auto channel =
      grpc::CreateChannel(FLAGS_include_processor_server_address, grpc::InsecureChannelCredentials());
  std::unique_ptr<ProcessIncludesService::Stub> stub(ProcessIncludesService::NewStub(channel));
  ClientContext context;  // No deadline.
  include_processor::StatsRequest req;
  include_processor::StatsResponse resp;
  Status status = stub->Stats(&context, req, &resp);
  if (!status.ok()) {
    cout << "Stats rpc failed:" << status.error_message() << "\n";
    return 1;
  }
  cout << resp.DebugString();
  return 0;
}

int GetInputsFromIncludeProcessor(const string& cmd_id, int argc, char** argv, const char** env,
                                  const string& cwd, set<string>* inputs) {
  string server_address = FLAGS_include_processor_server_address;
  if (FLAGS_include_processor_server_instances > 1) {
    int port = 8070;
    vector<string> parts = absl::StrSplit(FLAGS_include_processor_server_address, ':');
    if (!absl::SimpleAtoi(parts[1], &port)) {
      cerr << "If INCLUDE_SERVER_INSTANCES>1, INCLUDE_SERVER_ADDRESS should be host:port.";
      return 35;
    }
    port += rand() % FLAGS_include_processor_server_instances;
    server_address = absl::StrCat(parts[0], ":", port).c_str();
  }
  ProcessIncludesRequest req;
  req.set_command_id(cmd_id);
  req.set_cwd(GetCwd());
  for (int i = 0; i < argc; ++i) {
    req.add_args(argv[i]);
  }
  while (*env) {
    req.add_envs(*env++);
  }
  if (FLAGS_verbose) {
    cout << "Calling remote server on " << server_address << "\n" << req.DebugString();
  }
  auto channel =
      grpc::CreateChannel(server_address, grpc::InsecureChannelCredentials());
  std::unique_ptr<ProcessIncludesService::Stub> stub(ProcessIncludesService::NewStub(channel));
  ClientContext context;  // No deadline.
  ProcessIncludesResponse resp;
  Status status = stub->ProcessIncludes(&context, req, &resp);
  if (!status.ok()) {
    cout << "ProcessIncludes rpc failed:" << status.error_message() << "\n";
    return 1;
  }
  if (FLAGS_verbose) {
    cout << "Server returned response:\n" << resp.DebugString() << "\n";
  }
  if (resp.time_msec() > 500) {
    cerr << cmd_id << "> Slow includes time: " << resp.time_msec() << " ms\n";
  }
  for (const auto& input : resp.includes()) {
    inputs->insert(NormalizedRelativePath(cwd, input));
  }
  return 0;
}


string ReadFile(const std::string& fileName) {
  ifstream fileStream(fileName.c_str());
  return string((istreambuf_iterator<char>(fileStream)),
            istreambuf_iterator<char>());
}

bool IsFileArg(const std::string& str) {
  if (str.substr(0, 5) == kFileArgPrefix) {
    return true;
  }
  return false;
}

// ExpandFileArguments goes through the set of args given as input, and for any arg
// that contains a "file:<absolute-file-path>" syntax, reads the file pointed to by the
// absolute-file-path and insert the arguments specified by it into the given set of args.
//
// The file contents is expected to be a comma separated list of arguments. For example,
// the following is a valid file:
// foo/bar/a.java,bar/foo/b.h,bar/foo/b.cpp
void ExpandFileArguments(set<string>* args) {
  set<string> argsFromFileContents;

  for (const auto& arg : *args) {
    if (IsFileArg(arg)) {
      const std::string& fileName = arg.substr(5);
      const std::string& fileContents = ReadFile(fileName);
      for (const auto& argFromFile : absl::StrSplit(fileContents, ',', absl::SkipEmpty())) {
        argsFromFileContents.insert(string(argFromFile));
      }
    }
  }

  args->insert(argsFromFileContents.begin(), argsFromFileContents.end());
}

int ComputeInputs(int argc, char** argv, const char** env, const string& cwd, const string& cmd_id,
                  bool *is_compile, bool* is_javac, set<string>* inputs) {
  set<string> inputs_from_args;
  if (FLAGS_inputs == "") {
    cerr << "Missing inputs\n";
    return 1;
  }
  bool is_assembler = false;
  for (const auto& input : absl::StrSplit(FLAGS_inputs, ',', absl::SkipEmpty())) {
    inputs_from_args.insert(string(input));
    if (absl::EndsWith(input, ".S") ||  absl::EndsWith(input, ".s")) {
      is_assembler = true;
    }
  }
  // Expand file:<filename> args into the contents of the file itself.
  // This is done so that large inputs exceeding 120KB in size can be passed in as files
  // rather than as direct inputs to rbecc invocation.
  ExpandFileArguments(&inputs_from_args);

  bool next_is_input = false;
  bool is_link = false;
  *is_compile = false;
  set<string> cc_input_args({"-I", "-c", "-isystem", "-quote"});
  vector<string> input_prefixes({"-L", "--gcc_toolchain"});
  for (int i = 0; i < argc; ++i) {
    if (next_is_input) {
      inputs_from_args.insert(argv[i]);
    }
    next_is_input = (cc_input_args.find(argv[i]) != cc_input_args.end());
    if (!strcmp(argv[i], "-o") && absl::EndsWith(argv[i+1], ".o")) {
      *is_compile = true;
    }
    for (const string& prefix : input_prefixes) {
      if (absl::StartsWith(argv[i], prefix)) {
        inputs_from_args.insert(argv[i] + prefix.length());
        is_link = is_link || (prefix == "-L");
      }
    }
    if (!strcmp(argv[i], "-D__ASSEMBLY__")) {
        is_assembler = true;
    }
  }

  bool use_args_inputs = false;
  *is_javac = IsJavacAction(argc, argv);
  bool is_header_abi_dumper = string(argv[1]).find("header-abi-dumper") != std::string::npos;
  if (*is_compile) {
    int proc_res = GetInputsFromIncludeProcessor(cmd_id, argc, argv, env, cwd, inputs);
    if (proc_res != 0) {
      return proc_res;
    }
    if (inputs->empty()) {
      use_args_inputs = true;
      // We successfully called the include processor, but it returned no values.
      // Fall back on computing from the command, but warn.
      cerr << cmd_id << "> Include processor did not return results, computing from args\n";
    }
  } else if (is_header_abi_dumper) {
    use_args_inputs = true;
    *is_compile = true;
    vector<const char*> new_argv;
    new_argv.reserve(argc);
    bool is_c = false;
    for (const string& inp: inputs_from_args) {
      if (inp.compare(inp.length() - 2, 2, string(".c")) == 0) {
        is_c = true;
      }
    }
    for(int i = 0; i < argc; ++i) {
        if (i==1) {
          // Only Android @ head has header-abi-dumper, which uses
          // clang-r349610.
          if (is_c)
            new_argv.emplace_back("prebuilts/clang/host/linux-x86/clang-r349610/bin/clang");
          else
            new_argv.emplace_back("prebuilts/clang/host/linux-x86/clang-r349610/bin/clang++");
        } else {
          new_argv.emplace_back(argv[i]);
        }
    }
    new_argv.emplace_back(nullptr);
    int proc_res = GetInputsFromIncludeProcessor(cmd_id, argc, const_cast<char**>(&new_argv[0]), env, cwd, inputs);
    if (proc_res != 0) {
      return proc_res;
    }
    if (inputs->empty()) {
      // We successfully called the include processor, but it returned no values.
      // Fall back on computing from the command, but warn.
      cerr << cmd_id << "> Include processor did not return results, computing from args\n";
    }
  } else if (*is_javac) {
    use_args_inputs = true;
    inputs->insert("prebuilts/jdk/jdk9/linux-x86");
    inputs->insert("external/icu");
    FindAllFilesFromCommand(argc, argv, inputs);
  } else if (is_link) {
    use_args_inputs = true;
    inputs->insert("prebuilts/gcc");
    FindAllFilesFromCommand(argc, argv, inputs);
    FindLibraryInputs(argc, argv, inputs);
  } else if (FLAGS_force_remote) {
    use_args_inputs = true;
    FindAllFilesFromCommand(argc, argv, inputs);
  }
  if (use_args_inputs) {
    inputs->insert(inputs_from_args.begin(), inputs_from_args.end());
  }
  inputs->insert(GetCompilerDir(argv[0]));  // For both compile and link commands?
  if (is_assembler) {
    // Horrible hack for Android 7 assembly actions.
    inputs->insert("prebuilts/gcc/linux-x86/arm/arm-linux-androideabi-4.9/arm-linux-androideabi/bin/as");
  }
  if (is_compile) {
    inputs->insert(argv[argc-1]);  // For Android compile commands, the compiled file is last.
  } // Linker commands need special treatment as well.

  return 0;
}

int CreateRunRequest(int argc, char** argv, const char** env,
                     const string& cmd_id, RunRequest* req,
                     bool* is_compile, bool* is_javac) {
  req->Clear();
  Command* command = req->mutable_command();
  Labels* labels = command->mutable_labels();
  labels->set_command_id(cmd_id);
  labels->set_invocation_id(FLAGS_invocation_id);
  ExecutionOptions* exec_options = command->mutable_execution_options();
  exec_options->set_accept_cached(FLAGS_accept_cached);
  exec_options->set_save_execution_data(FLAGS_save_exec_data);
  string cwd = GetCwd();
  set<string> outputs;
  if (FLAGS_outputs == "") {
    cerr << "Missing outputs\n";
    return 1;
  }
  for (const auto& output : absl::StrSplit(FLAGS_outputs, ',', absl::SkipEmpty())) {
    outputs.insert(string(output));
    if (absl::EndsWith(output, ".o")) {
      outputs.insert(absl::StrCat(output, ".d"));
      outputs.insert(absl::StrCat(output.substr(0, output.length() - 2), ".d"));
    }
  }
  for (const auto& output : outputs) {
    command->add_output_files(NormalizedRelativePath(cwd, output));
  }
  set<string> inputs;
  int compute_input_res = ComputeInputs(argc, argv, env, cwd, cmd_id, is_compile, is_javac, &inputs);
  if (compute_input_res != 0) {
    cerr << cmd_id << "> Failed to compute inputs\n";
    return compute_input_res;
  }
  bool allow_outputs_under_inputs = *is_javac || FLAGS_allow_out_under_in;
  bool allow_output_directories_as_inputs = *is_javac || FLAGS_allow_out_dirs;
  for (const auto& input : inputs) {
    string inp = NormalizedRelativePath(cwd, input);
    bool is_directory = false;
    if (inp.empty() || inp == "." || !PathExists(inp, &is_directory)) {
      continue;
    }
    if (!allow_output_directories_as_inputs && is_directory && absl::StartsWith(inp, FLAGS_out_dir + "/")) {
      continue;
    }

    if (!allow_outputs_under_inputs) {
      bool found = false;
      for (const auto& output : outputs) {
        if (absl::StartsWith(output, inp)) {
          found = true;
          break;
        }
      }
      if (found) {
        continue;
      }
    }
    command->add_inputs(inp);
  }
  for (int i = 0; i < argc; ++i) {
    command->add_args(NormalizedRelativePath(cwd, string(argv[i])));
  }
  command->add_ignore_inputs("\\.d$");
  command->add_ignore_inputs("\\.P$");
  command->add_ignore_inputs("\\.o-.*$");
  command->add_ignore_inputs("\\.git.*$");
  auto& environment_variables = *(exec_options->mutable_environment_variables());
  environment_variables["PWD"] = kPWDOverride;
  set<string> whitelist = absl::StrSplit(FLAGS_env_whitelist, ',', absl::SkipEmpty());
  while (*env) {
    string varval(*env++);
    unsigned int eq_index = varval.find("=");
    string var = varval.substr(0, eq_index);
    if (whitelist.find(var) != whitelist.end()) {
      environment_variables[var] = varval.substr(eq_index+1);
    }
  }
  auto& platform = *(exec_options->mutable_platform());
  platform["container-image"] = "docker://gcr.io/foundry-x-experiments/android-platform@sha256:"
      "56e8072003914010c86702ef94634cdfde7089e4732ceac241d0fe4242957f90";
  platform["jdk-version"] = "10";
  set<string> label_vals = absl::StrSplit(FLAGS_labels, ',', absl::SkipEmpty());
  auto& cmd_labels = *(labels->mutable_labels());
  for (const string& nameval : label_vals) {
    unsigned int eq_index = nameval.find("=");
    string name = nameval.substr(0, eq_index);
    cmd_labels[name] = nameval.substr(eq_index+1);
  }
  return 0;
}

int ExecuteRemotely(const RunRequest& req) {
  string proxy_address = FLAGS_proxy_address;
  if (FLAGS_proxy_instances > 1) {
    int port = 8080;
    vector<string> parts = absl::StrSplit(FLAGS_proxy_address, ':');
    if (!absl::SimpleAtoi(parts[1], &port)) {
      cerr << "If proxy_instances>1, proxy_address should be host:port.";
      return 35;
    }
    port += rand() % FLAGS_proxy_instances;
    proxy_address = absl::StrCat(parts[0], ":", port);
  }
  if (FLAGS_verbose) {
    cout << "Calling remote proxy on " << proxy_address << "\n" << req.DebugString();
  }
  auto channel =
      grpc::CreateChannel(proxy_address, grpc::InsecureChannelCredentials());
  std::unique_ptr<Commands::Stub> stub(Commands::NewStub(channel));
  RunResponse resp;
  ClientContext context;  // No deadline.
  std::unique_ptr<ClientReader<RunResponse> > reader(stub->RunCommand(&context, req));
  while (reader->Read(&resp)) {
    if (!resp.stdout().empty()) {
      cout << resp.stdout();
    }
    if (!resp.stderr().empty()) {
      cerr << resp.stderr();
    }
    if (resp.has_result()) {
      CommandResult result = resp.result();
      if (!reader->Finish().ok()) {
        cerr << "Error finishing read from remote client proxy.\n";
        return 33;
      }
      return result.exit_code();
    }
  }
  cerr << "Remote client proxy failed to return a run result.\n";
  return 33;
}

int ExecuteCommand(int argc, char** argv, const char** env) {
  std::chrono::time_point<std::chrono::high_resolution_clock> start_time,
      proxy_start_time, end_time;
  if (FLAGS_verbose) {  // Enable profilig with VERBOSE.
    start_time = std::chrono::high_resolution_clock::now();
  }
  setenv("PWD", kPWDOverride.c_str(), true);  // Will apply to both local and remote commands.
  // Build local command arguments.
  vector<const char*> args;
  args.reserve(argc);
  for (int i = 0; i < argc; ++i) {
    args.emplace_back(argv[i]);
  }
  args.emplace_back(nullptr);
  string local_cmd = absl::StrJoin(args.begin(), args.end() - 1, " ");
  string cmd_id = absl::StrCat(std::hash<std::string>{}(local_cmd));

  RunRequest req;
  bool is_compile, is_javac;
  int create_run_res = CreateRunRequest(argc, argv, env, cmd_id, &req, &is_compile, &is_javac);
  if (create_run_res != 0) {
    return create_run_res;  // Failed to create request.
  }
  if (!is_compile && !is_javac && !FLAGS_force_remote) {
    // Only run compile actions remotely for now.
    if (FLAGS_verbose) {
      cout << "Executing non-compile action locally: " << local_cmd << "\n";
    }
    return execvp(args[0], const_cast<char**>(args.data()));
  }
  if (FLAGS_verbose) {
    proxy_start_time = std::chrono::high_resolution_clock::now();
  }
  int exit_code = 1;
  int sleep_ms = 500;
  for (int i = 0; i < FLAGS_retry_count; ++i) {
    exit_code = ExecuteRemotely(req);
    if (!exit_code) {
      break;
    }
    cerr << "FAILED " << cmd_id << " (exit_code = " << exit_code << ", attempt " << i + 1 << ")\n";
    if (i < FLAGS_retry_count - 1) {
      std::this_thread::sleep_for(std::chrono::milliseconds(sleep_ms));
      sleep_ms *= 2;
    }
  }
  if (FLAGS_verbose) {
    end_time = std::chrono::high_resolution_clock::now();
    milliseconds remote_ms = duration_cast<milliseconds>(end_time - proxy_start_time);
    milliseconds overhead_ms = duration_cast<milliseconds>(proxy_start_time - start_time);
    cerr << "Command " << cmd_id << " remote time: " << remote_ms.count()
         << " msec, overhead time " << overhead_ms.count() << " msec\n";
  }
  if (exit_code) {
    if (FLAGS_local_fallback) {
      cout << "Falling back to local execution " << cmd_id << "\n";
      return execvp(args[0], const_cast<char**>(args.data()));
    }
  }
  return exit_code;
}

int SelectAndRunCommand(int argc, char** argv, const char** env) {
  srand(time(nullptr));
  gflags::SetUsageMessage("RBE client for remote proxy");
  int sep_idx = argc;
  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "--")) {
      sep_idx = i;
      break;
    }
  }
  gflags::ParseCommandLineFlags(&sep_idx, &argv, false);
  if (FLAGS_command == "run") {
      if (sep_idx == argc) {
        cout << "You set the command to 'run'.\n";
         cout << "Usage: " << gflags::ProgramInvocationShortName() <<
              " [--arg=val] --command=run -- [full command]\n";
         return 1;
      }
      return ExecuteCommand(argc-sep_idx-1, &argv[sep_idx+1], env);
  }
  if (FLAGS_command == "include_stats") {
    return IncludeProcessorStats();
  }
  if (FLAGS_command == "list_includes") {
    std::chrono::time_point<std::chrono::high_resolution_clock> start_time, end_time;
    start_time = std::chrono::high_resolution_clock::now();
    set<string> includes;
    bool is_compile, is_javac;
    int result = ComputeInputs(argc, argv, env, GetCwd(), "cmd", &is_compile, &is_javac, &includes);
    cout << "Computed inputs:\n";
    for (const string& i : includes) {
      cout << i << "\n";
    }
    end_time = std::chrono::high_resolution_clock::now();
    std::chrono::duration<double> time = end_time - start_time;
    cerr << "Time: " << time.count() * 1000 << " msec\n";
    return result;
  }

  return 35;
}

}  // namespace remote_client
