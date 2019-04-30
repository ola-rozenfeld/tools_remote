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

import com.google.devtools.build.lib.remote.proxy.RunRecord;
import com.google.devtools.build.lib.remote.proxy.StatsRequest;
import com.google.devtools.build.lib.remote.proxy.StatsResponse;
import com.google.devtools.build.lib.remote.proxy.StatsGrpc.StatsImplBase;
import com.google.devtools.build.remote.client.Stats;
import io.grpc.stub.StreamObserver;

/** A basic implementation of a {@link StatsImplBase} service. */
final class StatServer extends StatsImplBase {
  private final Iterable<RunRecord.Builder> records;

  public StatServer(Iterable<RunRecord.Builder> records) {
    this.records = records;
  }

  @Override
  public void getStats(StatsRequest req, StreamObserver<StatsResponse> responseObserver) {
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
}
