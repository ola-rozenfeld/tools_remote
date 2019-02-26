// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.remote.client.proxy;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.PathConverter;
import java.nio.file.Path;
import java.util.List;

/** Options for remote cache/execution proxy server. */
@Parameters(separators = "=")
public final class RemoteProxyOptions {
  @Parameter(
      names = "--listen_port",
      description = "Listening port for the netty server.")
  public int listenPort = 8080;

  @Parameter(
      names = "--jobs",
      description = "The maximum number of concurrent jobs to run.")
  //TODO(olaola): implement a good default for "auto" based on system capacity.
  public int jobs = 100;
}
