// Copyright 2016 Google Inc. All Rights Reserved.

#ifndef DEVTOOLS_GOMA_LIB_PATH_UTIL_H_
#define DEVTOOLS_GOMA_LIB_PATH_UTIL_H_

#include "absl/strings/string_view.h"

namespace devtools_goma {

bool IsPosixAbsolutePath(absl::string_view path);
bool IsWindowsAbsolutePath(absl::string_view path);

bool HasPrefixDir(absl::string_view path, absl::string_view prefix);
bool HasPrefixDirWithSep(absl::string_view path, absl::string_view prefix,
                         char pathsep);

// Get file dirname of the given |filename|.
// This function think both '/' and '\\' as path separators.
// BEGIN GOOGLE-INTERNAL
// Note that google3 file::Dirname does not know Windows pathsep, and we
// need this function to get basename in Windows path (on Linux or google3).
// END GOOGLE-INTERNAL
absl::string_view GetDirname(absl::string_view filename);

// Get file basename of the given |filename|.
// This function think both '/' and '\\' as path separators.
// BEGIN GOOGLE-INTERNAL
// Note that google3 file::Basename does not know Windows pathsep, and we
// need this function to get basename in Windows path (on Linux or google3).
// END GOOGLE-INTERNAL
absl::string_view GetBasename(absl::string_view filename);

// Get file extension of the given |filename|.
// This function think both '/' and '\\' as path separators.
// BEGIN GOOGLE-INTERNAL
// Note that google3 file::Extention does not know Windows pathsep, and we
// need this function to get extention in Windows path (on Linux or google3).
// END GOOGLE-INTERNAL
absl::string_view GetExtension(absl::string_view filename);

// Get the part of the basename of |filename| prior to the final ".".
// If there is no "." in |filename|, this function gets basename.
// BEGIN GOOGLE-INTERNAL
// Note that google3 file::Stem does not know Windows pathsep, and we
// need this function to get stem in Windows path (on Linux or google3).
// END GOOGLE-INTERNAL
absl::string_view GetStem(absl::string_view filename);

}  // namespace devtools_goma

#endif  // DEVTOOLS_GOMA_LIB_PATH_UTIL_H_
