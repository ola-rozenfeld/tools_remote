// Copyright 2010 Google Inc. All Rights Reserved.
// Author: hamaji@google.com (Shinichiro Hamaji)

#ifndef DEVTOOLS_GOMA_CLIENT_UTIL_H_
#define DEVTOOLS_GOMA_CLIENT_UTIL_H_

#ifndef _WIN32
# include <unistd.h>
#else
# include <direct.h>
#endif

#ifdef _WIN32
#include "config_win.h"
#endif

namespace devtools_goma {

// Platform independent getpid function.
pid_t Getpid();

}  // namespace devtools_goma

#endif  // DEVTOOLS_GOMA_CLIENT_UTIL_H_
