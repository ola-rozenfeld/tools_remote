#include <iostream>

#include "gflags/gflags.h"

#include "src/main/cc/proxy_client/proxy_client.h"

int main(int argc, char** argv, const char** env) {
  return remote_client::SelectAndRunCommand(argc, argv, env);
}
