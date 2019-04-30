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
package com.google.devtools.build.remote.client;

import build.bazel.remote.execution.v2.ExecutedActionMetadata;
import com.google.devtools.build.lib.remote.proxy.ActionMetadata;
import com.google.devtools.build.lib.remote.proxy.ActionStats;
import com.google.devtools.build.lib.remote.proxy.LocalExecutionStats;
import com.google.devtools.build.lib.remote.proxy.LocalTimestamps;
import com.google.devtools.build.lib.remote.proxy.ProxyStats;
import com.google.devtools.build.lib.remote.proxy.RemoteExecutionStats;
import com.google.devtools.build.lib.remote.proxy.RunRecord;
import com.google.devtools.build.lib.remote.proxy.RunRecord.Stage;
import com.google.devtools.build.lib.remote.proxy.RunResult;
import com.google.devtools.build.lib.remote.proxy.RunResult.Status;
import com.google.devtools.build.lib.remote.proxy.StatsRequest;
import com.google.devtools.build.lib.remote.proxy.Stat;
import com.google.devtools.build.lib.remote.proxy.Stat.Outlier;
import com.google.common.math.Quantiles;
import com.google.common.math.Quantiles;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Utils to compute run time statistics on actions run by the proxy.*/
public class Stats {
  // Collect statistics with a few outliers.
  static class Statistic {
    private final ArrayList<Long> values = new ArrayList<>();
    private Outlier outlier1 = null, outlier2 = null;
    private final boolean computeTotal;
    private long total = 0;

    public Statistic() {
      this(true);
    }

    public Statistic(boolean computeTotal) {
      this.computeTotal = computeTotal;
    }

    public long getCount() {
      return values.size();
    }

    public void add(String id, long value) {
      values.add(value);
      if (computeTotal) {
        total += value;
      }
      Outlier cur = Outlier.newBuilder().setId(id).setValue(value).build();
      if (outlier1 == null || value > outlier1.getValue()) {
        outlier2 = outlier1;
        outlier1 = cur;
        return;
      }
      if (outlier2 == null || value > outlier2.getValue()) {
        outlier2 = cur;
      }
    }

    public Stat getStat() {
      if (values.isEmpty()) {
        return Stat.getDefaultInstance();
      }
      Stat.Builder result = Stat.newBuilder()
          .setMedian(Quantiles.median().compute(values))
          .setPercentile75(Quantiles.percentiles().index(75).compute(values))
          .setPercentile85(Quantiles.percentiles().index(85).compute(values))
          .setPercentile95(Quantiles.percentiles().index(95).compute(values))
          .setTotal(total);
      if (outlier1 != null) {
        result.addOutliers(outlier1);
      }
      if (outlier2 != null) {
        result.addOutliers(outlier2);
      }
      return result.build();
    }
  }

  static class TimeInterval {
    private final Timestamp from;
    private final Timestamp to;

    public Timestamp getFrom() {
      return from;
    }

    public Timestamp getTo() {
      return to;
    }

    public TimeInterval(Timestamp from, Timestamp to) {
      this.from = from;
      this.to = to;
    }

    public static final Comparator<TimeInterval> COMPARATOR =
        new Comparator<TimeInterval>() {
          @Override
          public int compare(TimeInterval i1, TimeInterval i2) {
            return Timestamps.compare(i1.getFrom(), i2.getFrom());
          }
        };
  }

  static class DurationStatistic extends Statistic {
    private final ArrayList<TimeInterval> intervals = new ArrayList<>();
    public void add(String id, Timestamp from, Timestamp to) {
      intervals.add(new TimeInterval(from, to));
      add(id, Durations.toMillis(Timestamps.between(from, to)));
    }

    private long computeParallelTimeMs() {
      List<TimeInterval> sortedIntervals = intervals.stream()
              .sorted(TimeInterval.COMPARATOR)
              .collect(Collectors.toList());
      Timestamp curStart = sortedIntervals.get(0).getFrom();
      Timestamp curEnd = sortedIntervals.get(0).getTo();
      long result = 0;
      for (TimeInterval i : intervals) {
        if (Timestamps.compare(curEnd, i.getFrom()) < 0) {
          // End of interval.
          result += Durations.toMillis(Timestamps.between(curStart, curEnd));
          curStart = i.getFrom();
        }
        if (Timestamps.compare(curEnd, i.getTo()) < 0) {
          curEnd = i.getTo();
        }
      }
      result += Durations.toMillis(Timestamps.between(curStart, curEnd));
      return result;
    }

    @Override
    public Stat getStat() {
      Stat stat = super.getStat();
      if (getCount() == 0) {
        return stat;
      }
      return stat.toBuilder().setTotalParallelTimeMs(computeParallelTimeMs()).build();
    }
  }

