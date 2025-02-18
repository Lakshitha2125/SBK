/**
 * Copyright (c) KMG. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.sbk.api.impl;

import io.sbk.api.Benchmark;
import io.sbk.api.Callback;
import io.sbk.api.ParameterOptions;
import io.sbk.api.Worker;
import io.sbk.config.PerlConfig;
import io.sbk.data.DataType;
import io.sbk.perl.SendChannel;
import io.sbk.time.Time;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SBK Callback reader implementation.
 * Deprecated
 * This interface is replaced by Abstract class AbstractCallbackReader and SbkReader.
 */
final public class SbkCallbackReader extends Worker implements Callback<Object>, Benchmark {
    final private DataType<Object> dataType;
    final private Time time;
    final private CompletableFuture<Void> ret;
    final private Callback<Object> callback;
    final private AtomicLong readCnt;
    final private double msToRun;
    final private long totalRecords;
    private long beginTime;

    public SbkCallbackReader(int readerId, int idMax, ParameterOptions params, SendChannel sendChannel,
                             DataType<Object> dataType, Time time) {
        super(readerId, idMax, params, sendChannel);
        this.dataType = dataType;
        this.time = time;
        this.ret = new CompletableFuture<>();
        this.readCnt = new AtomicLong(0);
        this.beginTime = 0;
        this.msToRun = params.getTotalSecondsToRun() * PerlConfig.MS_PER_SEC;
        this.totalRecords = params.getTotalRecords();

        if (params.isWriteAndRead()) {
            callback = this::consumeRW;
        } else {
            callback = this::consumeRead;
        }
    }

    @Override
    public CompletableFuture<Void> start() {
        this.beginTime = time.getCurrentTime();
        return ret;
    }

    @Override
    public void stop() {
        ret.complete(null);
    }


    @Override
    public void record(long startTime, long endTime, int dataSize, int events) {
        final long cnt = readCnt.incrementAndGet();
        final int id = (int) (cnt % recordIDMax);
        sendChannel.send(id, startTime, endTime, dataSize, events);
        if (this.msToRun > 0 && (time.elapsedMilliSeconds(endTime, beginTime) >= this.msToRun)) {
            ret.complete(null);
        } else if (this.totalRecords > cnt) {
            ret.complete(null);
        }
    }

    @Override
    public void consume(Object data) {
        callback.consume(data);
    }

    private void consumeRead(Object data) {
        final long endTime = time.getCurrentTime();
        record(endTime, endTime, dataType.length(data), 1);
    }

    private void consumeRW(Object data) {
        record(dataType.getTime(data), time.getCurrentTime(), dataType.length(data), 1);
    }
}
