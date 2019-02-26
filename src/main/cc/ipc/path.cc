// Copyright 2010 Google Inc. All Rights Reserved.
// Author: uekawa@google.com (Junichi Uekawa)

#include "path.h"

#include <stddef.h>

using std::string;

namespace {

// Appends |path2| to |path1|.
// Assuming |path1| and |path2| are not empty.
void AppendPath(string* path1, absl::string_view path2) {
#ifndef _WIN32
  if (path2[0] == '/') {
    path2.remove_prefix(1);
  }
  if (path1->back() != '/') {
    path1->push_back('/');
  }
#else
  if (path2[0] == '\\' || path2[0] == '/') {
    path2.remove_prefix(1);
  }
  if (path1->back() != '\\' && path1->back() != '/') {
    path1->push_back('\\');
  }
#endif

  path1->append(path2.begin(), path2.end());
}

}  // anonymous namespace

namespace file {

namespace internal {

string JoinPathIterators(const absl::string_view* begin,
                         const absl::string_view* end,
                         size_t cap) {
  string result;
  result.reserve(cap);

  while (begin != end) {
    const absl::string_view path = *begin++;

    if (path.empty()) {
      continue;
    }
    if (result.empty()) {
      result.append(path.begin(), path.end());
      continue;
    }
    AppendPath(&result, path);
  }

  return result;
}

string JoinPathImpl(std::initializer_list<absl::string_view> paths) {
  size_t cap = 0;
  for (const auto& path : paths) {
    cap += path.size() + 1;
  }

  return JoinPathIterators(paths.begin(), paths.end(), cap);
}

string JoinPathRespectAbsoluteImpl(
    std::initializer_list<absl::string_view> paths) {
  size_t cap = 0;
  auto start_iter = paths.end();
  for (; start_iter != paths.begin();) {
    --start_iter;
    const auto& path = *start_iter;
    cap += path.size() + 1;
    if (IsAbsolutePath(path)) {
      break;
    }
  }

  return JoinPathIterators(start_iter, paths.end(), cap);
}

}  // namespace internal

absl::string_view Basename(absl::string_view fname) {
#ifndef _WIN32
  // BEGIN GOOGLE-INTERNAL
  // This follows google3's file::Basename.
  // A bit different from POSIX basename.
  // e.g. /usr/ -> POSIX: usr, google3: empty absl::string_view.
  // END GOOGLE-INTERNAL
  absl::string_view::size_type pos = fname.find_last_of('/');
  // Handle the case with no '/' in |fname|.
  if (pos == absl::string_view::npos)
    return fname;
  return fname.substr(pos + 1);
#else
  // TODO(yyanagisawa): support UNC path.
  char name[_MAX_FNAME] = {0};
  char ext[_MAX_EXT] = {0};
  size_t basename_length = strlen(name);
  if (ext[0] == '.')
    basename_length += strlen(ext);
  fname.remove_prefix(fname.length() - basename_length);
  return fname;
#endif
}

absl::string_view Dirname(absl::string_view fname) {
#ifndef _WIN32
  // BEGIN GOOGLE-INTERNAL
  // This follows google3's file::Dirname, which is a bit different from
  // POSIX dirname.
  // e.g. /usr/ -> POSIX: /, google3: /usr.
  // e.g. '' (empty string) -> POSIX: ., google3: empty absl::string_view.
  // END GOOGLE-INTERNAL
  absl::string_view::size_type pos = fname.find_last_of('/');

  // Handle the case with no '/' in 'path'.
  if (pos == absl::string_view::npos)
    return fname.substr(0, 0);

  // Handle the case with a single leading '/' in 'path'.
  if (pos == 0)
    return fname.substr(0, 1);

  return fname.substr(0, pos);
#else
  // TODO(yyanagisawa): support UNC path.
  // BEGIN GOOGLE-INTERNAL
  // Similar to google3's file::Dirname, which is a bit different from
  // Windows _splitpath_s not to surprise developers familier with google3.
  // i.e. Return value does not end with '\\' except directory is single '\\'.
  //
  // e.g.
  // "C:\\" -> "C:\\"
  // "C:\\Windows\\win.ini" -> "C:\\Windows"
  // END GOOGLE-INTERNAL
  char drive[_MAX_DRIVE] = {0};
  char dir[_MAX_DIR] = {0};
  _splitpath_s(string(fname).c_str(), drive, sizeof drive, dir, sizeof dir,
               nullptr, 0, nullptr, 0);
  const size_t dir_length = strlen(dir);
  size_t dirname_length = strlen(drive) + dir_length;
  // Cut off last '\\' or '/' if dir is not single '\\' or '/'.
  if (dir_length > 1) {
    dirname_length--;
  }
  return fname.substr(0, dirname_length);
#endif
}

absl::string_view Stem(absl::string_view fname) {
  absl::string_view path = Basename(fname);
  absl::string_view::size_type pos = path.find_last_of('.');
  if (pos == absl::string_view::npos)
    return path;
  return path.substr(0, pos);
}

absl::string_view Extension(absl::string_view fname) {
  absl::string_view path = Basename(fname);
  absl::string_view::size_type pos = path.find_last_of('.');
  if (pos == absl::string_view::npos)
    return fname.substr(fname.size());
  return path.substr(pos + 1);
}

bool IsAbsolutePath(absl::string_view path) {
#ifndef _WIN32
  return !path.empty() && path[0] == '/';
#else
  return (!path.empty() && path[0] == '\\') ||
      (!path.empty() && path[0] == '/') ||
      (path.size() > 1 && path[1] == ':');
#endif
}

}  // namespace file
