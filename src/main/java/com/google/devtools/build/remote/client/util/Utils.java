// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.remote.client.util;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.remote.client.util.Clock;
import com.google.protobuf.Timestamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/** Utility methods for the remote package. * */
public class Utils {

  private Utils() {}

  /**
   * Returns the result of a {@link ListenableFuture} if successful, or throws any checked {@link
   * Exception} directly if it's an {@link IOException} or else wraps it in an {@link IOException}.
   */
  public static <T> T getFromFuture(ListenableFuture<T> f)
      throws IOException, InterruptedException {
    try {
      return f.get();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new IOException(e.getCause());
    }
  }

  public static String localTimeAsString() {
    return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  }

  public static void vlog(int verbosity, int level, String fmt, Object... args) {
    if (verbosity >= level) {
      System.out.println(localTimeAsString() + " " + String.format(fmt, args));
    }
  }

  /** Returns whether a path/file has owner executable permission.*/
  public static boolean isExecutable(Path path) throws IOException {
    return Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_EXECUTE);
  }

  /** Sets owner executable permission depending on isExecutable. */
  public static void setExecutable(Path path, boolean isExecutable) throws IOException {
    // setExecutable doesn't work on Windows, nor is it required.
    if (System.getProperty("os.name").startsWith("Windows")) {
      return;
    }
    Set<PosixFilePermission> originalPerms = Files.getPosixFilePermissions(path); // Immutable.
    Set<PosixFilePermission> perms = EnumSet.copyOf(originalPerms);
    if (!isExecutable) {
      perms.remove(PosixFilePermission.OWNER_EXECUTE);
    } else {
      perms.add(PosixFilePermission.OWNER_EXECUTE);
    }
    Files.setPosixFilePermissions(path, perms);
  }

  /** Get current time as a Timestamp. */
  public static Timestamp getCurrentTimestamp(Clock clock) {
    Instant time = Instant.ofEpochMilli(clock.currentTimeMillis());
    return Timestamp.newBuilder()
        .setSeconds(time.getEpochSecond())
        .setNanos(time.getNano())
        .build();
  }
}
