#include "src/main/cc/proxy_client/proxy_client.h"

#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
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
#include "src/main/proto/command_server.grpc.pb.h"
#include "src/main/proto/command_server.pb.h"
#include "src/main/proto/include_processor.pb.h"

#define INCLUDE_PROCESSOR_PROXY_FAILURE 44

namespace remote_client {

using devtools_goma::GomaIPC;
using grpc::Channel;
using grpc::ClientContext;
using grpc::ClientReader;
using include_processor::ProcessIncludesRequest;
using include_processor::ProcessIncludesResponse;
using std::cerr;
using std::chrono::milliseconds;
using std::chrono::duration_cast;
using std::cout;
using std::getenv;
using std::set;
using std::string;
using std::vector;

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
      iter = segments.erase(iter - 1, iter + 1);
      continue;
    }
    ++iter;
  }
  return absl::StrJoin(segments.begin(), segments.end(), "/");
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

int GetInputsFromIncludeProcessorProxy(int argc, char** argv, const char** env,
                                       const string& cwd, set<string>* inputs) {
  ProcessIncludesRequest req;
  req.set_cwd(cwd);
  for (int i = 4; i < argc; ++i) {
    req.add_args(argv[i]);
  }
  const char* verbose = getenv("VERBOSE");
  if (verbose) {
    cout << "Sending process includes request:\n" << req.DebugString() << "\n";
  }
  const char *goma_tmp_dir = getenv("GOMA_TMP_DIR");
  if (goma_tmp_dir == nullptr) {
    goma_tmp_dir = "/tmp/goma_tmp";
  }
  GomaIPC goma_ipc(std::unique_ptr<GomaIPC::ChanFactory>(
      new GomaIPC::GomaIPCSocketFactory(absl::StrCat(goma_tmp_dir, "/goma.ipc"))));
  GomaIPC::Status status;
  ProcessIncludesResponse resp;
  if (goma_ipc.Call("/ipc", &req, &resp, &status) < 0) {
    cerr << "Process includes failed: " << status.DebugString() << "\n";
    return INCLUDE_PROCESSOR_PROXY_FAILURE;
  }
  if (verbose) {
    cout << "Received process includes response:\n" << resp.DebugString() << "\n";
  }
  for (const string& i : resp.includes()) {
    inputs->insert(NormalizedRelativePath(cwd, i));
  }
  return 0;
}

int GetInputsFromIncludeProcessor(int argc, char** argv, const char** env,
                                  const string& cwd, set<string>* inputs) {
  if (getenv("USE_IP_PROXY")) {
    return GetInputsFromIncludeProcessorProxy(argc, argv, env, cwd, inputs);
  }

  string processor_path = absl::StrCat(getenv("HOME"), "/goma/cpp_include_processor");
  string compiler_path = absl::StrCat(cwd, "/", argv[4]);
  vector<const char*> args;
  args.reserve(argc - 1);
  args.emplace_back(processor_path.c_str());
  args.emplace_back(argv[4][0] == '/' ? argv[4] : compiler_path.c_str());
  for (int i = 5; i < argc; ++i) {
    args.emplace_back(argv[i]);
  }
  args.emplace_back(nullptr);

  const int kPipeRead = 0;
  const int kPipeWrite = 1;
  int aStdoutPipe[2];

  if (pipe(aStdoutPipe) < 0) {
    cerr << "Error allocating pipe for child output redirect\n";
    return 1;
  }

  bool verbose = getenv("VERBOSE");
  if (verbose) {
    for (unsigned int i = 0; i < args.size() - 1; ++i) {
      cout << args[i] << " ";
    }
    cout << "\n";
  }
  int fork_result = fork();
  if (fork_result < 0) {
    cerr << "Failed to create child process: " << fork_result << "\n";
    close(aStdoutPipe[kPipeRead]);
    close(aStdoutPipe[kPipeWrite]);
    return fork_result;
  }
  if (0 == fork_result) {
    // Child continues here.
    if (dup2(aStdoutPipe[kPipeWrite], STDOUT_FILENO) == -1) {
      cerr << "Failed to redirect stdout\n";
      exit(1);
    }
    close(aStdoutPipe[kPipeRead]);
    close(aStdoutPipe[kPipeWrite]);

    exit(execvp(args[0], const_cast<char**>(args.data())));
  }
  // Parent continues here.

  close(aStdoutPipe[kPipeWrite]);

  char buf[10001];
  string out;
  unsigned int n;
  while ((n = read(aStdoutPipe[kPipeRead], buf, sizeof(buf)-1))) {
    buf[n] = 0;
    out += buf;  // This is fine, we won't do it a lot.
  }

  if (verbose) {
    cout << "Include preprocessor returned:\n" << out << "\n";
  }
  for (const auto& input : absl::StrSplit(out, '\n', absl::SkipEmpty())) {
    inputs->insert(NormalizedRelativePath(cwd, string(input)));
  }
  return 0;
}

