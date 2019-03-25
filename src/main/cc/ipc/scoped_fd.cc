// Copyright 2011 Google Inc. All Rights Reserved.
// Author: ukai@google.com (Fumitoshi Ukai)

#include "scoped_fd.h"

#include "absl/strings/string_view.h"

#ifdef _WIN32
#include "base/socket_helper_win.h"
#include "lib/path_resolver.h"
#endif

#include <iostream>

#ifndef _WIN32
# include <errno.h>
# include <fcntl.h>
# include <poll.h>
# include <sys/socket.h>
# include <sys/stat.h>
# include <sys/types.h>
# include <unistd.h>
#endif

namespace devtools_goma {

int SetFileDescriptorFlag(int fd, int flag) {
  int old_flag = fcntl(fd, F_GETFD);
  if (old_flag == -1) {
    std::cerr << "Cannot GETFD for fd:" << fd;
    return -1;
  }
  if (fcntl(fd, F_SETFD, old_flag | flag) == -1) {
    std::cerr << "Cannot SETFD for fd:" << fd;
    return -1;
  }
  return 0;
}

int SetFileStatusFlag(int fd, int flag) {
  int old_flag = fcntl(fd, F_GETFL);
  if (old_flag == -1) {
    std::cerr << "Cannot GETFL for fd:" << fd;
    return -1;
  }
  if (fcntl(fd, F_SETFL, old_flag | flag) == -1) {
    std::cerr << "Cannot SETFL for fd:" << fd;
    return -1;
  }
  return 0;
}

#ifndef _WIN32
static ScopedFd::FileDescriptor kInvalidFd = -1;
#else
static ScopedFd::FileDescriptor kInvalidFd = INVALID_HANDLE_VALUE;
#endif

ScopedFd::ScopedFd()
    : fd_(kInvalidFd) {
}

ScopedFd::ScopedFd(FileDescriptor fd)
    : fd_(fd) {
  if (valid())
    SetCloseOnExec();
}

ScopedFd::~ScopedFd() {
  Close();
}

/* static */
ScopedFd::FileDescriptor ScopedFd::OpenForRead(const string& filename) {
#ifndef _WIN32
  return open(filename.c_str(), O_RDONLY);
#else
  // On Windows, the length of path is 256. When compiling NaCl untrusted code,
  // the length of path often exceeds 256. Usually it contains '..', so let's
  // clean it.
  const string& resolved = PathResolver::ResolvePath(filename);
  return CreateFileA(resolved.c_str(), GENERIC_READ,
                     FILE_SHARE_READ,
                     nullptr,
                     OPEN_EXISTING,
                     FILE_ATTRIBUTE_NORMAL,
                     nullptr);
#endif
}

/* static */
ScopedFd::FileDescriptor ScopedFd::OpenForAppend(
    const string& filename, int mode) {
#ifndef _WIN32
  return open(filename.c_str(), O_WRONLY | O_CREAT | O_APPEND, mode);
#else
  UNREFERENCED_PARAMETER(mode);
  // TODO(ukai): translate mode to file attribute.
  const string& resolved = PathResolver::ResolvePath(filename);
  HANDLE h = CreateFileA(resolved.c_str(),
                         FILE_APPEND_DATA,
                         FILE_SHARE_WRITE,
                         nullptr,
                         CREATE_NEW,
                         FILE_ATTRIBUTE_NORMAL,
                         nullptr);
  if (h == INVALID_HANDLE_VALUE) {
    std::cerr << "OpenForAppend failed: filename=" << filename;
  }
  return h;
#endif
}

/* static */
ScopedFd::FileDescriptor ScopedFd::OpenForRewrite(const string& filename) {
#ifndef _WIN32
  return open(filename.c_str(), O_RDWR);
#else
  const string& resolved = PathResolver::ResolvePath(filename);
  HANDLE h = CreateFileA(resolved.c_str(),
                         GENERIC_READ | GENERIC_WRITE,
                         0,
                         nullptr,
                         OPEN_EXISTING,
                         FILE_ATTRIBUTE_NORMAL,
                         nullptr);
  if (h == INVALID_HANDLE_VALUE) {
    std::cerr << "OpenForRewrite failed: filename=" << filename;
  }

  return h;
#endif
}

ScopedFd::FileDescriptor ScopedFd::Create(
    const string& filename, int mode) {
#ifndef _WIN32
  return open(filename.c_str(), O_WRONLY | O_CREAT | O_TRUNC, mode);
#else
  UNREFERENCED_PARAMETER(mode);
  // TODO(ukai): translate mode to file attribute.
  const string& resolved = PathResolver::ResolvePath(filename);
  HANDLE h = CreateFileA(resolved.c_str(),
                         GENERIC_WRITE,
                         FILE_SHARE_WRITE,
                         nullptr,
                         CREATE_ALWAYS,
                         FILE_ATTRIBUTE_NORMAL,
                         nullptr);
  if (h == INVALID_HANDLE_VALUE) {
    LOG_SYSRESULT(GetLastError());
    std::cerr << "Create failed: filename=" << filename;
  }
  return h;
#endif
}

ScopedFd::FileDescriptor ScopedFd::CreateExclusive(
    const string& filename, int mode) {
#ifndef _WIN32
  return open(filename.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_EXCL, mode);
#else
  UNREFERENCED_PARAMETER(mode);
  // TODO(yyanagisawa): translate mode to file attribute.
  // If the file exists, CreateFile with dwCreationDisposition == CREATE_NEW
  // will fail.
  // See: http://msdn.microsoft.com/en-us/library/windows/desktop/aa363858(v=vs.85).aspx
  const string& resolved = PathResolver::ResolvePath(filename);
  HANDLE h = CreateFileA(resolved.c_str(),
                         GENERIC_WRITE,
                         0,
                         nullptr,
                         CREATE_NEW,
                         FILE_ATTRIBUTE_NORMAL,
                         nullptr);
  if (h == INVALID_HANDLE_VALUE) {
    LOG_SYSRESULT(GetLastError());
    std::cerr << "CreateExclusive failed: filename=" << filename;
  }
  return h;
#endif
}

ScopedFd::FileDescriptor ScopedFd::OpenNull() {
#ifndef _WIN32
  return open("/dev/null", O_RDWR, 0600);
#else
  // To allow child process to continue using NUL, bInheritHandle should be set.
  SECURITY_ATTRIBUTES secattr;
  secattr.nLength = sizeof(secattr);
  secattr.lpSecurityDescriptor = nullptr;
  secattr.bInheritHandle = TRUE;
  // NUL is something like Unix /dev/null on Windows.
  // http://stackoverflow.com/questions/438092/how-to-open-a-nul-file
  // http://blogs.msdn.com/b/oldnewthing/archive/2003/10/22/55388.aspx
  return CreateFileA("NUL", GENERIC_WRITE, 0, &secattr, OPEN_EXISTING, 0,
                     nullptr);
#endif
}

bool ScopedFd::valid() const {
#ifndef _WIN32
  return fd_ >= 0;
#else
  if (fd_ == nullptr || fd_ == kInvalidFd)
    return false;
  return true;
#endif
}

void ScopedFd::SetCloseOnExec() const {
#ifndef _WIN32
  SetFileDescriptorFlag(fd_, FD_CLOEXEC);
#endif
}

ssize_t ScopedFd::Read(void* ptr, size_t len) const {
#ifndef _WIN32
  ssize_t r = 0;
  while ((r = read(fd_, ptr, len)) < 0) {
    if (errno != EINTR) break;
  }
  return r;
#else
  DWORD bytes_read = 0;
  if (!ReadFile(fd_, ptr, len, &bytes_read, nullptr)) {
    LOG_SYSRESULT(GetLastError());
    return -1;
  }
  return bytes_read;
#endif
}

ssize_t ScopedFd::Write(const void* ptr, size_t len) const {
#ifndef _WIN32
  ssize_t r = 0;
  while ((r = write(fd_, ptr, len)) < 0) {
    if (errno != EINTR) break;
  }
  return r;
#else
  DWORD bytes_written = 0;
  if (!WriteFile(fd_, ptr, len, &bytes_written, nullptr)) {
    LOG_SYSRESULT(GetLastError());
    return -1;
  }
  return bytes_written;
#endif
}

off_t ScopedFd::Seek(off_t offset, Whence whence) const {
#ifndef _WIN32
  return lseek(fd_, offset, whence);
#else
  // TODO(ukai): use lpDistanceToMoveHigh for high order 32bits of 64bits?
  DWORD r = SetFilePointer(fd_, offset, nullptr, whence);
  if (r == INVALID_SET_FILE_POINTER) {
    DWORD err = GetLastError();
    if (err != NO_ERROR) {
      LOG_SYSRESULT(err);
      return static_cast<off_t>(-1);
    }
    // maybe, seek success.
  }
  return r;
#endif
}

bool ScopedFd::GetFileSize(size_t* file_size) const {
  *file_size = 0;
#ifndef _WIN32
  struct stat st;
  if (fstat(fd_, &st) != 0)
    return false;
  *file_size = st.st_size;
  return true;
#else
  DWORD size = ::GetFileSize(fd_, nullptr);
  if (size == INVALID_FILE_SIZE) {
    LOG_SYSRESULT(GetLastError());
    return false;
  }
  *file_size = size;
  return true;
#endif
}

void ScopedFd::reset(ScopedFd::FileDescriptor fd) {
  Close();
  fd_ = fd;
#ifndef _WIN32
  if (fd >= 0) {
    SetCloseOnExec();
  }
#endif
}

ScopedFd::FileDescriptor ScopedFd::release() {
  FileDescriptor fd = fd_;
  fd_ = kInvalidFd;
  return fd;
}

bool ScopedFd::Close() {
  if (valid()) {
#ifndef _WIN32
    return close(release()) == 0;
#else
    return CloseHandle(release()) == TRUE;
#endif
  }
  return true;
}

ScopedSocket::~ScopedSocket() {
  Close();
}

bool ScopedSocket::SetCloseOnExec() const {
#ifndef _WIN32
  return SetFileDescriptorFlag(fd_, FD_CLOEXEC) == 0;
#else
  return true;
#endif
}

bool ScopedSocket::SetNonBlocking() const {
#ifndef _WIN32
  return SetFileStatusFlag(fd_, O_NONBLOCK) == 0;
#else
  unsigned long non_blocking = 1;
  return ioctlsocket(fd_, FIONBIO, &non_blocking) != SOCKET_ERROR;
#endif
}

bool ScopedSocket::SetReuseAddr() const {
  int yes = 1;
#ifndef _WIN32
  return setsockopt(fd_, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes)) == 0;
#else
  return setsockopt(fd_, SOL_SOCKET, SO_REUSEADDR,
                    (const char*)&yes, sizeof(yes)) == 0;
#endif
}

