#ifndef REMOTE_CLIENT_COMMON_UTILS_H_
#define REMOTE_CLIENT_COMMON_UTILS_H_

#include <string>

namespace remote_client {

using std::string;

bool PathExists(const string& s, bool *is_directory);

}

#endif // REMOTE_CLIENT_COMMON_UTILS_H_
