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
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.remote.proxy.FetchRecordRequest;
import com.google.devtools.build.lib.remote.proxy.FetchRecordResponse;
import com.google.devtools.build.lib.remote.proxy.RunRecord;
import com.google.devtools.build.lib.remote.proxy.RunRequest;
import com.google.devtools.build.lib.remote.proxy.RunResponse;
import com.google.devtools.build.lib.remote.proxy.RunResult;
import com.google.devtools.build.lib.remote.proxy.RunResult.Status;
import com.google.devtools.build.lib.remote.proxy.StatsRequest;
import com.google.devtools.build.lib.remote.proxy.StatsResponse;
import com.google.devtools.build.lib.remote.proxy.CommandServiceGrpc.CommandServiceImplBase;
import com.google.devtools.build.remote.client.AuthAndTLSOptions;
import com.google.devtools.build.remote.client.RecordingOutErr;
import com.google.devtools.build.remote.client.RemoteClient;
import com.google.devtools.build.remote.client.RemoteClientOptions.RunRemoteCommand;
import com.google.devtools.build.remote.client.RemoteOptions;
import com.google.devtools.build.remote.client.RemoteClientOptions;
import com.google.devtools.build.remote.client.RemoteRunner;
import com.google.devtools.build.remote.client.Stats;
import com.google.devtools.build.remote.client.util.Utils;
import io.grpc.StatusRuntimeException;
import io.grpc.Status.Code;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A basic implementation of a {@link CommandServiceImplBase} service. */
final class CommandServer extends CommandServiceImplBase {
  private final ListeningExecutorService executorService;
  private final RemoteClient client;
  private final RemoteProxyOptions proxyOptions;
  // TODO(olaola): clear stuff out from time to time.
  private final ConcurrentLinkedQueue<RunRecord.Builder> records =
      Queues.newConcurrentLinkedQueue();

  public CommandServer(RemoteProxyOptions proxyOptions, RemoteClient client) {
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

  @Override
  public void run(RunRequest req, StreamObserver<RunResponse> responseObserver) {
    Utils.vlog(client.verbosity(),3,"Received request:\n%s", req);
    RunRemoteCommand cmdOptions = new RunRemoteCommand();
    JCommander optionsParser =
        JCommander.newBuilder()
            .programName("remote_client")
            .addObject(new AuthAndTLSOptions()) // Parse, but ignore.
            .addObject(new RemoteOptions())
            .addObject(new RemoteClientOptions())
            .addCommand("run_remote", cmdOptions)
            .build();
    try {
      optionsParser.parse(req.getCommandList().toArray(new String[]{}));
    } catch (ParameterException e) {
      System.err.println("Unable to parse options: " + e.getLocalizedMessage());
      responseObserver.onNext(
          RunResponse.newBuilder()
              .setResult(
                  RunResult.newBuilder()
                      .setStatus(Status.LOCAL_ERROR)
                      .setExitCode(RemoteRunner.LOCAL_ERROR_EXIT_CODE)
                      .setMessage("Unable to parse options: " + e.getLocalizedMessage()))
              .build());
      responseObserver.onCompleted();
      return;
    }

    RecordingOutErr outErr = new RecordingOutErr();
    RunRecord.Builder record = client.newFromCommandOptions(cmdOptions);
    records.add(record);
    ListenableFuture<Void> future =
        executorService.submit(() -> {
          client.runRemote(cmdOptions, outErr, record, new String[]{});
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
                  record.getCommandParameters().getName(),
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

  @Override
  public void stats(StatsRequest req, StreamObserver<StatsResponse> responseObserver) {
    StatsResponse.Builder response = StatsResponse.newBuilder();
    if (req.getSummary()) {
      response.setProxyStats(Stats.computeStats(req, records));
    }
    if (req.getFull()) {
      long frameSize = 0;
      for (RunRecord.Builder rec : records) {
        if (!Stats.shouldCountRecord(rec, req)) {
          continue;
        }
        long recordSize = rec.build().getSerializedSize();
        if (frameSize + recordSize > 4 * 1024 * 1024 - 2000) {
          responseObserver.onNext(response.build());
          response.clear();
          frameSize = 0;
        }
        frameSize += recordSize;
        response.addRunRecords(rec);
      }
      if (response.getRunRecordsCount() > 0) {
        responseObserver.onNext(response.build());
      }
    } else {
      responseObserver.onNext(response.build());
    }
    responseObserver.onCompleted();
  }

  @Override
  public void fetchRecord(
      FetchRecordRequest req, StreamObserver<FetchRecordResponse> responseObserver) {
    for (RunRecord.Builder rec : records) {
      if (rec.getCommandParameters().getName().equals(req.getCommandId()) &&
          (req.getInvocationId().isEmpty() ||
              rec.getCommandParameters().getInvocationId().equals(req.getInvocationId()))) {
        responseObserver.onNext(FetchRecordResponse.newBuilder().setRecord(rec).build());
        responseObserver.onCompleted();
        return;
      }
    }
    responseObserver.onNext(FetchRecordResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }
}