// Copyright 2010 Google Inc. All Rights Reserved.
// Author: uekawa@google.com (Junichi Uekawa)
// BEGIN GOOGLE-INTERNAL
// Google3 path.h emulation layer.
// END GOOGLE-INTERNAL

#ifndef DEVTOOLS_GOMA_BASE_PATH_H_
#define DEVTOOLS_GOMA_BASE_PATH_H_

#include <initializer_list>
#include <string>

#include "absl/strings/string_view.h"

using std::string;

namespace file {

namespace internal {
string JoinPathImpl(std::initializer_list<absl::string_view> paths);
string JoinPathRespectAbsoluteImpl(
    std::initializer_list<absl::string_view> paths);
}  // namespace internal

absl::string_view Basename(absl::string_view path);

// Returns dirname.
// For example:
//   Dirname("a/b") --> "a"
//   Dirname("a") --> ""
// On Windows, drive letter is handled:
//   Dirname("C:\\foo") --> "C:\\"
//   Dirname("C:a") --> "C:"
// See lib/path_unittest.cc for more examples.
absl::string_view Dirname(absl::string_view fname);

absl::string_view Stem(absl::string_view path);

absl::string_view Extension(absl::string_view path);

// New file path API.
// It always returns path1/path2 even when path2 is absolute.
template<typename... Strs>
inline string JoinPath(const Strs&... paths) {
  return internal::JoinPathImpl({paths...});
}

// It would return path2, if path2 is absolute.
template<typename... Strs>
inline string JoinPathRespectAbsolute(const Strs&... paths) {
  return internal::JoinPathRespectAbsoluteImpl({paths...});
}

// Return true if path is absolute.
bool IsAbsolutePath(absl::string_view path);

}  // namespace file

#endif  // DEVTOOLS_GOMA_BASE_PATH_H_
