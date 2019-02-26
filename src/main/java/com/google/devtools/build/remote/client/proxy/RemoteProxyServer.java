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

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.google.devtools.build.remote.client.AuthAndTLSOptions;
import com.google.devtools.build.remote.client.RemoteClient;
import com.google.devtools.build.remote.client.RemoteClientOptions;
import com.google.devtools.build.remote.client.RemoteOptions;
import com.google.devtools.build.remote.client.util.Utils;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;

import com.google.devtools.build.lib.remote.proxy.StatsResponse;
import com.google.devtools.build.lib.remote.proxy.RunRecord;
import com.google.protobuf.TextFormat;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.nio.file.Paths;

/**
 * Implements a remote worker that accepts work items as protobufs. The server implementation is
 * based on gRPC.
 */
public final class RemoteProxyServer {

  private static void tmp() throws Exception {
    StatsResponse.Builder builder = StatsResponse.newBuilder();
    try (FileInputStream fin = new FileInputStream(Paths.get("/usr/local/google/home/olaola/rbe_full_stats_tmp").toFile())) {
      TextFormat.getParser().merge(new InputStreamReader(fin), builder);
    }
    StatsResponse resp = builder.build();
    System.out.println("<<<<<<<<<<<<<<<<<<<<<<<< response \n" + resp.getProxyStats());
    for (RunRecord record : resp.getRunRecordsList()) {
      if (record.getCommandParameters().getName().equals("7392513426613147441")) {
//        System.out.println("<<<<<<<<<<<<<<<<<<<<<<<< record \n" + printCommand(record) + "\n");
        break;
      }
    }
  }

  public static void main(String[] args) throws Exception {
    /*tmp();
    if (true) {
      return;
    }*/
    AuthAndTLSOptions authAndTlsOptions = new AuthAndTLSOptions();
    RemoteProxyOptions proxyOptions = new RemoteProxyOptions();
    RemoteOptions remoteOptions = new RemoteOptions();
    RemoteClientOptions remoteClientOptions = new RemoteClientOptions();

    JCommander optionsParser =
        JCommander.newBuilder()
            .programName("remote_client_proxy")
            .addObject(proxyOptions)
            .addObject(remoteOptions)
            .addObject(remoteClientOptions)
            .addObject(authAndTlsOptions)
            .build();

    try {
      optionsParser.parse(args);
    } catch (ParameterException e) {
      System.err.println("Unable to parse options: " + e.getLocalizedMessage());
      optionsParser.usage();
      System.exit(1);
    }

    RemoteClient client = new RemoteClient(remoteOptions, remoteClientOptions, authAndTlsOptions);
    Server server = NettyServerBuilder.forPort(proxyOptions.listenPort)
        .addService(new CommandServer(proxyOptions, client)).build();
    Utils.vlog(
        client.verbosity(),
        1,
        "Starting gRPC server on port %s...",
        proxyOptions.listenPort);
    server.start();
    server.awaitTermination();
  }
}