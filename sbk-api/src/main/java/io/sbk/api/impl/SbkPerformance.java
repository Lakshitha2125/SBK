/**
 * Copyright (c) KMG. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.sbk.api.impl;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.LockSupport;
import java.nio.file.Files;
import java.nio.file.Paths;

import io.sbk.api.Config;
import io.sbk.api.Logger;
import io.sbk.api.Performance;
import io.sbk.api.SendChannel;
import io.sbk.api.ResultLogger;
import io.sbk.api.TimeStamp;
import io.sbk.api.Channel;
import lombok.Synchronized;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;


/**
 * Class for Performance statistics.
 */
final public class SbkPerformance implements Performance {
    final private String csvFile;
    final private int windowInterval;
    final private int idleNS;
    final private int baseLatency;
    final private int maxWindowLatency;
    final private int maxLatency;
    final private ResultLogger resultLogger;
    final private ExecutorService executor;
    final private Channel[] channels;
    final private double[] percentiles;

    @GuardedBy("this")
    private int index;

    @GuardedBy("this")
    private CompletableFuture<Void> retFuture;

    public SbkPerformance(Config config, int workers, String csvFile,
                          ResultLogger periodicLogger, ExecutorService executor) {
        this.idleNS = Math.max(Config.MIN_IDLE_NS, config.idleNS);
        this.baseLatency = Math.max(Config.DEFAULT_MIN_LATENCY, config.minLatency);
        this.windowInterval = Math.max(Config.MIN_REPORTING_INTERVAL_MS, config.reportingMS);
        this.maxWindowLatency = Math.min(Integer.MAX_VALUE,
                                    Math.max(config.maxWindowLatency, Config.DEFAULT_WINDOW_LATENCY));
        this.maxLatency = Math.min(Integer.MAX_VALUE,
                                    Math.max(config.maxLatency, Config.DEFAULT_MAX_LATENCY));
        this.csvFile = csvFile;
        this.resultLogger = periodicLogger;
        this.executor = executor;
        this.retFuture = null;
        int maxQs;
        if (config.maxQs > 0) {
            maxQs = config.maxQs;
            this.channels = new CQueueChannel[1];
            this.index = 1;
        } else {
            maxQs =  Math.max(Config.MIN_Q_PER_WORKER, config.qPerWorker);
            this.channels = new CQueueChannel[workers];
            this.index = workers;
        }
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new CQueueChannel(maxQs);
        }

