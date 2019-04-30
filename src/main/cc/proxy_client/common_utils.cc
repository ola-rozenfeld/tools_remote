#include "src/main/cc/proxy_client/common_utils.h"

#include <sys/stat.h>

namespace remote_client {

bool PathExists(const string& s, bool *is_directory) {
  struct stat st;
  if (stat(s.c_str(), &st) == 0) {
    *is_directory = (st.st_mode & S_IFDIR) != 0;
    return true;
  }
  return false;
}

}
