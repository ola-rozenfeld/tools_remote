#ifndef REMOTE_CLIENT_PROXY_CLIENT_H_
#define REMOTE_CLIENT_PROXY_CLIENT_H_

#define HAS_GLOBAL_STRING

namespace remote_client {

int SelectAndRunCommand(int argc, char** argv, const char** env);

}  // namespace remote::client

#endif  // REMOTE_CLIENT_PROXY_CLIENT_H_