  static class ActionStatistics {
    private final Statistic numInputsStats = new Statistic(false);
    private final Statistic totalInputBytesStats = new Statistic(false);
    private final Statistic numOutputsStats = new Statistic();
    private final Statistic totalOutputBytesStats = new Statistic();

    public void addDataPoint(String id, ActionMetadata meta) {
      numInputsStats.add(id, meta.getNumInputs());
      totalInputBytesStats.add(id, meta.getTotalInputBytes());
      numOutputsStats.add(id, meta.getNumOutputs());
      totalOutputBytesStats.add(id, meta.getTotalOutputBytes());
    }

    public long getCount() {
      return numInputsStats.getCount();
    }

    public ActionStats getStats() {
      return ActionStats.newBuilder()
          .setNumInputs(numInputsStats.getStat())
          .setTotalInputBytes(totalInputBytesStats.getStat())
          .setNumOutputs(numOutputsStats.getStat())
          .setTotalOutputBytes(totalOutputBytesStats.getStat())
          .build();
    }
  }

  static class RemoteExecutionStatistics {
    private final DurationStatistic queuedStats = new DurationStatistic();
    private final DurationStatistic workerStats = new DurationStatistic();
    private final DurationStatistic inputFetchStats = new DurationStatistic();
    private final DurationStatistic executionStats = new DurationStatistic();
    private final DurationStatistic outputUploadStats = new DurationStatistic();

    public void addDataPoint(String id, ExecutedActionMetadata meta) {
      queuedStats.add(id, meta.getQueuedTimestamp(), meta.getWorkerStartTimestamp());
      workerStats.add(id, meta.getWorkerStartTimestamp(), meta.getWorkerCompletedTimestamp());
      inputFetchStats.add(
          id, meta.getInputFetchStartTimestamp(), meta.getInputFetchCompletedTimestamp());
      executionStats.add(
          id, meta.getExecutionStartTimestamp(), meta.getExecutionCompletedTimestamp());
      outputUploadStats.add(
          id, meta.getOutputUploadStartTimestamp(), meta.getOutputUploadCompletedTimestamp());
    }

    public long getCount() {
      return queuedStats.getCount();
    }

    public RemoteExecutionStats getStats() {
      return RemoteExecutionStats.newBuilder()
          .setQueued(queuedStats.getStat())
          .setWorker(workerStats.getStat())
          .setInputFetch(inputFetchStats.getStat())
          .setExecution(executionStats.getStat())
          .setOutputUpload(outputUploadStats.getStat())
          .build();
    }
  }

  static class LocalStatistics {
    private final DurationStatistic queuedStats = new DurationStatistic();
    private final DurationStatistic inputTreeStats = new DurationStatistic();
    private final DurationStatistic checkActionCacheStats = new DurationStatistic();
    private final DurationStatistic uploadInputsStats = new DurationStatistic();
    private final DurationStatistic executeStats = new DurationStatistic();
    private final DurationStatistic downloadOutputsStats = new DurationStatistic();
    private final DurationStatistic totalLocalStats = new DurationStatistic();

    public void addDataPoint(String id, LocalTimestamps ts) {
      if (ts.hasQueuedEnd()) {
        queuedStats.add(id, ts.getQueuedStart(), ts.getQueuedEnd());
      }
      if (ts.hasInputTreeEnd()) {
        inputTreeStats.add(id, ts.getInputTreeStart(), ts.getInputTreeEnd());
      }
      checkActionCacheStats.add(id, ts.getCheckActionCacheStart(), ts.getCheckActionCacheEnd());
      if (ts.hasUploadInputsEnd()) {
        uploadInputsStats.add(id, ts.getUploadInputsStart(), ts.getUploadInputsEnd());
      }
      if (ts.hasExecuteEnd()) {
        executeStats.add(id, ts.getExecuteStart(), ts.getExecuteEnd());
      }
      if (ts.hasDownloadOutputsEnd()) {
        downloadOutputsStats.add(id, ts.getDownloadOutputsStart(), ts.getDownloadOutputsEnd());
        totalLocalStats.add(id, ts.getQueuedStart(), ts.getDownloadOutputsEnd());
      }
    }

    public long getCount() {
      return queuedStats.getCount();
    }

    public LocalExecutionStats getStats() {
      return LocalExecutionStats.newBuilder()
          .setQueued(queuedStats.getStat())
          .setComputeInputTree(inputTreeStats.getStat())
          .setCheckActionCache(checkActionCacheStats.getStat())
          .setUploadInputs(uploadInputsStats.getStat())
          .setExecute(executeStats.getStat())
          .setDownloadOutputs(downloadOutputsStats.getStat())
          .setTotalLocal(totalLocalStats.getStat())
          .build();
    }
  }

