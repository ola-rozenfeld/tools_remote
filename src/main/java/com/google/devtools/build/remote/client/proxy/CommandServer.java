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

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.remote.commands.RunResult.Status;
import com.google.devtools.build.lib.remote.commands.RunRequest;
import com.google.devtools.build.lib.remote.commands.RunResponse;
import com.google.devtools.build.lib.remote.commands.CommandsGrpc.CommandsImplBase;
import com.google.devtools.build.lib.remote.stats.RunRecord;
import com.google.devtools.build.remote.client.RecordingOutErr;
import com.google.devtools.build.remote.client.RemoteClient;
import com.google.devtools.build.remote.client.RemoteRunner;
import com.google.devtools.build.remote.client.util.Utils;
import io.grpc.StatusRuntimeException;
import io.grpc.Status.Code;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A basic implementation of a {@link CommandsImplBase} service. */
final class CommandServer extends CommandsImplBase {
  private final ListeningExecutorService executorService;
  private final RemoteClient client;
  private final RemoteProxyOptions proxyOptions;
  // TODO(olaola): clear stuff out from time to time.
  private final ConcurrentLinkedQueue<RunRecord.Builder> records;

  public CommandServer(
      RemoteProxyOptions proxyOptions,
      RemoteClient client,
      ConcurrentLinkedQueue<RunRecord.Builder> records) {
    this.records = records;
    this.proxyOptions = proxyOptions;
    this.client = client;
    ThreadPoolExecutor realExecutor =
        new ThreadPoolExecutor(
            // This is actually the max number of concurrent jobs.
            proxyOptions.jobs,
            // Since we use an unbounded queue, the executor ignores this value, but it still checks
            // that it is greater or equal to the value above.
            proxyOptions.jobs,
            // Shut down idle threads after one minute. Threads aren't all that expensive, but we
            // also
            // don't need to keep them around if we don't need them.
            1,
            TimeUnit.MINUTES,
            // We use an unbounded queue for now.
            // TODO(olaola): We need to reject work eventually.
            new LinkedBlockingQueue<>(),
            new ThreadFactoryBuilder().setNameFormat("subprocess-handler-%d").build());
    // Allow the core threads to die.
    realExecutor.allowCoreThreadTimeOut(true);
    this.executorService = MoreExecutors.listeningDecorator(realExecutor);
  }

  private void addRecord(RunRecord.Builder record) {
    records.add(record);
    if (records.size() > proxyOptions.statsRecords) {
      // Repeat the condition in order to reduce the size of the critical section.
      synchronized (this) {
        while (records.size() > proxyOptions.statsRecords) records.remove();
      }
    }
  }

  @Override
  public void runCommand(RunRequest req, StreamObserver<RunResponse> responseObserver) {
    Utils.vlog(client.verbosity(), 3, "Received request:\n%s", req);
    RecordingOutErr outErr = new RecordingOutErr();
    RunRecord.Builder record = client.newFromCommand(req.getCommand());
    addRecord(record);
    ListenableFuture<Void> future =
        executorService.submit(() -> {
          client.runRemote(record, outErr);
          return null;
        });
    future.addListener(
        () -> {
          try {
            // TODO(olaola): chunk the stdout/err!! Better yet, send as they appear.
            responseObserver.onNext(
                RunResponse.newBuilder()
                    .setStdout(outErr.outAsLatin1())
                    .setStderr(outErr.errAsLatin1())
                    .setResult(record.getResult())
                    .build());
            responseObserver.onCompleted();
            Status status = record.getResult().getStatus();
            if (RemoteRunner.isFailureStatus(status)) {
              Utils.vlog(
                  client.verbosity(),
                  1,
                  "%s> Command failed: status %s, exit code %d, message %s",
                  record.getCommand().getLabels().getCommandId(),
                  status,
                  record.getResult().getExitCode(),
                  record.getResult().getMessage());
            }
          } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Code.CANCELLED) {
              System.err.println("Request canceled by client");
            } else {
              System.err.println("Error connecting to client: " + e);
            }
          }
        },
        MoreExecutors.directExecutor());
  }
}