int ComputeInputs(int argc, char** argv, const char** env, const string& cwd, const string& cmd_id,
                  bool *is_compile, set<string>* inputs) {
  set<string> inputs_from_args;
  if (!absl::StartsWith(argv[2], "-inputs:")) {
    cerr << "Missing -inputs\n";
    return 1;
  }
  const char* input_arg = argv[2] + 8;
  for (const auto& input : absl::StrSplit(input_arg, ',', absl::SkipEmpty())) {
    inputs_from_args.insert(string(input));
  }
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
  }
  if (*is_compile) {
    int proc_res = GetInputsFromIncludeProcessor(argc, argv, env, cwd, inputs);
    // Ignore failures from include processor proxy failures since it fails deterministically on
    // assembly commands today which we will eventually fix.
    if (proc_res != 0 && proc_res != INCLUDE_PROCESSOR_PROXY_FAILURE) {
      return proc_res;
    }
    if (inputs->empty()) {
      // We successfully called the include processor, but it returned no values.
      // Fall back on computing from the command, but warn.
      cerr << cmd_id << "> Include processor did not return results, computing from args\n";
    }
  }
  if (inputs->empty()) {
    inputs->insert(inputs_from_args.begin(), inputs_from_args.end());
  }
  // Common inputs:
  inputs->insert("build");  // Needed for Android 9?
  inputs->insert("toolchain");
  inputs->insert(GetCompilerDir(argv[4]));  // For both compile and link commands?
  if (is_compile) {
    inputs->insert(argv[argc-1]);  // For Android compile commands, the compiled file is last.
  } // Linker commands need special treatment as well.
  return 0;
}

int CreateRunRequest(int argc, char** argv, const char** env,
                     const string& cmd_id, RunRequest* req, bool* is_compile) {
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
  int compute_input_res = ComputeInputs(argc, argv, env, cwd, cmd_id, is_compile, &inputs);
  if (compute_input_res != 0) {
    cerr << cmd_id << "> Failed to compute inputs\n";
    return compute_input_res;
  }
  if (!inputs.empty()) {
    req->add_command("--inputs");
  }
  bool allow_outputs_under_inputs = getenv("ALLOW_OUTPUTS_UNDER_INPUTS") != nullptr;
  bool allow_output_directories_as_inputs = getenv("ALLOW_OUTPUT_DIRECTORIES_AS_INPUTS") != nullptr;
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
  req->add_command("\\.git$");
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
    srand(time(nullptr));
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
  bool is_compile;
  int create_run_res = CreateRunRequest(argc, argv, env, cmd_id, &req, &is_compile);
  if (create_run_res != 0) {
    return create_run_res;  // Failed to create request.
  }
  if (!is_compile && !getenv("RUN_ALL_REMOTELY")) {
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
    bool is_compile;
    int result = ComputeInputs(argc, argv, env, GetCwd(), "cmd", &is_compile, &includes);
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

  cerr << "Unrecognized command " << argv[1]
       << ", supported commands are \"run\", \"tmp_dir\", \"list_includes\"\n";
  return 35;
}

}  // namespace remote_client