  public static boolean shouldCountRecord(RunRecord.Builder rec, StatsRequest req) {
    if (!req.getInvocationId().isEmpty() &&
        !req.getInvocationId().equals(rec.getCommandParameters().getInvocationId())) {
      return false;
    }
    if (!req.getCommandId().isEmpty() &&
        !req.getCommandId().equals(rec.getCommandParameters().getId())) {
      return false;
    }
    LocalTimestamps ts = rec.getLocalTimestamps();
    if (req.hasFromTs() && ts.hasQueuedStart() &&
        Timestamps.compare(req.getFromTs(), ts.getQueuedStart()) > 0) {
      return false;
    }
    if (req.hasToTs() && (!ts.hasDownloadOutputsEnd() ||
        Timestamps.compare(req.getToTs(), ts.getDownloadOutputsEnd()) < 0)) {
      return false;
    }
    if (req.getStatus() != Status.UNKNOWN && rec.getResult().getStatus() != req.getStatus()) {
      return false;
    }
    return true;
  }

  public static ProxyStats computeStats(StatsRequest req, Iterable<RunRecord.Builder> records) {
    ProxyStats.Builder proxyStats = ProxyStats.newBuilder();
    // I don't care it's asynchronous and we won't be too exact here!
    int[] recordsByStage = new int[10];
    int[] finishedByStatus = new int[8];
    long numInputs = 0;
    int afterAcCall = 0;
    Timestamp startTs = null, endTs = null;
    ActionStatistics actionStats = new ActionStatistics();
    RemoteExecutionStatistics remoteStats = new RemoteExecutionStatistics();
    LocalStatistics localStats = new LocalStatistics();
    for (RunRecord.Builder rec : records) {
      if (!shouldCountRecord(rec, req)) {
        continue;
      }
      if (rec.hasResultBeforeLocalFallback()) {
        proxyStats.setLocalFallbackTotal(proxyStats.getLocalFallbackTotal() + 1);
      }
      Stage stage = rec.getStage();
      recordsByStage[stage.getNumber()]++;
      if (stage.getNumber() >= stage.UPLOADING_INPUTS.getNumber()) { // Stages are in order.
        afterAcCall++;
      }
      String id = rec.getCommandParameters().getId();
      if (rec.hasRemoteMetadata()) {
        remoteStats.addDataPoint(id, rec.getRemoteMetadata());
      }
      if (rec.hasLocalTimestamps()) {
        LocalTimestamps ts = rec.getLocalTimestamps();
        localStats.addDataPoint(id, ts);
        if (startTs == null ||
            (ts.hasQueuedStart() && Timestamps.compare(startTs, ts.getQueuedStart()) > 0)) {
          startTs = ts.getQueuedStart();
        }
        if (endTs == null ||
            (ts.hasDownloadOutputsEnd() &&
                Timestamps.compare(endTs, ts.getDownloadOutputsEnd()) < 0)) {
          endTs = ts.getDownloadOutputsEnd();
        }
      }
      if (rec.getStage() != Stage.FINISHED) {
        continue;
      }
      ActionMetadata meta = rec.getActionMetadata();  // It always exists at this point.
      numInputs += meta.getNumInputs();
      proxyStats.setCasCacheMisses(proxyStats.getCasCacheMisses() + meta.getCasCacheMisses());
      actionStats.addDataPoint(id, meta);
      finishedByStatus[rec.getResult().getStatus().getNumber()]++;
    }
    if (numInputs > 0) {
      proxyStats.setCasCacheHitRatio(
          (numInputs - proxyStats.getCasCacheMisses()) / (float)numInputs);
    }
    if (afterAcCall > 0) {
      proxyStats.setAcCacheHitRatio(
          finishedByStatus[Status.CACHE_HIT.getNumber()] / (float)afterAcCall);
    }
    if (remoteStats.getCount() > 0) {
      proxyStats.setRemoteExecutionStats(remoteStats.getStats());
    }
    if (localStats.getCount() > 0) {
      proxyStats.setLocalExecutionStats(localStats.getStats());
    }
    if (actionStats.getCount() > 0) {
      proxyStats.setActionStats(actionStats.getStats());
    }
    for (int i = 0; i < recordsByStage.length; ++i) {
      if (recordsByStage[i] > 0) {
        proxyStats.addRecordsByStageBuilder().setStage(Stage.valueOf(i))
            .setCount(recordsByStage[i]);
      }
    }
    for (int i = 0; i < finishedByStatus.length; ++i) {
      if (finishedByStatus[i] > 0) {
        proxyStats.addFinishedByStatusBuilder().setStatus(Status.valueOf(i))
            .setCount(finishedByStatus[i]);
      }
    }
    if (startTs != null) {
      proxyStats.setStartTs(startTs);
    }
    if (endTs != null) {
      proxyStats.setEndTs(endTs);
    }
    return proxyStats.build();
  }
}