void ScopedSocket::reset(int fd) {
  Close();
  fd_ = fd;
}

ssize_t ScopedSocket::Read(void* ptr, size_t len) const {
#ifndef _WIN32
  return read(fd_, ptr, len);
#else
  return recv(fd_, (char*)ptr, len, 0);
#endif
}

ssize_t ScopedSocket::Write(const void* ptr, size_t len) const {
#ifndef _WIN32
  return write(fd_, ptr, len);
#else
  return send(fd_, (char*)ptr, len, 0);
#endif
}

bool ScopedSocket::Close() {
  if (valid()) {
#ifndef _WIN32
    return close(release()) == 0;
#else
    return closesocket(release()) == 0;
#endif
  }
  return true;
}

// Read. Return < 0 on error.
ssize_t ScopedSocket::ReadWithTimeout(char* buf,
                                      size_t bufsize,
                                      absl::Duration timeout) const {
  for (;;) {
#ifdef _WIN32
    // Since WSAPoll (Windows poll API) is broken, we should use select on Win.
    // See: http://daniel.haxx.se/blog/2012/10/10/wsapoll-is-broken/
    fd_set fdset;
    FD_ZERO(&fdset);
    MSVC_PUSH_DISABLE_WARNING_FOR_FD_SET();
    FD_SET(static_cast<SOCKET>(fd_), &fdset);
    MSVC_POP_WARNING();
    TIMEVAL timeout_tv = absl::ToTimeval(timeout);
    // http://msdn.microsoft.com/en-us/library/windows/desktop/ms740141(v=vs.85).aspx
    int result = select(fd_ + 1, &fdset, nullptr, nullptr, &timeout_tv);
    if (result == SOCKET_ERROR) {
      std::cerr << "GOMA: read select error";
      return FAIL;
    }
    if (result == 0) {
      std::cerr << "GOMA: read select timeout " << timeout;
      return ERR_TIMEOUT;
    }
#else
    struct pollfd pfd;
    pfd.fd = fd_;
    pfd.events = POLLIN;
    const int timeout_ms = static_cast<int>(absl::ToInt64Milliseconds(timeout));
    int result;
    while ((result = poll(&pfd, 1, timeout_ms)) == -1) {
      if (errno != EINTR)
        break;
    }
    if (result == -1) {
      std::cerr << "GOMA: read poll error";
      return FAIL;
    }
    if (result == 0) {
      std::cerr << "GOMA: read poll timeout " << timeout;
      return ERR_TIMEOUT;
    }
#endif

    ssize_t ret = Read(buf, bufsize);
    if (ret == -1) {
      if (errno == EAGAIN || errno == EINTR)
        continue;
      std::cerr << "read";
    }
    return ret;
  }
}

