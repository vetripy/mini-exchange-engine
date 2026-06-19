package org.trading.exchange.perf;

import com.google.common.util.concurrent.RateLimiter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.HdrHistogram.ConcurrentHistogram;
import org.junit.jupiter.api.Test;
import org.trading.exchange.engine.MatchingEngine;
import org.trading.exchange.engine.command.NewOrderCommand;
import org.trading.exchange.listener.OrderUpdateListener;
import org.trading.exchange.listener.TradeListener;
import org.trading.exchange.model.EngineMode;
import org.trading.exchange.model.OrderSide;
import org.trading.exchange.model.OrderType;

/**
 * Sustained-load saturation test using a real rate limiter and multiple producer threads.
 *
 * <p>WHY MULTI-THREADED: a single producer thread doing manual nanosleep timing cannot reliably
 * push high rates (200k-300k/sec) without its own scheduling jitter distorting the input rate —
 * you'd be measuring "how fast can one thread loop and sleep", not "what rate can the engine
 * sustain". Guava's {@link RateLimiter} handles the pacing correctly (token bucket, smooths
 * bursts), and spreading acquisition across N threads means the producer side has enough raw
 * throughput headroom that the engine's consumer side is the actual bottleneck under test — not
 * your test harness's single thread.
 *
 * <p>WHY THIS ANSWERS "CAN IT SUSTAIN X/SEC", UNLIKE A JMH BENCHMARK: JMH measures the cost of
 * one call to submit(); it says nothing about whether the engine thread + publisher thread can keep
 * draining the queue at that same rate over time. This test holds the input rate constant for a
 * sustained window and watches whether submit->first-event latency stays FLAT or CLIMBS. A climbing
 * trend means backlog is accumulating — the engine is not keeping pace, even if no queue has
 * overflowed yet within the test window. That climbing trend is the real signal, not a single
 * throughput number.
 *
 * <p>HOW TO USE: run with several target rates (e.g. 100_000, 200_000, 300_000) via the first
 * CLI arg. The highest rate where latency stays flat across the run and zero backpressure
 * rejections occur is your sustainable ceiling. Anything above that is "it accepted the orders but
 * is falling behind," which will eventually surface as queue overflow or unbounded latency if the
 * run continued.
 *
 * <p>ASSUMPTION FLAGGED: {@code TradeListener.onTrade(TradeEvent)} signature is inferred from
 * usage in MatchingEngine.publishDirect; it was not directly confirmed. If your actual interface
 * differs, fix the method reference below.
 */
public class SustainedLoadTestMultiThreaded {

    private static final int WARMUP_SECONDS = 5;
    private static final int RUN_SECONDS = 30;
    private static final String SYMBOL = "AAPL";
    private static final int PRODUCER_THREADS = 4;

    @Test
    void load200k() throws Exception {
        SustainedLoadTestMultiThreaded.runAt(200_000L);
    }

    public static void main(String[] args) throws Exception {
        long targetRatePerSec = args.length > 0 ? Long.parseLong(args[0]) : 200_000L;
        runAt(targetRatePerSec);
    }

