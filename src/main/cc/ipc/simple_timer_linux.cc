// Copyright 2012 Google Inc. All Rights Reserved.
// Author: arthurhsu@google.com (Arthur Hsu)

#include "simple_timer.h"

#include <cstdint>
#include <iostream>

#include "absl/time/time.h"

namespace devtools_goma {

absl::Duration SimpleTimer::GetDuration() const {
  return absl::Nanoseconds(GetInNanoSeconds());
}

void SimpleTimer::Start() {
  clock_gettime(CLOCK_MONOTONIC, &start_time_);
}

int64_t SimpleTimer::GetInNanoSeconds() const {
  struct timespec end_time;
  clock_gettime(CLOCK_MONOTONIC, &end_time);

  uint64_t end_time_int =
      end_time.tv_sec * 1000000000LL + end_time.tv_nsec;
  uint64_t start_time_int =
      start_time_.tv_sec * 1000000000LL + start_time_.tv_nsec;

  if (end_time_int < start_time_int) {
    // This shouldn't happen, but check.
    std::cerr << "SimpleTimer is not monotonic:"
              << " start_time=" << start_time_int
              << " end_time=" << end_time_int;
    return 0;
  }

  return static_cast<int64_t>(end_time_int - start_time_int);
}

}  // namespace devtools_goma
