// Copyright 2010 Google Inc. All Rights Reserved.
// Author: hamaji@google.com (Shinichiro Hamaji)

#include "util.h"

namespace devtools_goma {

pid_t Getpid() {
#ifdef _WIN32
  return static_cast<pid_t>(::GetCurrentProcessId());
#else
  return getpid();
#endif
}

}  // namespace devtools_goma
