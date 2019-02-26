#include <iostream>

#include "src/main/cc/proxy_client/proxy_client.h"
#include "src/main/proto/command_server.pb.h"

int main(int argc, char** argv, const char** env) {
  return remote_client::SelectAndRunCommand(argc, argv, env);
}
