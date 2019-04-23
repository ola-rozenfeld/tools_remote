#include "src/main/cc/proxy_client/proxy_client.h"

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
#include "src/main/cc/ipc/goma_ipc.h"
#include "src/main/cc/proxy_client/javac_remote_actions.h"
#include "src/main/proto/command_server.grpc.pb.h"
#include "src/main/proto/command_server.pb.h"
#include "src/main/proto/include_processor.pb.h"
#include "src/main/proto/include_processor.grpc.pb.h"

#define INCLUDE_PROCESSOR_PROXY_FAILURE 44

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

bool PathExists(const string& s, bool *is_directory) {
  struct stat st;
  if (stat(s.c_str(), &st) == 0) {
    *is_directory = (st.st_mode & S_IFDIR) != 0;
    return true;
  }
  return false;
}

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
    if (*iter == "..") {
      // If the previous segment has any one of the following characters,
      // don't erase the whole previous segment but instead erase only the
      // relevant portions of the previous segment.
      auto prev_iter = iter - 1;
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

int IncludeProcessorStats() {
  const char* server_address = getenv("INCLUDE_SERVER_ADDRESS");
  if (!server_address) {
    server_address = "localhost:8070";
  }
  auto channel =
      grpc::CreateChannel(server_address, grpc::InsecureChannelCredentials());
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
  const char* server_address = getenv("INCLUDE_SERVER_ADDRESS");
  if (!server_address) {
    server_address = "localhost:8070";
  }
  const char* server_instances_var = getenv("INCLUDE_SERVER_INSTANCES");
  int server_instances = 1;
  if (server_instances_var && !absl::SimpleAtoi(server_instances_var, &server_instances)) {
    cerr << "INCLUDE_SERVER_INSTANCES should be an integer.";
    return 35;
  }
  if (server_instances > 1) {
    int port = 8070;
    vector<string> parts = absl::StrSplit(server_address, ':');
    if (!absl::SimpleAtoi(parts[1], &port)) {
      cerr << "If INCLUDE_SERVER_INSTANCES>1, INCLUDE_SERVER_ADDRESS should be host:port.";
      return 35;
    }
    port += rand() % server_instances;
    server_address = absl::StrCat(parts[0], ":", port).c_str();
  }
  ProcessIncludesRequest req;
  req.set_command_id(cmd_id);
  req.set_cwd(GetCwd());
  for (int i = 4; i < argc; ++i) {
    req.add_args(argv[i]);
  }
  while (*env) {
    req.add_envs(*env++);
  }
  bool verbose = getenv("VERBOSE") != nullptr;
  if (verbose) {
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
  if (verbose) {
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
  if (!absl::StartsWith(argv[2], "-inputs:")) {
    cerr << "Missing -inputs\n";
    return 1;
  }
  const char* input_arg = argv[2] + 8;
  bool is_assembler = false;
  for (const auto& input : absl::StrSplit(input_arg, ',', absl::SkipEmpty())) {
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
  *is_compile = false;
  set<string> cc_input_args({"-I", "-c", "-isystem", "-quote"});
  vector<string> input_prefixes({"-L", "--gcc_toolchain"});
  for (int i = 5; i < argc; ++i) {
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
      }
    }
    if (!strcmp(argv[i], "-D__ASSEMBLY__")) {
        is_assembler = true;
    }
  }

  bool use_args_inputs = false;
  *is_javac = IsJavacAction(argc, argv);
  bool is_header_abi_dumper = string(argv[4]).find("header-abi-dumper") != std::string::npos;
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
        if (i==4) {
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
  }

  if (use_args_inputs) {
    inputs->insert(inputs_from_args.begin(), inputs_from_args.end());
  }
  // Common inputs:
  inputs->insert("build");  // Needed for Android 9?
  inputs->insert("toolchain");
  inputs->insert(GetCompilerDir(argv[4]));  // For both compile and link commands?
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
  req->add_command("run_remote");
  req->add_command("--name");
  req->add_command(cmd_id);
  char* invocation_id = getenv("INVOCATION_ID");
  if (invocation_id != nullptr) {
    req->add_command("--invocation_id");
    req->add_command(invocation_id);
  }
  char* accept_cached = getenv("ACCEPT_CACHED");
  if (accept_cached != nullptr) {
    req->add_command("--accept_cached");
    req->add_command(accept_cached);
  }
  char* save_exec_data = getenv("SAVE_EXECUTION_DATA");
  if (save_exec_data != nullptr) {
    req->add_command("--save_execution_data");
    req->add_command(save_exec_data);
  }
  string cwd = GetCwd();
  set<string> outputs;
  const char* outputs_arg = argv[3];
  if (!absl::StartsWith(outputs_arg, "-outputs:")) {
    cerr << "Missing -outputs\n";
    return 1;
  }
  outputs_arg += 9;
  for (const auto& output : absl::StrSplit(outputs_arg, ',', absl::SkipEmpty())) {
    outputs.insert(string(output));
    if (absl::EndsWith(output, ".o")) {
      outputs.insert(absl::StrCat(output, ".d"));
      outputs.insert(absl::StrCat(output.substr(0, output.length() - 2), ".d"));
    }
  }
  req->add_command("--output_files");  // We don't know whether these are files or directories.
  for (const auto& output : outputs) {
    req->add_command(NormalizedRelativePath(cwd, output));
  }
  set<string> inputs;
  int compute_input_res = ComputeInputs(argc, argv, env, cwd, cmd_id, is_compile, is_javac, &inputs);
  if (compute_input_res != 0) {
    cerr << cmd_id << "> Failed to compute inputs\n";
    return compute_input_res;
  }
  if (!inputs.empty()) {
    req->add_command("--inputs");
  }
  bool allow_outputs_under_inputs = *is_javac || getenv("ALLOW_OUTPUTS_UNDER_INPUTS") != nullptr;
  bool allow_output_directories_as_inputs = *is_javac || getenv("ALLOW_OUTPUT_DIRECTORIES_AS_INPUTS") != nullptr;
  for (const auto& input : inputs) {
    string inp = NormalizedRelativePath(cwd, input);
    bool is_directory = false;
    if (inp.empty() || inp == "." || !PathExists(inp, &is_directory)) {
      continue;
    }
    if (!allow_output_directories_as_inputs && is_directory && absl::StartsWith(inp, "out/")) {
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
    req->add_command(inp);
  }
  req->add_command("--command");
  for (int i = 4; i < argc; ++i) {
    req->add_command(NormalizedRelativePath(cwd, string(argv[i])));
  }
  req->add_command("--ignore_inputs");
  req->add_command("\\.d$");
  req->add_command("\\.P$");
  req->add_command("\\.o-.*$");
  req->add_command("\\.git.*$");
  req->add_command("--environment_variables");
  string env_vars;
  while (*env) {
    string varval(*env++);
    unsigned int eq_index = varval.find("=");
    string var = varval.substr(0, eq_index);
    bool passPath = false;
    if (getenv("ADDITONAL_ENV_VARS")) {
      // TODO: support more vars if needed.
      passPath = true;
    }
    // Do not pass empty environment variables for consistency with Python version.
    if (var.find("PYTHON") == string::npos && eq_index != varval.length() - 1 &&
        (var.find("ANDROID") != string::npos ||
         var.find("TARGET") != string::npos || var == "PWD" ||
         (var.find("PATH") != string::npos && passPath) || var.find("OUT") != string::npos))
      absl::StrAppend(&env_vars, varval, ",");
  }
  if (env_vars.length() > 1) {
    req->add_command(env_vars.substr(0, env_vars.length() - 1));
  }
  req->add_command("--platform");
  req->add_command(
      "container-image=docker://gcr.io/foundry-x-experiments/"
      "android-platform-client-environment@sha256:"
      "796f79be0b316df94c435e697f30e00b8c6aba0741fa22c4975fdf87a089417b,"
      "jdk-version=10");
  return 0;
}

int ExecuteRemotely(const RunRequest& req) {
  const char* proxy_address = getenv("PROXY_ADDRESS");
  if (!proxy_address) {
    proxy_address = "localhost:8080";
  }
  const char* proxy_instances_var = getenv("PROXY_INSTANCES");
  int proxy_instances = 1;
  if (proxy_instances_var && !absl::SimpleAtoi(proxy_instances_var, &proxy_instances)) {
    cerr << "PROXY_INSTANCES should be an integer.";
    return 35;
  }
  if (proxy_instances > 1) {
    int port = 8080;
    vector<string> parts = absl::StrSplit(proxy_address, ':');
    if (!absl::SimpleAtoi(parts[1], &port)) {
      cerr << "If PROXY_INSTANCES>1, PROXY_ADDRESS should be host:port.";
      return 35;
    }
    port += rand() % proxy_instances;
    proxy_address = absl::StrCat(parts[0], ":", port).c_str();
  }
  if (getenv("VERBOSE")) {
    cout << "Calling remote proxy on " << proxy_address << "\n" << req.DebugString();
  }
  auto channel =
      grpc::CreateChannel(proxy_address, grpc::InsecureChannelCredentials());
  std::unique_ptr<CommandService::Stub> stub(CommandService::NewStub(channel));
  RunResponse resp;
  ClientContext context;  // No deadline.
  std::unique_ptr<ClientReader<RunResponse> > reader(stub->Run(&context, req));
  while (reader->Read(&resp)) {
    if (!resp.stdout().empty()) {
      cout << resp.stdout();
    }
    if (!resp.stderr().empty()) {
      cerr << resp.stderr();
    }
    if (resp.has_result()) {
      RunResult result = resp.result();
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
  const char* verbose = getenv("VERBOSE");
  if (verbose) {  // Enable profilig with VERBOSE.
    start_time = std::chrono::high_resolution_clock::now();
  }
  setenv("PWD", "/proc/self/cwd", true);  // Will apply to both local and remote commands.
  // Build local command arguments.
  vector<const char*> args;
  args.reserve(argc - 3);
  for (int i = 4; i < argc; ++i) {
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
  if (!is_compile && !is_javac && !getenv("RUN_ALL_REMOTELY")) {
    // Only run compile actions remotely for now.
    if (verbose) {
      cout << "Executing non-compile action locally: " << local_cmd << "\n";
    }
    return execvp(args[0], const_cast<char**>(args.data()));
  }
  if (verbose) {
    proxy_start_time = std::chrono::high_resolution_clock::now();
  }
  int exit_code = 1;
  int sleep_ms = 500;
  const char* attempts_str = getenv("REMOTE_RETRY");
  int attempts = 1;
  if (attempts_str && !absl::SimpleAtoi(attempts_str, &attempts)) {
    cerr << "REMOTE_RETRY variable should be an integer.\n";
    return 1;
  }
  for (int i = 0; i < attempts; ++i) {
    exit_code = ExecuteRemotely(req);
    if (!exit_code) {
      break;
    }
    cerr << "FAILED " << cmd_id << " (exit_code = " << exit_code << ", attempt " << i + 1 << ")\n";
    if (i < attempts - 1) {
      std::this_thread::sleep_for(std::chrono::milliseconds(sleep_ms));
      sleep_ms *= 2;
    }
  }
  if (verbose) {
    end_time = std::chrono::high_resolution_clock::now();
    milliseconds remote_ms = duration_cast<milliseconds>(end_time - proxy_start_time);
    milliseconds overhead_ms = duration_cast<milliseconds>(proxy_start_time - start_time);
    cerr << "Command " << cmd_id << " remote time: " << remote_ms.count()
         << " msec, overhead time " << overhead_ms.count() << " msec\n";
  }
  if (exit_code) {
    bool fallback = true;
    char *val = getenv("LOCAL_FALLBACK");
    if (val && !strcmp(val, "false")) {
      fallback = false;
    }
    if (fallback) {
      cout << "Falling back to local execution " << cmd_id << "\n";
      return execvp(args[0], const_cast<char**>(args.data()));
    }
  }
  return exit_code;
}

int SelectAndRunCommand(int argc, char** argv, const char** env) {
  srand(time(nullptr));
  // Hack to allow goma_ctl ensure_start to work. This is called by
  // the Android @head build when USE_GOMA is set.
  if (!strcmp(argv[1], "tmp_dir")) {
    const char *tmp_dir = "/tmp/goma_tmp";
    mkdir(tmp_dir, 0777);
    cout << tmp_dir << "\n";
    return 0;
  }

  if (!strcmp(argv[1], "list_includes")) {
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

  if (!strcmp(argv[1], "run")) {
    return ExecuteCommand(argc, argv, env);
  }

  if (!strcmp(argv[1], "include_stats")) {
    return IncludeProcessorStats();
  }

  cerr << "Unrecognized command " << argv[1]
       << ", supported commands are \"run\", \"tmp_dir\", \"list_includes\"\n";
  return 35;
}

}  // namespace remote_client