ssize_t ScopedSocket::WriteWithTimeout(const char* buf,
                                       size_t bufsize,
                                       absl::Duration timeout) const {
  for (;;) {
#ifdef _WIN32
    // Since WSAPoll (Windows poll API) is broken, we should use select on Win.
    // See: http://daniel.haxx.se/blog/2012/10/10/wsapoll-is-broken/
    fd_set fdset;
    FD_ZERO(&fdset);
    MSVC_PUSH_DISABLE_WARNING_FOR_FD_SET();
    FD_SET(fd_, &fdset);
    MSVC_POP_WARNING();
    TIMEVAL timeout_tv = absl::ToTimeval(timeout);
    // http://msdn.microsoft.com/en-us/library/windows/desktop/ms740141(v=vs.85).aspx
    int result = select(fd_ + 1, nullptr, &fdset, nullptr, &timeout_tv);
    if (result == SOCKET_ERROR) {
      std::cerr << "GOMA: write select error";
      return FAIL;
    }
    if (result == 0) {
      std::cerr << "GOMA: write select timeout " << timeout;
      return ERR_TIMEOUT;
    }
#else
    struct pollfd pfd;
    pfd.fd = fd_;
    pfd.events = POLLOUT;
    const int timeout_ms = static_cast<int>(absl::ToInt64Milliseconds(timeout));
    int result;
    while ((result = poll(&pfd, 1, timeout_ms)) == -1) {
      if (errno != EINTR)
        break;
    }
    if (result == -1) {
      std::cerr << "GOMA: write poll error";
      return FAIL;
    }
    if (result == 0) {
      std::cerr << "GOMA: write poll timeout" << timeout;
      return ERR_TIMEOUT;
    }
#endif

    ssize_t ret = Write(buf, bufsize);
    if (ret == -1) {
      if (errno == EAGAIN || errno == EINTR)
        continue;
      std::cerr << "write";
    }
    return ret;
  }
}

// Write string to socket. Return negative (Errno) on fail, OK on success.
int ScopedSocket::WriteString(absl::string_view message,
                              absl::Duration timeout) const {
  const char *p = message.data();
  int size = message.size();
  while (size > 0) {
    int ret = WriteWithTimeout(p, size, timeout);
    if (ret < 0) {
      std::cerr << "write failure: " << ret
                  << " written=" << (message.size() - size) << " size=" << size
                  << " out of " << message.size();
      return ret;
    }
    p += ret;
    size -= ret;
  }
  return OK;
}

string ScopedSocket::GetLastErrorMessage() const {
  char message[1024];
#ifndef _WIN32
  // Meaning of returned value of strerror_r is different between
  // XSI and GNU. Need to ignore.
  (void)strerror_r(errno, message, sizeof(message));
#else
  FormatMessageA(FORMAT_MESSAGE_FROM_SYSTEM, nullptr,
                 WSAGetLastError(), 0,
                 message, sizeof(message), nullptr);
#endif
  return message;
}

}  // namespace devtools_goma
