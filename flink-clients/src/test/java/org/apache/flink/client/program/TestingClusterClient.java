/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.client.program;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.execution.CheckpointType;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.runtime.jobmaster.JobResult;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.operators.coordination.CoordinationRequest;
import org.apache.flink.runtime.operators.coordination.CoordinationResponse;
import org.apache.flink.runtime.rest.messages.TriggerId;
import org.apache.flink.streaming.api.graph.ExecutionPlan;
import org.apache.flink.util.function.QuadFunction;
import org.apache.flink.util.function.TriFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Settable implementation of ClusterClient used for testing. */
public class TestingClusterClient<T> implements ClusterClient<T> {

    private Function<JobID, CompletableFuture<Acknowledge>> cancelFunction =
            ignore -> CompletableFuture.completedFuture(Acknowledge.get());
    private TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
            cancelWithSavepointFunction =
                    (ignore, savepointPath, formatType) ->
                            CompletableFuture.completedFuture(savepointPath);
    private QuadFunction<JobID, Boolean, String, SavepointFormatType, CompletableFuture<String>>
            stopWithSavepointFunction =
                    (ignore1, ignore2, savepointPath, formatType) ->
                            CompletableFuture.completedFuture(savepointPath);
    private QuadFunction<JobID, Boolean, String, SavepointFormatType, CompletableFuture<String>>
            stopWithDetachedSavepointFunction =
                    (ignore1, ignore2, savepointPath, formatType) ->
                            CompletableFuture.completedFuture(new TriggerId().toString());

    private TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
            triggerSavepointFunction =
                    (ignore, savepointPath, formatType) ->
                            CompletableFuture.completedFuture(savepointPath);
    private TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
            triggerDetachedSavepointFunction =
                    (ignore, savepointPath, formatType) ->
                            CompletableFuture.completedFuture(new TriggerId().toString());

    private BiFunction<JobID, CheckpointType, CompletableFuture<Long>> triggerCheckpointFunction =
            (ignore, checkpointType) -> CompletableFuture.completedFuture(1L);

    public void setCancelFunction(Function<JobID, CompletableFuture<Acknowledge>> cancelFunction) {
        this.cancelFunction = cancelFunction;
    }

    public void setCancelWithSavepointFunction(
            TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
                    cancelWithSavepointFunction) {
        this.cancelWithSavepointFunction = cancelWithSavepointFunction;
    }

    public void setStopWithSavepointFunction(
            QuadFunction<JobID, Boolean, String, SavepointFormatType, CompletableFuture<String>>
                    stopWithSavepointFunction) {
        this.stopWithSavepointFunction = stopWithSavepointFunction;
    }

    public void setStopWithDetachedSavepointFunction(
            QuadFunction<JobID, Boolean, String, SavepointFormatType, CompletableFuture<String>>
                    stopWithDetachedSavepointFunction) {
        this.stopWithDetachedSavepointFunction = stopWithDetachedSavepointFunction;
    }

    public void setTriggerSavepointFunction(
            TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
                    triggerSavepointFunction) {
        this.triggerSavepointFunction = triggerSavepointFunction;
    }

    public void setTriggerDetachedSavepointFunction(
            TriFunction<JobID, String, SavepointFormatType, CompletableFuture<String>>
                    triggerDetachedSavepointFunction) {
        this.triggerDetachedSavepointFunction = triggerDetachedSavepointFunction;
    }

    public void setTriggerCheckpointFunction(
            BiFunction<JobID, CheckpointType, CompletableFuture<Long>> triggerCheckpointFunction) {
        this.triggerCheckpointFunction = triggerCheckpointFunction;
    }

    @Override
    public T getClusterId() {
        return (T) "test-cluster";
    }

    @Override
    public Configuration getFlinkConfiguration() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void shutDownCluster() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getWebInterfaceURL() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Collection<JobStatusMessage>> listJobs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Acknowledge> disposeSavepoint(String savepointPath) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<JobID> submitJob(@Nonnull ExecutionPlan executionPlan) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<JobStatus> getJobStatus(JobID jobId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<JobResult> requestJobResult(@Nonnull JobID jobId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Map<String, Object>> getAccumulators(JobID jobID, ClassLoader loader) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Acknowledge> cancel(JobID jobId) {
        return cancelFunction.apply(jobId);
    }

    @Override
    public CompletableFuture<String> cancelWithSavepoint(
            JobID jobId, @Nullable String savepointDirectory, SavepointFormatType formatType) {
        return cancelWithSavepointFunction.apply(jobId, savepointDirectory, formatType);
    }

    @Override
    public CompletableFuture<String> stopWithSavepoint(
            JobID jobId,
            boolean advanceToEndOfEventTime,
            @Nullable String savepointDirectory,
            SavepointFormatType formatType) {
        return stopWithSavepointFunction.apply(
                jobId, advanceToEndOfEventTime, savepointDirectory, formatType);
    }

    @Override
    public CompletableFuture<String> stopWithDetachedSavepoint(
            JobID jobId,
            boolean advanceToEndOfEventTime,
            @org.jetbrains.annotations.Nullable String savepointDirectory,
            SavepointFormatType formatType) {
        return stopWithDetachedSavepointFunction.apply(
                jobId, advanceToEndOfEventTime, savepointDirectory, formatType);
    }

    @Override
    public CompletableFuture<String> triggerSavepoint(
            JobID jobId, @Nullable String savepointDirectory, SavepointFormatType formatType) {
        return triggerSavepointFunction.apply(jobId, savepointDirectory, formatType);
    }

    @Override
    public CompletableFuture<Long> triggerCheckpoint(JobID jobId, CheckpointType checkpointType) {
        return triggerCheckpointFunction.apply(jobId, checkpointType);
    }

    @Override
    public CompletableFuture<String> triggerDetachedSavepoint(
            JobID jobId,
            @org.jetbrains.annotations.Nullable String savepointDirectory,
            SavepointFormatType formatType) {
        return triggerDetachedSavepointFunction.apply(jobId, savepointDirectory, formatType);
    }

    @Override
    public CompletableFuture<CoordinationResponse> sendCoordinationRequest(
            JobID jobId, String operatorUid, CoordinationRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
}