        this.percentiles = new double[Config.PERCENTILES.length];
        for (int i = 0; i < this.percentiles.length; i++) {
            this.percentiles[i] = Config.PERCENTILES[i] / 100.0;
        }

    }

    /**
     * Private class for start and end time.
     */
    final private class QueueProcessor implements Runnable {
        final private long startTime;
        final private long msToRun;
        final private long totalRecords;
        final private double[] percentiles;

        private QueueProcessor(long startTime, int secondsToRun, int records, double[] percentiles) {
            this.startTime = startTime;
            this.msToRun = secondsToRun * Config.MS_PER_SEC;
            this.totalRecords = records;
            this.percentiles = percentiles;
        }

        public void run() {
            final TimeWindow window;
            final LatencyWindow latencyWindow;
            boolean doWork = true;
            long time = startTime;
            long recordsCnt = 0;
            boolean notFound;
            TimeStamp t;

            if (csvFile != null) {
                try {
                    latencyWindow = new CSVLatencyWriter(baseLatency, maxLatency, percentiles, startTime,
                            csvFile, Config.timeUnitToString(Config.TIME_UNIT));
                } catch (IOException ex) {
                    ex.printStackTrace();
                    return;
                }
            } else {
                latencyWindow = new LatencyWindow(baseLatency, maxLatency, percentiles, startTime);
            }
            window = new TimeWindow(baseLatency, maxWindowLatency, percentiles, startTime, windowInterval, idleNS);
            while (doWork) {
                notFound = true;
                for (Channel ch : channels) {
                    t = ch.receive(windowInterval);
                    if (t != null) {
                        notFound = false;
                        time = t.endTime;
                        if (t.isEnd()) {
                            doWork = false;
                            break;
                        } else {
                            recordsCnt += t.records;
                            final int latency = (int) (t.endTime - t.startTime);
                            window.record(t.startTime, t.bytes, t.records, latency);
                            latencyWindow.record(t.startTime, t.bytes, t.records, latency);
                            if (totalRecords > 0  && recordsCnt >= totalRecords) {
                                doWork = false;
                                break;
                            }
                        }
                        if (window.elapsedTimeMS(time) > windowInterval) {
                            window.print(time, resultLogger);
                            window.reset(time);
                        }
                    }
                }
                if (notFound) {
                    time = window.idleWaitPrint(time, resultLogger);
                }
                if ( msToRun > 0 && (time - startTime) >= msToRun) {
                    doWork = false;
                }
            }
            latencyWindow.print(time, resultLogger::printTotal);
        }
    }

    /**
     * Private class for counter implementation to reduce System.currentTimeMillis() invocation.
     */
    @NotThreadSafe
    final static private class ElasticCounter {
        final private int windowInterval;
        final private int idleNS;
        final private double countRatio;
        final private long minIdleCount;
        private long elasticCount;
        private long idleCount;
        private long totalCount;

        private ElasticCounter(int windowInterval, int idleNS) {
            this.windowInterval = windowInterval;
            this.idleNS = idleNS;
            double minWaitTimeMS = windowInterval / 50.0;
            countRatio = (Config.NS_PER_MS * 1.0) / idleNS;
            minIdleCount = (long) (countRatio * minWaitTimeMS);
            elasticCount = minIdleCount;
            idleCount = 0;
            totalCount = 0;
        }

        private boolean waitCheck() {
            LockSupport.parkNanos(idleNS);
            idleCount++;
            totalCount++;
            return idleCount > elasticCount;
        }

        private void reset() {
            idleCount = 0;
        }

        private void updateElastic(long diffTime) {
            elasticCount = Math.max((long) (countRatio * (windowInterval - diffTime)), minIdleCount);
        }

        private void setElastic(long diffTime) {
            elasticCount =  (totalCount * windowInterval) / diffTime;
            totalCount = 0;
        }
    }


    /**
     *  class for Performance statistics.
     */
    @NotThreadSafe
    static private class LatencyRecorder {
        public long validLatencyRecords;
        public long lowerLatencyDiscardRecords;
        public long higherLatencyDiscardRecords;
        public long bytes;
        public long totalLatency;
        public int maxLatency;
        final private int baseLatency;
        final private long[] latencies;

        LatencyRecorder(int baseLatency, int latencyThreshold) {
            this.baseLatency = baseLatency;
            this.latencies = new long[latencyThreshold-baseLatency];
            reset();
        }

        public void reset() {
            this.validLatencyRecords = 0;
            this.lowerLatencyDiscardRecords = 0;
            this.higherLatencyDiscardRecords = 0;
            this.bytes = 0;
            this.maxLatency = 0;
            this.totalLatency = 0;
        }


        public int[] getPercentiles(final double[] percentiles) {
            final int[] values = new int[percentiles.length];
            final long[] percentileIds = new long[percentiles.length];
            long cur = 0;
            int index = 0;

            for (int i = 0; i < percentileIds.length; i++) {
                percentileIds[i] = (long) (validLatencyRecords * percentiles[i]);
            }

            for (int i = 0; i < Math.min(latencies.length, this.maxLatency+1); i++) {
                if (latencies[i] > 0) {
                    while (index < values.length) {
                        if (percentileIds[index] >= cur && percentileIds[index] < (cur + latencies[i])) {
                            values[index] = i + baseLatency;
                            index += 1;
                        } else {
                            break;
                        }
                    }
                    cur += latencies[i];
                    latencies[i] = 0;
                }
            }
            return values;
        }

        /**
         * Record the latency
         *
         * @param bytes number of bytes.
         * @param events number of events(records).
         * @param latency latency value in milliseconds.
         */
        public void record(int bytes, int events, int latency) {
            if (latency < this.baseLatency) {
                this.lowerLatencyDiscardRecords += events;
            } else {
                final int index = latency - this.baseLatency;
                if (index < this.latencies.length) {
                    this.latencies[index] += events;
                    this.validLatencyRecords += events;
                } else {
                    this.higherLatencyDiscardRecords += events;
                }
            }
            this.bytes += bytes;
            this.totalLatency += latency * events;
            this.maxLatency = Math.max(this.maxLatency, latency);
        }
    }

    /**
     * Private class for Performance statistics within a given time window.
     */
    @NotThreadSafe
    static private class LatencyWindow extends LatencyRecorder {
        final private double[] percentiles;
        private long startTime;

        LatencyWindow(int baseLatency, int latencyThreshold, double[] percentiles, long start) {
            super(baseLatency, latencyThreshold);
            this.startTime = start;
            this.percentiles = percentiles;
        }

        public void reset(long start) {
            reset();
            this.startTime = start;
        }

        /**
         * Record the latency
         *
         * @param startTime start time.
         * @param bytes number of bytes.
         * @param events number of events(records).
         * @param latency latency value in milliseconds.
         */
        public void record(long startTime, int bytes, int events, int latency) {
            record(bytes, events, latency);
        }

        /**
         * Get the current time duration of this window
         *
         * @param time current time.
         */
        public long elapsedTimeMS(long time) {
            return time - startTime;
        }


        /**
         * Print the window statistics
         */
        public void print(long endTime, Logger logger) {
            final double elapsedSec = (endTime - startTime) / 1000.0;
            final long totalRecords  = this.validLatencyRecords +
                    this.lowerLatencyDiscardRecords + this.higherLatencyDiscardRecords;
            final double recsPerSec = totalRecords / elapsedSec;
            final double mbPerSec = (this.bytes / (1024.0 * 1024.0)) / elapsedSec;
            int[] pecs = getPercentiles(percentiles);

            logger.print(this.bytes, totalRecords, recsPerSec, mbPerSec,
                    this.totalLatency / (double) totalRecords, this.maxLatency,
                    this.lowerLatencyDiscardRecords, this.higherLatencyDiscardRecords,
                    pecs);
        }
    }

    @NotThreadSafe
    final static private class TimeWindow extends LatencyWindow {
        final private ElasticCounter idleCounter;
        final private int windowInterval;

        private TimeWindow(int baseLatency, int latencyThreshold, double[] percentiles, long start, int interval, int idleNS) {
            super(baseLatency, latencyThreshold, percentiles, start);
            this.idleCounter = new ElasticCounter(interval, idleNS);
            this.windowInterval = interval;
        }

        @Override
        public void reset(long start) {
            super.reset(start);
            this.idleCounter.reset();
        }

        private long waitCheckPrint(ElasticCounter counter, long time, ResultLogger logger) {
            if (counter.waitCheck()) {
                time = System.currentTimeMillis();
                final long diffTime = elapsedTimeMS(time);
                if (diffTime > windowInterval) {
                    print(time, logger);
                    reset(time);
                    counter.setElastic(diffTime);
                } else {
                    counter.updateElastic(diffTime);
                }
            }
            return time;
        }

        private long  idleWaitPrint(long currentTime, ResultLogger logger) {
                return waitCheckPrint(idleCounter, currentTime, logger);
        }
    }


    @NotThreadSafe
    static private class CSVLatencyWriter extends LatencyWindow {
        final private String csvFile;
        final private CSVPrinter csvPrinter;

        CSVLatencyWriter(int baseLatency, int latencyThreshold, double[] percentiles, long start,
                         String csvFile, String unitString) throws IOException {
            super(baseLatency, latencyThreshold, percentiles, start);
            this.csvFile = csvFile;
            csvPrinter = new CSVPrinter(Files.newBufferedWriter(Paths.get(csvFile)), CSVFormat.DEFAULT
                    .withHeader("Start Time (" + unitString + ")", "data size (bytes)", "Records", " Latency (" + unitString + ")"));
        }

        private void readCSV() {
            try {
                CSVParser csvParser = new CSVParser(Files.newBufferedReader(Paths.get(csvFile)), CSVFormat.DEFAULT
                        .withFirstRecordAsHeader().withIgnoreHeaderCase().withTrim());

                for (CSVRecord csvEntry : csvParser) {
                    super.record(Long.parseLong(csvEntry.get(0)), Integer.parseInt(csvEntry.get(1)),
                            Integer.parseInt(csvEntry.get(2)), Integer.parseInt(csvEntry.get(3)));
                }
                csvParser.close();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public void record(long startTime, int bytes, int events, int latency) {
            try {
                csvPrinter.printRecord(startTime, bytes, events, latency);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public void print(long endTime, Logger logger) {
            try {
                csvPrinter.close();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            readCSV();
            super.print(endTime, logger);
        }
    }

    @NotThreadSafe
    static final class CQueueChannel implements Channel {
        final private ConcurrentLinkedQueue<TimeStamp>[] cQueues;
        private int index;

        public CQueueChannel(int qSize) {
            this.index = qSize;
            this.cQueues = new ConcurrentLinkedQueue[qSize];
            for (int i = 0; i < cQueues.length; i++) {
                cQueues[i] = new ConcurrentLinkedQueue<>();
            }
        }

        public TimeStamp receive(int timeout) {
            index += 1;
            if (index >= cQueues.length) {
                index = 0;
            }
            return cQueues[index].poll();
        }

        public void sendEndTime(long endTime) {
            cQueues[0].add(new TimeStamp(endTime));
        }

        public void clear() {
            for (ConcurrentLinkedQueue<TimeStamp> q: cQueues) {
                q.clear();
            }
        }

        /* This Method is Thread Safe */
        @Override
        public void send(int id, long startTime, long endTime, int bytes, int records) {
            cQueues[id].add(new TimeStamp(startTime, endTime, bytes, records));
        }
    }

    @Override
    @Synchronized
    public SendChannel get() {
        if (channels.length == 1) {
                return channels[0];
        }
        index += 1;
        if (index >= channels.length) {
            index = 0;
        }
        return  channels[index];
    }


    @Override
    @Synchronized
    public CompletableFuture<Void> start(long startTime, int secondsToRun, int records) {
        if (this.retFuture == null) {
            this.retFuture = CompletableFuture.runAsync(new QueueProcessor(startTime, secondsToRun,
                    records, percentiles),
                    executor);
        }
        return this.retFuture;
    }

    @Override
    @Synchronized
    public void stop(long endTime)  {
        if (this.retFuture != null) {
            if (!this.retFuture.isDone()) {
                for (Channel ch : channels) {
                    ch.sendEndTime(endTime);
                }
                try {
                    retFuture.get();
                } catch (ExecutionException | InterruptedException ex) {
                    ex.printStackTrace();
                }
                for (Channel ch : channels) {
                    ch.clear();
                }
            }
            this.retFuture = null;
        }
    }
}