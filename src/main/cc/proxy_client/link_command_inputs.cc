#include "src/main/cc/proxy_client/link_command_inputs.h"
#include "src/main/cc/proxy_client/common_utils.h"

#include <set>
#include <string>
#include <sys/stat.h>

namespace remote_client {

using std::set;
using std::string;

void FindLibrarySearchPaths(int argc, char** argv, set<string>* lib_search_paths) {
  for (int i = 0; i < argc; ++i) {
    const string cur(argv[i]);
    if (cur.length() > 2 && cur[0] == '-' && cur[1] == 'L') {
      lib_search_paths->insert(cur.substr(2));
    }
  }
}

void FindLinkedLibraries(int argc, char** argv, set<string>* libraries) {
  for (int i = 0; i < argc; ++i) {
    const string cur(argv[i]);
    if (cur.length() > 2 && cur[0] == '-' && cur[1] == 'l') {
      libraries->insert("lib" + cur.substr(2));
    }
  }
}

void FindLibraryInputs(int argc, char** argv, set<string>* lib_paths) {
  set<string> lib_search_paths;
  FindLibrarySearchPaths(argc, argv, &lib_search_paths);

  set<string> libraries;
  FindLinkedLibraries(argc, argv, &libraries);

  // The loop below is O(m * n), m = num libraries, n = num search directories.
  // This is fine because:
  // 1) In the longer term, this would live in the include processor where it'll be cached.
  // 2) m and n are usually small values (<=10) for AP@7 atleast.
  for (const auto& library : libraries) {
    for (const auto& lib_path : lib_search_paths) {
      const string relative_static_lib_path = lib_path + "/" + library + ".a";
      const string relative_dynamic_lib_path = lib_path + "/" + library + ".so";

      bool is_dir;
      if (PathExists(relative_static_lib_path, &is_dir)) {
        lib_paths->insert(relative_static_lib_path);
      }
      if (PathExists(relative_dynamic_lib_path, &is_dir)) {
        lib_paths->insert(relative_dynamic_lib_path);
      }
    }
  }
}

}