    static void runAt(long targetRatePerSec) throws Exception {
        System.out.printf("=== Sustained load test @ %,d orders/sec (%d producer threads) ===%n",
            targetRatePerSec, PRODUCER_THREADS);

        MatchingEngine engine = new MatchingEngine(EngineMode.ASYNC);

        // Sized generously; this map should drain near-instantly relative to order arrival
        // rate. If it grows unbounded over the run, that itself is a saturation signal (see
        // "in-flight at end" in the report).
        ConcurrentHashMap<String, Long> submitTimestamps = new ConcurrentHashMap<>(500_000);

        ConcurrentHistogram fullRunLatency = newConcurrentHistogram();
        ConcurrentHistogram earlyWindowLatency = newConcurrentHistogram();
        ConcurrentHistogram lateWindowLatency = newConcurrentHistogram();

        AtomicLong eventsObserved = new AtomicLong();
        AtomicLong unmatchedEvents = new AtomicLong();
        AtomicLong submitFailures = new AtomicLong();
        AtomicLong submitted = new AtomicLong();

        long runStartNanos = System.nanoTime();
        long warmupEndNanos = runStartNanos + TimeUnit.SECONDS.toNanos(WARMUP_SECONDS);
        long lateWindowStartNanos =
            runStartNanos + TimeUnit.SECONDS.toNanos(WARMUP_SECONDS + (RUN_SECONDS * 2L / 3));

        LatencyRecorder recorder = new LatencyRecorder(submitTimestamps, eventsObserved,
            unmatchedEvents, fullRunLatency, earlyWindowLatency, lateWindowLatency,
            warmupEndNanos, lateWindowStartNanos);

        OrderUpdateListener orderListener = event -> recorder.recordFirstEvent(
            event.getClientOrderId());

        // ASSUMPTION: TradeListener#onTrade(TradeEvent) — see class javadoc.
        TradeListener tradeListener = event -> {
            recorder.recordFirstEvent(event.getBuyClientOrderId());
            recorder.recordFirstEvent(event.getSellClientOrderId());
        };

        engine.addOrderUpdateListener(orderListener);
        engine.addTradeListener(tradeListener);
        engine.start();

        QueueDepthSampler sampler = new QueueDepthSampler(engine);
        ScheduledExecutorService samplerExec = Executors.newSingleThreadScheduledExecutor();
        samplerExec.scheduleAtFixedRate(sampler::sample, 0, 500, TimeUnit.MILLISECONDS);

        long totalDurationSeconds = WARMUP_SECONDS + RUN_SECONDS;
        long endNanos = runStartNanos + TimeUnit.SECONDS.toNanos(totalDurationSeconds);

        // Each producer thread gets its own share of the target rate from a shared RateLimiter.
        // A SHARED limiter (not one-per-thread) is correct here: it caps the aggregate rate
        // across all threads to targetRatePerSec, which is what "can the engine handle X/sec"
        // actually means. If each thread had its own limiter at targetRatePerSec, total input
        // would be targetRatePerSec * PRODUCER_THREADS, not targetRatePerSec.
        RateLimiter limiter = RateLimiter.create(targetRatePerSec);

        CountDownLatch doneLatch = new CountDownLatch(PRODUCER_THREADS);
        Thread[] producers = new Thread[PRODUCER_THREADS];

        for (int t = 0; t < PRODUCER_THREADS; t++) {
            final int threadIdx = t;
            producers[t] = new Thread(() -> {
                long localCounter = 0;
                try {
                    while (System.nanoTime() < endNanos) {
                        limiter.acquire(); // blocks until permit available at target rate

                        localCounter++;
                        String clientOrderId = "LOAD-" + threadIdx + "-" + localCounter;
                        boolean isBuy = (localCounter % 2) == 0;
                        long price = 100_00 + (localCounter % 200);
                        long submitNanos = System.nanoTime();

                        NewOrderCommand cmd = NewOrderCommand.of(
                            clientOrderId,
                            SYMBOL,
                            isBuy ? OrderSide.BUY : OrderSide.SELL,
                            OrderType.LIMIT,
                            "load-test-" + threadIdx,
                            price,
                            10,
                            submitNanos);

                        // Timestamp captured BEFORE submit, on the calling thread — this is
                        // the moment that matters for caller-perceived latency.
                        submitTimestamps.put(clientOrderId, submitNanos);

                        try {
                            engine.submit(cmd);
                            submitted.incrementAndGet();
                        } catch (IllegalStateException e) {
                            // Hard backpressure signal: inbound queue stayed full past its
                            // offer timeout. This means the rate is provably unsustainable —
                            // not a soft signal like rising latency, an actual rejection.
                            submitFailures.incrementAndGet();
                            submitTimestamps.remove(clientOrderId);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "load-producer-" + t);
        }

        for (Thread p : producers) {
            p.start();
        }
        doneLatch.await();

        // Drain grace period: let in-flight orders finish producing events before measuring
        // tail state, otherwise "in-flight at end" overcounts orders that were simply still
        // queued at the moment producers stopped, not actually stuck.
        Thread.sleep(2000);
        samplerExec.shutdown();
        engine.stop();

        long actualElapsedSec =
            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - runStartNanos);

        printReport(targetRatePerSec, submitted.get(), submitFailures.get(),
            eventsObserved.get(), unmatchedEvents.get(), submitTimestamps.size(),
            actualElapsedSec, fullRunLatency, earlyWindowLatency, lateWindowLatency, sampler);
    }

    private static ConcurrentHistogram newConcurrentHistogram() {
        // Tracks 1us to ~10s range at 3 significant figures — plenty of resolution for
        // microsecond-scale queueing latency while bounding memory use.
        return new ConcurrentHistogram(1, TimeUnit.SECONDS.toNanos(10), 3);
    }

    private static void printReport(long targetRate, long submitted, long submitFailures,
        long eventsObserved, long unmatchedEvents, long inFlightAtEnd, long actualElapsedSec,
        ConcurrentHistogram fullRun, ConcurrentHistogram early, ConcurrentHistogram late,
        QueueDepthSampler sampler) {

        System.out.println();
        System.out.println("=== RESULTS ===");
        System.out.printf("Target rate:          %,d orders/sec%n", targetRate);
        System.out.printf("Submitted:             %,d%n", submitted);
        System.out.printf("Submit failures:       %,d  (backpressure rejections)%n",
            submitFailures);
        System.out.printf("Achieved submit rate:  %,d orders/sec%n",
            submitted / Math.max(1, actualElapsedSec));
        System.out.printf("First-events seen:     %,d%n", eventsObserved);
        System.out.printf("Unmatched events:      %,d  (should be ~0; nonzero = correlation "
            + "bug or cross-run leftovers)%n", unmatchedEvents);
        System.out.printf("In-flight at end:      %,d  (submitted but no first event seen — "
            + "growing with rate is itself a saturation signal)%n", inFlightAtEnd);

        System.out.println();
        System.out.println("--- Latency: submit -> first event (whole run, post-warmup) ---");
        printConcurrentHistogram(fullRun);

        System.out.println();
        System.out.println("--- Latency: first third of measurement window ---");
        printConcurrentHistogram(early);

        System.out.println();
        System.out.println("--- Latency: last third of measurement window ---");
        printConcurrentHistogram(late);

        System.out.println();
        System.out.println("--- Queue depth over time (inbound / outbound) ---");
        sampler.printSamples();

        System.out.println();
        System.out.println("=== VERDICT ===");
        evaluateVerdict(submitFailures, early, late, sampler);
    }

    private static void printConcurrentHistogram(ConcurrentHistogram h) {
        if (h.getTotalCount() == 0) {
            System.out.println("  (no samples)");
            return;
        }
        System.out.printf(
            "  count=%,d  p50=%,dus  p90=%,dus  p99=%,dus  p99.9=%,dus  max=%,dus%n",
            h.getTotalCount(),
            TimeUnit.NANOSECONDS.toMicros(h.getValueAtPercentile(50)),
            TimeUnit.NANOSECONDS.toMicros(h.getValueAtPercentile(90)),
            TimeUnit.NANOSECONDS.toMicros(h.getValueAtPercentile(99)),
            TimeUnit.NANOSECONDS.toMicros(h.getValueAtPercentile(99.9)),
            TimeUnit.NANOSECONDS.toMicros(h.getMaxValue()));
    }

    private static void evaluateVerdict(long submitFailures, ConcurrentHistogram early,
        ConcurrentHistogram late,
        QueueDepthSampler sampler) {

        if (submitFailures > 0) {
            System.out.println("FAIL: backpressure exceptions occurred — the inbound queue "
                + "could not absorb this rate even with its offer timeout. The engine cannot "
                + "sustain this rate as configured.");
            return;
        }

        if (early.getTotalCount() == 0 || late.getTotalCount() == 0) {
            System.out.println("INCONCLUSIVE: not enough samples in early/late windows. "
                + "Increase RUN_SECONDS or check listener wiring.");
            return;
        }

        long earlyP99 = early.getValueAtPercentile(99);
        long lateP99 = late.getValueAtPercentile(99);
        double growthRatio = (double) lateP99 / Math.max(1, earlyP99);
        boolean queueGrowing = sampler.isQueueDepthGrowing();

        System.out.printf("Late-window p99 / early-window p99 ratio: %.2fx%n", growthRatio);
        System.out.printf("Queue depth trend: %s%n", queueGrowing ? "GROWING" : "stable/bounded");

        if (growthRatio > 1.5 || queueGrowing) {
            System.out.println("LIKELY UNSUSTAINABLE: latency is climbing and/or queue depth "
                + "trended upward over the run. This rate is probably above the engine's "
                + "ceiling — try a lower rate.");
        } else {
            System.out.println("LIKELY SUSTAINABLE: latency stayed flat and queue depth stayed "
                + "bounded across the run. Try a higher rate to find the true ceiling.");
        }
    }

    /**
     * Correlates submit time -> first observed event for a given clientOrderId, recording into the
     * appropriate ConcurrentHistograms. Thread-safe: called concurrently from listener callbacks.
     */
    private static class LatencyRecorder {

        private final ConcurrentHashMap<String, Long> submitTimestamps;
        private final AtomicLong eventsObserved;
        private final AtomicLong unmatchedEvents;
        private final ConcurrentHistogram fullRun;
        private final ConcurrentHistogram early;
        private final ConcurrentHistogram late;
        private final long warmupEndNanos;
        private final long lateWindowStartNanos;

        LatencyRecorder(ConcurrentHashMap<String, Long> submitTimestamps,
            AtomicLong eventsObserved, AtomicLong unmatchedEvents, ConcurrentHistogram fullRun,
            ConcurrentHistogram early, ConcurrentHistogram late, long warmupEndNanos,
            long lateWindowStartNanos) {
            this.submitTimestamps = submitTimestamps;
            this.eventsObserved = eventsObserved;
            this.unmatchedEvents = unmatchedEvents;
            this.fullRun = fullRun;
            this.early = early;
            this.late = late;
            this.warmupEndNanos = warmupEndNanos;
            this.lateWindowStartNanos = lateWindowStartNanos;
        }

        void recordFirstEvent(String clientOrderId) {
            Long submitNanos = submitTimestamps.remove(clientOrderId);
            if (submitNanos == null) {
                // Already recorded by a racing callback for the same order (e.g. a TradeEvent
                // arriving for both legs), or — if this count is large — a real correlation
                // bug worth investigating, not just races.
                unmatchedEvents.incrementAndGet();
                return;
            }

            long latencyNanos = Math.max(0, System.nanoTime() - submitNanos);
            eventsObserved.incrementAndGet();

            if (submitNanos < warmupEndNanos) {
                return; // discard warmup-period samples entirely
            }

            // Synchronized because HdrConcurrentHistogram's ConcurrentHistogram (non-Concurrent variant) is not
            // thread-safe for concurrent recordValue calls, and these callbacks fire from the
            // publisher thread potentially overlapping with... actually only one publisher
            // thread exists in this engine, but producer threads don't call this — only the
            // listener (single publisher thread) does. No lock needed in practice, but kept
            // for safety against future multi-publisher changes.
            synchronized (fullRun) {
                fullRun.recordValue(latencyNanos);
            }
            if (submitNanos < lateWindowStartNanos) {
                synchronized (early) {
                    early.recordValue(latencyNanos);
                }
            } else {
                synchronized (late) {
                    late.recordValue(latencyNanos);
                }
            }
        }
    }

    /**
     * Samples private inbound/outbound queue sizes via reflection at fixed intervals, to observe
     * whether backlog trends upward over the run. Reflection is a deliberate trade-off to avoid
     * adding test-only accessors to production code; if you'd rather have first-class metrics,
     * replace this with real getters and delete this class.
     */
    private static class QueueDepthSampler {

        private final java.lang.reflect.Field inboundField;
        private final java.lang.reflect.Field outboundField;
        private final MatchingEngine engine;
        private final List<long[]> samples = new CopyOnWriteArrayList<>();
        private final long startNanos = System.nanoTime();

        QueueDepthSampler(MatchingEngine engine) {
            this.engine = engine;
            try {
                inboundField = MatchingEngine.class.getDeclaredField("inboundEvents");
                inboundField.setAccessible(true);
                outboundField = MatchingEngine.class.getDeclaredField("outboundEvents");
                outboundField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(
                    "Field name mismatch — update QueueDepthSampler to match MatchingEngine's "
                        + "actual field names", e);
            }
        }

        void sample() {
            try {
                java.util.Queue<?> inbound = (java.util.Queue<?>) inboundField.get(engine);
                java.util.Queue<?> outbound = (java.util.Queue<?>) outboundField.get(engine);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                samples.add(new long[]{elapsedMs, inbound.size(), outbound.size()});
            } catch (IllegalAccessException e) {
                // Best-effort metric; don't crash the run over a sampling failure.
            }
        }

        boolean isQueueDepthGrowing() {
            int n = samples.size();
            if (n < 4) {
                return false;
            }
            int quarter = Math.max(1, n / 4);
            double earlyInboundAvg = avg(samples.subList(0, quarter), 1);
            double lateInboundAvg = avg(samples.subList(n - quarter, n), 1);
            double earlyOutboundAvg = avg(samples.subList(0, quarter), 2);
            double lateOutboundAvg = avg(samples.subList(n - quarter, n), 2);

            // Tolerance margin avoids flagging normal jitter as "growing".
            boolean inboundGrowing = lateInboundAvg > earlyInboundAvg + 50;
            boolean outboundGrowing = lateOutboundAvg > earlyOutboundAvg + 50;
            return inboundGrowing || outboundGrowing;
        }

        private double avg(List<long[]> rows, int idx) {
            return rows.stream().mapToLong(r -> r[idx]).average().orElse(0);
        }

        void printSamples() {
            int n = samples.size();
            if (n == 0) {
                System.out.println("  (no samples captured)");
                return;
            }
            int step = Math.max(1, n / 20);
            for (int i = 0; i < n; i += step) {
                long[] s = samples.get(i);
                System.out.printf("  t=%,dms  inbound=%,d  outbound=%,d%n", s[0], s[1], s[2]);
            }
        }
    }
}