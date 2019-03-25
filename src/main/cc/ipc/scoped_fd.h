// Copyright 2010 Google Inc. All Rights Reserved.
// Author: uekawa@google.com (Junichi Uekawa)

#ifndef DEVTOOLS_GOMA_LIB_SCOPED_FD_H_
#define DEVTOOLS_GOMA_LIB_SCOPED_FD_H_

#ifdef _WIN32
#pragma once

#include <Winsock2.h>

#include "config_win.h"
#else
#include <unistd.h>
#endif

#include <ostream>
#include <string>

#include "absl/strings/string_view.h"
#include "absl/time/time.h"
#include "basictypes.h"
using std::string;

namespace devtools_goma {

// Note: the Win32 version, ScopeFd is used to host HANDLEs
// TODO(arthurhsu): POSIX version set fd to be closed upon exec
class ScopedFd {
 public:
#ifdef _WIN32
  typedef HANDLE FileDescriptor;
  enum Whence {
    SeekAbsolute = FILE_BEGIN,
    SeekRelative = FILE_CURRENT
  };
#else
  typedef int FileDescriptor;
  enum Whence {
    SeekAbsolute = SEEK_SET,
    SeekRelative = SEEK_CUR
  };
#endif
  ScopedFd();
  explicit ScopedFd(FileDescriptor fd);
  ScopedFd(ScopedFd&& other) : fd_(other.release()) {}
  ~ScopedFd();

  ScopedFd& operator=(ScopedFd&& other) {
    if (this == &other) {
      return *this;
    }
    reset(other.release());
    return *this;
  }

  static FileDescriptor OpenForRead(const string& filename);
  static FileDescriptor OpenForAppend(const string& filename, int mode);
  static FileDescriptor OpenForRewrite(const string& filename);
  static FileDescriptor Create(const string& filename, int mode);
  static FileDescriptor CreateExclusive(const string& filename, int mode);
  static FileDescriptor OpenNull();

  bool valid() const;
  void SetCloseOnExec() const;

  ssize_t Read(void* ptr, size_t len) const;
  ssize_t Write(const void* ptr, size_t len) const;
  off_t Seek(off_t offset, Whence whence) const;
  bool GetFileSize(size_t* file_size) const;

  // Returns a pointer to the internal representation.
  FileDescriptor* ptr() { return &fd_; }
  FileDescriptor release();
  void reset(FileDescriptor fd);

  // Returns true on success or already closed.
  bool Close();

#ifndef _WIN32
  int fd() const { return fd_; }
#else
  HANDLE handle() const { return fd_; }
#endif

  friend std::ostream& operator<<(std::ostream& os, const ScopedFd& fd) {
#ifdef _WIN32
    return os << fd.handle();
#else
    return os << fd.fd();
#endif
  }

 private:
  FileDescriptor fd_;

  DISALLOW_COPY_AND_ASSIGN(ScopedFd);
};

enum Errno {
  OK = 0,
  FAIL = -1,
  ERR_TIMEOUT = -2,
};

class IOChannel {
 public:
  virtual ~IOChannel() {}

  virtual ssize_t Read(void* ptr, size_t len) const = 0;
  virtual ssize_t Write(const void* ptr, size_t len) const = 0;
  virtual ssize_t ReadWithTimeout(char* buf,
                                  size_t bufsize,
                                  absl::Duration timeout) const = 0;
  virtual ssize_t WriteWithTimeout(const char* buf,
                                   size_t bufsize,
                                   absl::Duration timeout) const = 0;
  // Write string to socket. Return negative on fail (Errno). OK on success.
  virtual int WriteString(absl::string_view message,
                          absl::Duration timeout) const = 0;

  // Returns the last error message. Valid when called just after
  // Write(), Read(), etc.
  virtual string GetLastErrorMessage() const = 0;

  virtual bool is_secure() const { return false; }

  virtual void StreamWrite(std::ostream& os) const = 0;

  friend std::ostream& operator<<(std::ostream& os, const IOChannel& chan) {
    chan.StreamWrite(os);
    return os;
  }
};

class ScopedSocket : public IOChannel {
 public:
#ifdef _WIN32
  ScopedSocket() : fd_(INVALID_SOCKET) {}
#else
  ScopedSocket() : fd_(-1) {}
#endif
  explicit ScopedSocket(int fd) : fd_(fd) {}
  ScopedSocket(ScopedSocket&& other) : fd_(other.release()) {}
  ~ScopedSocket() override;

  ScopedSocket& operator=(ScopedSocket&& other) {
    if (this == &other) {
      return *this;
    }
    reset(other.release());
    return *this;
  }

  ssize_t Read(void* ptr, size_t len) const override;
  ssize_t Write(const void* ptr, size_t len) const override;
  ssize_t ReadWithTimeout(char* buf,
                          size_t bufsize,
                          absl::Duration timeout) const override;
  ssize_t WriteWithTimeout(const char* buf,
                           size_t bufsize,
                           absl::Duration timeout) const override;
  int WriteString(absl::string_view message,
                  absl::Duration timeout) const override;

  // Returns the last error message. Valid when called just after
  // Write(), Read(), etc.
  string GetLastErrorMessage() const override;

  bool SetCloseOnExec() const;
  bool SetNonBlocking() const;
  bool SetReuseAddr() const;

#ifdef _WIN32
  SOCKET get() const { return fd_; }
  bool valid() const { return fd_ != INVALID_SOCKET; }
  SOCKET release() { SOCKET fd = fd_; fd_ = INVALID_SOCKET; return fd; }
#else
  int get() const { return fd_; }
  bool valid() const { return fd_ >= 0; }
  int release() { int fd = fd_; fd_ = -1; return fd; }
#endif

  void reset(int fd);
  // Returns true on success or already closed.
  bool Close();
  explicit operator int() const { return fd_; }
  void StreamWrite(std::ostream& os) const override {
    os << fd_;
  }

 private:
#ifdef _WIN32
  SOCKET fd_;
#else
  int fd_;
#endif

  DISALLOW_COPY_AND_ASSIGN(ScopedSocket);
};

}  // namespace devtools_goma

#endif  // DEVTOOLS_GOMA_LIB_SCOPED_FD_H_
