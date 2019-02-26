#include "src/main/cc/proxy_client/simple.h"

#include <iostream>

namespace simple {
  int Foo(const char *arg) {
    std::cout << ">>>> bla: " << arg;
    return 5;
  }
}  // namespace simple
