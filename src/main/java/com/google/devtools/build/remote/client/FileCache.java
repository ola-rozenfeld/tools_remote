// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.remote.client;

import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.devtools.build.remote.client.util.DigestUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

/**
 * An in-memory cache to ensure we do I/O for source files only once during a single build.
 *
 * <p>Simply maintains a cached mapping from filename to metadata that may be populated only once.
 */
public class FileCache {
  private final DigestUtil digestUtil;

  public FileCache(DigestUtil digestUtil) {
    this.digestUtil = digestUtil;
  }

  private final Cache<String, FileMetadata> pathToMetadata =
      CacheBuilder.newBuilder()
          // We default to 10 disk read threads, but we don't expect them all to edit the map
          // simultaneously.
          .concurrencyLevel(8)
          // Even small-ish builds, as of 11/21/2011 typically have over 10k artifacts, so it's
          // unlikely that this default will adversely affect memory in most cases.
          .initialCapacity(10000)
          .build();

  public FileMetadata getMetadata(File file) throws IOException {
    try {
      return pathToMetadata.get(file.toString(), () -> new FileMetadata(file, digestUtil));
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      throw new IllegalStateException("Unexpected cache loading error", e); // Should never happen.
    }
  }
}
