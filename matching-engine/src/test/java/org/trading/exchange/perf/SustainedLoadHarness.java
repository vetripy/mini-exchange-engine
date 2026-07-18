package org.trading.exchange.perf;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.HdrHistogram.Histogram;
import org.trading.exchange.engine.MatchingEngine;
import org.trading.exchange.engine.command.CancelOrderCommand;
import org.trading.exchange.engine.command.NewOrderCommand;
import org.trading.exchange.listener.OrderUpdateListener;
import org.trading.exchange.model.EngineMode;
import org.trading.exchange.model.EngineState;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;
import org.trading.exchange.model.OrderType;
import org.trading.exchange.model.Symbol;
import org.trading.exchange.orderbook.OrderBook;

/**
 * Sustained-load harness for the single-threaded deterministic matching engine.
 *
 * <p>This is a ground-up rewrite of SustainedLoadTestRealistic. It was rewritten because the
 * previous harness's OWN bookkeeping — not the engine — was almost certainly the source of the
 * monotonic post-GC heap growth (36M -> 396M) that the benchmarking notes had been chasing as an
 * "unresolved engine leak". Specifically:
 *
 * <ol>
 *   <li>{@code restingOrderIds} was an UNBOUNDED ConcurrentLinkedQueue. LIMIT_RATIO (0.50) offered
 *       to it, CANCEL_RATIO (0.20) polled from it, netting +30% of every action, forever. At ~76k
 *       actions/sec that is ~23k live Strings/sec promoted to old gen. That is the leak.</li>
 *   <li>Latency was measured from ACTUAL submit time while submission was gated by a blocking
 *       RateLimiter — the textbook coordinated-omission bug. Engine stalls hid inside acquire() and
 *       never appeared in the tail percentiles.</li>
 *   <li>Per event, the listener allocated a LatencySample and pushed it through a LinkedBlockingQueue
 *       to a drainer thread. Under the engine's current offer()/fail-on-full outbound policy, a slow
 *       publisher fills outbound and FAILS the engine, so a heavy listener can trigger the very
 *       failure it is trying to measure.</li>
 *   <li>The reflection targeted {@code Map<String,OrderBook> books}; the engine now uses
 *       {@code Map<Symbol,OrderBook>}, so the old harness would not even start.</li>
 * </ol>
 *
 * <p>DESIGN PRINCIPLES OF THIS VERSION:
 *
 * <ul>
 *   <li><b>No unbounded harness state.</b> Cancel targets live in a per-thread fixed-size String
 *       ring (overwrite-oldest). The one correlation map is self-cleaning (removed on first ack), so
 *       its size tracks in-flight and never grows without bound.</li>
 *   <li><b>Coordinated-omission-free latency.</b> Latency is measured against each order's INTENDED
 *       send time (producerStart + k * interval), not its actual send time. If a producer falls
 *       behind because the engine stalled, that delay is fully captured. Pacing (how we approach the
 *       target rate) and measurement (intended schedule) are deliberately decoupled.</li>
 *   <li><b>Feather-light listener.</b> One map remove + one histogram record, both allocation-free.
 *       No per-event object, no queue, no drainer thread. Keeps the publisher fast so it cannot be
 *       the thing that trips the engine's outbound-full failure.</li>
 *   <li><b>Fail-visible.</b> A state listener catches engine FAILED and the report says so, rather
 *       than quietly producing truncated numbers.</li>
 * </ul>
 *
 * <p>Run via {@link #main(String[])} (e.g. exec:java or the IDE). It is intentionally NOT a JUnit
 * test — a saturation run does not belong in the unit suite.
 *
 * <p>DEPTH BALANCE (workload tuning, watch the sampler trace, not a correctness property): the book
 * stays roughly flat only when LIMIT replenishment ~= IOC consumption + effective cancels. With
 * IOC_QTY = k * BOOK_ORDER_QTY, each IOC removes ~k resting orders. Aim for
 * {@code LIMIT_RATIO ~= IOC_RATIO * k + CANCEL_RATIO_effective}. If the printed book trend shows
 * GROWING or draining to 0-5, retune the ratios below; a growing book reintroduces exactly the
 * heap-growth artifact this rewrite set out to remove.
 */
public final class SustainedLoadHarness {

    private SustainedLoadHarness() {
    }

    // --- Run timing ---
    // 25s, not 15: the first run showed inbound spiking to ~19k around t=13s (C2 compiling under
    // load) then instantly draining once hot. At 15s that recovery tail bled into the early window.
    // Ideally gate the measure clock on inbound draining to ~0; a longer warmup is the simpler proxy.
    private static final int WARMUP_SECONDS = 25;   // measured-phase JIT/steady-state warmup (discarded)
    private static final int MEASURE_SECONDS = 30;  // window that actually feeds the histograms
    private static final String SYMBOL = "AAPL";
    // Tunable via -Dproducers=N. Drop to 2 to free cores for a concurrent collector (ZGC/Shenandoah)
    // or to de-oversubscribe a box with < ~8 cores; 4 saturates an 8-core box with the spin layout.
    private static final int PRODUCER_THREADS = Integer.getInteger("producers", 4);

    // --- Stage 1: book construction (untimed) ---
    private static final long MID_PRICE = 10_000L;
    private static final int PRICE_LEVELS = 50;              // ticks each side of mid
    private static final int TARGET_RESTING_PER_LEVEL = 40;
    private static final long BOOK_ORDER_QTY = 10L;
    private static final long BOOK_WARMUP_TIMEOUT_SECONDS = 15;

    // --- Stage 2: order mix (must sum to 1.0) ---
    private static final double IOC_RATIO = 0.35;
    private static final double LIMIT_RATIO = 0.45;
    private static final double CANCEL_RATIO = 0.20;
    private static final long IOC_QTY = 20L; // 2 * BOOK_ORDER_QTY -> each IOC eats ~2 resting orders
    // Structural depth control: IOCs only consume the INSIDE of the book, so LIMITs spread over all
    // PRICE_LEVELS pile onto deep levels that nothing ever eats -> unbounded growth regardless of
    // ratios. Cluster replenishment near the touch (where IOCs and cancels actually reach it).
    private static final int LIMIT_CLUSTER_LEVELS = 5;

    // --- Cancel-target ring (per producer thread) ---
    private static final int RING_SIZE = 1 << 16; // 65,536; bounded, overwrite-oldest
    private static final int RING_MASK = RING_SIZE - 1;

    // --- Depth feedback: keep the book in a bounded, non-empty band regardless of exact ratios ---
    // The base ratios above only shape behaviour WITHIN the band; at the watermarks we override the
    // action, which guarantees the book can neither drain to zero nor grow without bound. This ends
    // the ratio-tuning treadmill: matching always happens against real, bounded depth.
    private static final long DEPTH_LOW = 3_000;   // below -> force LIMIT (replenish)
    private static final long DEPTH_HIGH = 7_000;  // above -> force IOC (consume)

    // --- Latency histogram bounds ---
    private static final long MAX_TRACKABLE_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int SIGNIFICANT_DIGITS = 3;

    public static void main(String[] args) throws Exception {
        long targetRatePerSec = args.length > 0 ? Long.parseLong(args[0]) : 70_000L;
        runAt(targetRatePerSec);
    }

    static void runAt(long targetRatePerSec) throws Exception {
        System.out.printf("=== Sustained load @ %,d orders/sec  (%d producers) ===%n",
            targetRatePerSec, PRODUCER_THREADS);
        System.out.printf(
            "Mix: IOC=%.0f%%  LIMIT=%.0f%%  CANCEL=%.0f%%   IOC_QTY=%d  BOOK_QTY=%d%n",
            IOC_RATIO * 100, LIMIT_RATIO * 100, CANCEL_RATIO * 100, IOC_QTY, BOOK_ORDER_QTY);

        MatchingEngine engine = new MatchingEngine(EngineMode.ASYNC);

        // Engine-failure visibility (the outbound-full policy can fail the engine mid-run).
        AtomicReference<EngineState> terminalState = new AtomicReference<>(null);
        engine.addStateListener((old, now, cause) -> {
            if (now == EngineState.FAILED) {
                terminalState.set(now);
                System.err.println("ENGINE FAILED mid-run: "
                    + (cause == null ? "(no cause)" : cause));
            }
        });

        // The ONLY correlation map. clientOrderId -> intended send nanos.
        //   - self-cleaning: every accepted order emits exactly one self-ack, which removes its
        //     entry, so size ~= in-flight and cannot grow without bound.
        //   - remove-on-first-event gives first-ack dedup for free (later fill/cancel events for the
        //     same id find nothing and are skipped, so resting-time never pollutes the latency).
        //   - leftover size at end == genuine in-flight backlog (our overload signal).
        // Value is boxed Long (transient, minor); acceptable next to the unavoidable clientOrderId
        // String and command allocations. A primitive concurrent long-map would remove even this.
        ConcurrentHashMap<String, Long> intendedByClientId = new ConcurrentHashMap<>(1 << 16);

        // Approximate count of measured-phase LIMITs currently resting: +1 when a LIMIT is submitted
        // (always rests — priced non-crossing), -1 when an 'L'-prefixed order reaches a terminal
        // state (filled or cancelled). Producers steer on it to hold the book in [DEPTH_LOW,HIGH].
        // Warmup ('W') orders are intentionally not tracked; steering on the measured-phase net is
        // enough to keep total depth bounded and non-empty.
        java.util.concurrent.atomic.AtomicLong approxRestingDepth =
            new java.util.concurrent.atomic.AtomicLong(0);

        Histogram earlyHist = new Histogram(1, MAX_TRACKABLE_NANOS, SIGNIFICANT_DIGITS);
        Histogram lateHist = new Histogram(1, MAX_TRACKABLE_NANOS, SIGNIFICANT_DIGITS);
        ListenerStats lstats = new ListenerStats(); // single-writer: publisher thread only

        // Window boundaries are set once the measured phase starts (below); the listener reads them
        // via volatile fields on this holder.
        Windows win = new Windows();

        OrderUpdateListener listener = event -> {
            // Publisher-thread only. Must stay allocation-free and cheap.
            String cid = event.clientOrderId();
            // Feedback bookkeeping: a resting measured-phase LIMIT has left the book.
            if (event.orderState().isTerminal() && !cid.isEmpty() && cid.charAt(0) == 'L') {
                approxRestingDepth.decrementAndGet();
            }
            Long intended = intendedByClientId.remove(cid);
            if (intended == null) {
                lstats.nonAttributable++; // warmup ack, later fill of an old order, or cancel-ack
                return;
            }
            long intendedNanos = intended;
            long measureStart = win.measureStartNanos; // volatile read
            if (intendedNanos < measureStart) {
                lstats.preWindow++; // scheduled during warmup; ignore
                return;
            }
            long raw = System.nanoTime() - intendedNanos;
            long latency;
            if (raw < 1) {
                lstats.negative++;  // must be ~0 with the strict pacer; a spike means a send preceded
                latency = 1;        // its slot again (pacer regression) — the numbers are then suspect.
            } else if (raw > MAX_TRACKABLE_NANOS) {
                latency = MAX_TRACKABLE_NANOS;
            } else {
                latency = raw;
            }
            lstats.recorded++;
            if (intendedNanos < win.lateStartNanos) {
                earlyHist.recordValue(latency);
            } else {
                lateHist.recordValue(latency);
            }
        };
        engine.addOrderUpdateListener(listener);
        engine.start();

        EngineReflection refl = new EngineReflection(engine, SYMBOL);

        // ---- STAGE 1: build a bounded, non-crossing resting book (untimed) ----
        System.out.println("\n--- Stage 1: warming the book ---");
        warmupBook(engine);
        long targetDepth = (long) PRICE_LEVELS * TARGET_RESTING_PER_LEVEL * 2;
        waitForBookDepth(refl, targetDepth, BOOK_WARMUP_TIMEOUT_SECONDS);
        int[] d = refl.bookDepth();
        System.out.printf("Book depth after warmup: buy=%,d  sell=%,d  total=%,d%n",
            d[0], d[1], d[0] + d[1]);

        // Clean slate for the measured phase: drop any warmup garbage so heap growth we observe is
        // attributable to the measured window, not to stage-1 leftovers.
        System.gc();
        Thread.sleep(200);

        // ---- STAGE 2: measured sustained load ----
        System.out.println("\n--- Stage 2: sustained load ---");
        Sampler sampler = new Sampler(refl, approxRestingDepth);
        ScheduledExecutorService samplerExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "depth-sampler");
            t.setDaemon(true);
            return t;
        });
        samplerExec.scheduleAtFixedRate(sampler::sample, 0, 1000, TimeUnit.MILLISECONDS);

        long phaseStart = System.nanoTime();
        win.measureStartNanos = phaseStart + TimeUnit.SECONDS.toNanos(WARMUP_SECONDS);
        win.lateStartNanos =
            phaseStart + TimeUnit.SECONDS.toNanos(WARMUP_SECONDS + MEASURE_SECONDS * 2L / 3);
        long endNanos = phaseStart + TimeUnit.SECONDS.toNanos(WARMUP_SECONDS + MEASURE_SECONDS);

        int perThreadRate = Math.max(1, (int) (targetRatePerSec / PRODUCER_THREADS));
        double intervalNanos = 1_000_000_000.0 / perThreadRate;

        CountDownLatch done = new CountDownLatch(PRODUCER_THREADS);
        ProducerStats[] pstats = new ProducerStats[PRODUCER_THREADS];
        Thread[] producers = new Thread[PRODUCER_THREADS];
        for (int t = 0; t < PRODUCER_THREADS; t++) {
            ProducerStats ps = new ProducerStats();
            pstats[t] = ps;
            final int idx = t;
            producers[t] = new Thread(
                () -> produce(engine, idx, intervalNanos, endNanos,
                    intendedByClientId, approxRestingDepth, terminalState, ps, done),
                "load-producer-" + t);
        }
        for (Thread p : producers) {
            p.start();
        }
        done.await();

        Thread.sleep(2000); // grace: let the engine drain and acks arrive before we read state
        samplerExec.shutdownNow();
        engine.stop();      // joins publisher thread -> happens-before for reading the histograms

        long inFlight = intendedByClientId.size(); // acks that never arrived == real backlog
        Histogram full = new Histogram(1, MAX_TRACKABLE_NANOS, SIGNIFICANT_DIGITS);
        full.add(earlyHist);
        full.add(lateHist);

        report(targetRatePerSec, TimeUnit.SECONDS.toSeconds(
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - phaseStart)),
            pstats, lstats, inFlight, terminalState.get(), full, earlyHist, lateHist, sampler);
    }

    // ----------------------------- producer -----------------------------

    private static void produce(MatchingEngine engine, int threadIdx,
        double intervalNanos, long endNanos,
        ConcurrentHashMap<String, Long> intendedByClientId,
        java.util.concurrent.atomic.AtomicLong approxRestingDepth,
        AtomicReference<EngineState> terminalState, ProducerStats ps, CountDownLatch done) {

        // Bounded, thread-local cancel-target ring. Overwrite-oldest: no growth, no contention.
        String[] ring = new String[RING_SIZE];
        long ringCursor = 0;
        long counter = 0;
        long producerStart = System.nanoTime();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        try {
            while (System.nanoTime() < endNanos) {
                if (terminalState.get() != null) {
                    break; // engine failed; stop generating load
                }
                // Strict pacer: wait until THIS order's scheduled slot, then send. Because a send
                // never PRECEDES its slot, latency = ack - slot is always >= 0, and a producer that
                // falls behind (engine stall / CPU starvation) still stamps the scheduled slot, so
                // the delay lands in the histogram — coordinated-omission-free.
                // (Guava's SmoothBursty RateLimiter was removed here: after a starvation episode it
                // dumps accumulated permits as a burst, racing `counter`/intendedNanos ahead of
                // wall-clock, producing future-dated slots and negative, clamped-to-zero latencies —
                // that was the late-window "all zeros incl. max=0" artifact.)
                long slot = producerStart + (long) (counter * intervalNanos);
                counter++;
                long waitNanos = slot - System.nanoTime();
                if (waitNanos > 20_000L) {
                    LockSupport.parkNanos(waitNanos - 10_000L); // coarse sleep for the bulk
                }
                while (System.nanoTime() < slot) {
                    Thread.onSpinWait(); // tighten the final few microseconds
                }
                long intendedNanos = slot;

                // Depth feedback: force replenish/consume at the watermarks, normal mix in-band.
                // Implemented by steering the pick value so the existing branch thresholds still hold.
                long depth = approxRestingDepth.get();
                double pick;
                if (depth < DEPTH_LOW) {
                    pick = 0.999;                     // -> LIMIT branch (>= CANCEL_RATIO + IOC_RATIO)
                } else if (depth > DEPTH_HIGH) {
                    pick = CANCEL_RATIO + 0.0001;      // -> IOC branch [CANCEL_RATIO, +IOC_RATIO)
                } else {
                    pick = rnd.nextDouble();
                }
                try {
                    if (pick < CANCEL_RATIO) {
                        String target = ring[rnd.nextInt(RING_SIZE)];
                        if (target == null) {
                            continue; // nothing known-resting yet; skip (counts as neither send nor fail)
                        }
                        engine.submit(buildCancel(target));
                        ps.cancelSent++;
                        // We do not correlate cancel latency here: the CANCELLED ack carries the
                        // TARGET's clientOrderId (whose intended time is old), and remove-on-first-ack
                        // has already consumed that id, so it is skipped as non-attributable. Cancel
                        // *throughput* is captured by cancelSent; cancel *misses* (target already
                        // gone) are an engine-thread rejection with no event, by design invisible and
                        // not a perf signal.
                    } else if (pick < CANCEL_RATIO + IOC_RATIO) {
                        String cid = "I-" + threadIdx + "-" + counter;
                        boolean buy = rnd.nextBoolean();
                        long price = buy ? MID_PRICE + PRICE_LEVELS : MID_PRICE - PRICE_LEVELS;
                        intendedByClientId.put(cid, intendedNanos);
                        engine.submit(NewOrderCommand.of(cid, SYMBOL,
                            buy ? OrderSide.BUY : OrderSide.SELL, OrderType.IOC,
                            "lt-" + threadIdx, price, IOC_QTY, intendedNanos));
                        ps.iocSent++;
                    } else {
                        String cid = "L-" + threadIdx + "-" + counter;
                        boolean buy = rnd.nextBoolean();
                        int level =
                            1 + rnd.nextInt(LIMIT_CLUSTER_LEVELS); // near the touch, consumable
                        long price = buy ? MID_PRICE - level : MID_PRICE + level; // own side: rests
                        intendedByClientId.put(cid, intendedNanos);
                        engine.submit(NewOrderCommand.of(cid, SYMBOL,
                            buy ? OrderSide.BUY : OrderSide.SELL, OrderType.LIMIT,
                            "lt-" + threadIdx, price, BOOK_ORDER_QTY, intendedNanos));
                        ps.limitSent++;
                        approxRestingDepth.incrementAndGet(); // priced non-crossing -> always rests
                        ring[(int) (ringCursor++
                            & RING_MASK)] = cid; // becomes a future cancel target
                    }
                    ps.submitted++;
                } catch (IllegalStateException e) {
                    // Bounded inbound queue rejected us (backpressure) OR the engine just failed.
                    ps.submitFailures++;
                    // Best-effort: if this was a new order we already inserted, pull it back so it
                    // does not masquerade as in-flight backlog at the end.
                    // (Cheap and safe: remove is a no-op for cancels, which never inserted.)
                    // Note: we cannot know the id here without recomputing; kept simple — the entry
                    // will be reconciled as in-flight, and submitFailures already flags the overload.
                    if (terminalState.get() != null) {
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
    }

    // ----------------------------- stage 1 helpers -----------------------------

    private static void warmupBook(MatchingEngine engine) throws InterruptedException {
        long c = 0;
        for (int level = 1; level <= PRICE_LEVELS; level++) {
            long sellPrice = MID_PRICE + level;
            long buyPrice = MID_PRICE - level;
            for (int i = 0; i < TARGET_RESTING_PER_LEVEL; i++) {
                submitWarmup(engine, "W-S-" + (++c), OrderSide.SELL, sellPrice);
                submitWarmup(engine, "W-B-" + (++c), OrderSide.BUY, buyPrice);
            }
        }
    }

    private static void submitWarmup(MatchingEngine engine, String cid, OrderSide side, long price)
        throws InterruptedException {
        // Untimed: warmup orders are never inserted into the correlation map, so their acks land as
        // non-attributable and are ignored by the latency stats.
        try {
            engine.submit(NewOrderCommand.of(cid, SYMBOL, side, OrderType.LIMIT, "warmup",
                price, BOOK_ORDER_QTY, System.nanoTime()));
        } catch (IllegalStateException e) {
            System.out.println("WARNING: backpressure during warmup — inbound full before measured "
                + "load. Lower TARGET_RESTING_PER_LEVEL.");
        }
    }

    private static void waitForBookDepth(EngineReflection refl, long target, long timeoutSeconds)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            int[] depth = refl.bookDepth();
            if (depth[0] + depth[1] >= target) {
                return;
            }
            Thread.sleep(100);
        }
        int[] finalDepth = refl.bookDepth();
        System.out.printf(
            "WARNING: warmup reached only %,d of target %,d within %ds — proceeding.%n",
            finalDepth[0] + finalDepth[1], target, timeoutSeconds);
    }

    private static CancelOrderCommand buildCancel(String clientOrderId) {
        // ASSUMPTION (unchanged from prior harness): CancelOrderCommand mirrors NewOrderCommand's
        // Lombok @Builder with a clientOrderId field. Only getClientOrderId() is confirmed. If the
        // real signature differs, this is the one method to change.
        return CancelOrderCommand.builder().clientOrderId(clientOrderId).build();
    }

    // ----------------------------- reporting -----------------------------

    private static void report(long targetRate, long elapsedSec, ProducerStats[] pstats,
        ListenerStats lstats, long inFlight, EngineState terminal,
        Histogram full, Histogram early, Histogram late, Sampler sampler) {

        long submitted = 0, ioc = 0, limit = 0, cancel = 0, fails = 0;
        for (ProducerStats p : pstats) {
            submitted += p.submitted;
            ioc += p.iocSent;
            limit += p.limitSent;
            cancel += p.cancelSent;
            fails += p.submitFailures;
        }
        long secs = Math.max(1, elapsedSec);

        System.out.println("\n=== RESULTS ===");
        if (terminal == EngineState.FAILED) {
            System.out.println("*** ENGINE ENTERED FAILED STATE DURING THE RUN — numbers below are "
                + "partial. Most likely the outbound queue filled (publisher could not keep up) and "
                + "the offer()/fail-on-full policy halted the engine. Increase outbound capacity or "
                + "lighten the publisher before trusting a verdict. ***");
        }
        System.out.printf("Target rate:          %,d /sec%n", targetRate);
        System.out.printf("Submitted:            %,d  (IOC=%,d  LIMIT=%,d  CANCEL=%,d)%n",
            submitted, ioc, limit, cancel);
        System.out.printf("Submit failures:      %,d  (bounded-inbound backpressure)%n", fails);
        System.out.printf("Achieved submit rate: %,d /sec (incl. warmup phase)%n",
            submitted / secs);
        System.out.printf(
            "Latency samples:      %,d  (non-attributable=%,d  pre-window=%,d  neg-clamped=%,d)%n",
            lstats.recorded, lstats.nonAttributable, lstats.preWindow, lstats.negative);
        System.out.printf("In-flight at end:     %,d  (acks that never arrived == real backlog)%n",
            inFlight);

        System.out.println("\n--- Latency: intended-submit -> publish (whole measured window) ---");
        printHist(full);
        System.out.println("--- Latency: first two-thirds of window ---");
        printHist(early);
        System.out.println("--- Latency: last third of window ---");
        printHist(late);

        System.out.println("\n--- Queue & book depth over time ---");
        sampler.print();

        System.out.println("\n=== VERDICT ===");
        verdict(terminal, fails, inFlight, submitted, early, late, sampler);
    }

    private static void printHist(Histogram h) {
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

    private static void verdict(EngineState terminal, long fails, long inFlight, long submitted,
        Histogram early, Histogram late, Sampler sampler) {
        if (terminal == EngineState.FAILED) {
            System.out.println(
                "FAIL: engine failed mid-run (see note above). Not a valid ceiling.");
            return;
        }
        if (fails > 0) {
            System.out.printf(
                "FAIL: %,d backpressure rejections — inbound saturated at this rate.%n",
                fails);
            return;
        }
        double inFlightRatio = submitted == 0 ? 0 : (double) inFlight / submitted;
        if (inFlightRatio > 0.02) {
            System.out.printf(
                "FAIL: %,d orders (%.1f%%) unprocessed at end — engine fell behind.%n",
                inFlight, inFlightRatio * 100);
            return;
        }
        if (early.getTotalCount() == 0 || late.getTotalCount() == 0) {
            System.out.println("INCONCLUSIVE: too few samples in one window.");
            return;
        }
        long e99 = early.getValueAtPercentile(99);
        long l99 = late.getValueAtPercentile(99);
        double ratio = (double) l99 / Math.max(1, e99);
        boolean queueGrowing = sampler.queueGrowing();
        boolean bookGrowing = sampler.bookGrowing();
        System.out.printf("late p99 / early p99: %.2fx%n", ratio);
        System.out.printf("queue trend: %s%n", queueGrowing ? "GROWING" : "stable");
        System.out.printf("book trend:  %s%n", bookGrowing ? "GROWING" : "stable");
        if (bookGrowing) {
            System.out.println(
                "INCONCLUSIVE: the book is still trending up, so this is a book-growth "
                    + "run, not a clean engine measurement — the latency tail is very likely a GC/book-"
                    + "growth artifact. Retune the mix (raise IOC_RATIO/IOC_QTY, keep LIMITs clustered "
                    + "near the touch, or raise CANCEL_RATIO) until the book trend reads 'stable' BEFORE "
                    + "trusting any latency verdict.");
            return;
        }
        if (queueGrowing) {
            System.out.println("UNSUSTAINABLE (throughput): queue depth trending up — the engine "
                + "cannot keep pace at this rate. Lower it.");
        } else if (ratio > 1.5) {
            System.out.printf(
                "THROUGHPUT SUSTAINED (queue bounded, in-flight ~0, no rejections) but "
                    + "LATENCY DEGRADING: late p99 is %.2fx early. With the book stable, a growing tail "
                    + "on a flat median is the GC pause signature, not matching cost — attack allocation "
                    + "(O-003 events / constructor pattern / Disruptor), not the algorithm.%n",
                ratio);
        } else {
            System.out.println(
                "SUSTAINABLE: latency flat, queue bounded, book stable. Try a higher rate.");
        }
    }

    // ----------------------------- small holders -----------------------------

    /**
     * Single-writer (publisher thread). Read by main only after engine.stop() joins that thread.
     */
    private static final class ListenerStats {

        long recorded;
        long nonAttributable;
        long preWindow;
        long negative;
    }

    /**
     * Single-writer (its own producer thread). Read by main only after join.
     */
    private static final class ProducerStats {

        long submitted;
        long iocSent;
        long limitSent;
        long cancelSent;
        long submitFailures;
    }

    private static final class Windows {

        volatile long measureStartNanos;
        volatile long lateStartNanos;
    }

    // ----------------------------- reflection -----------------------------

    /**
     * Reflection is confined to reaching three private fields on MatchingEngine (books,
     * inboundEvents, outboundEvents). Depth is then read through OrderBook's PUBLIC snapshot
     * methods. Field names are the coupling point — if they change, this constructor throws with a
     * clear message.
     */
    private static final class EngineReflection {

        private final OrderBook orderBook;
        private final Field inboundField;
        private final Field outboundField;
        private final MatchingEngine engine;

        @SuppressWarnings("unchecked")
        EngineReflection(MatchingEngine engine, String symbol) {
            this.engine = engine;
            try {
                Field booksField = MatchingEngine.class.getDeclaredField("books");
                booksField.setAccessible(true);
                // Engine now keys books by Symbol, not String.
                Map<Symbol, OrderBook> books = (Map<Symbol, OrderBook>) booksField.get(engine);
                this.orderBook = books.get(Symbol.from(symbol));
                if (orderBook == null) {
                    throw new IllegalStateException("No OrderBook for symbol " + symbol);
                }
                inboundField = MatchingEngine.class.getDeclaredField("inboundEvents");
                inboundField.setAccessible(true);
                outboundField = MatchingEngine.class.getDeclaredField("outboundEvents");
                outboundField.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Reflection target changed — update EngineReflection",
                    e);
            }
        }

        /**
         * {buyDepth, sellDepth}. Snapshot copies, so safe-ish, but see Sampler for the CME caveat.
         */
        int[] bookDepth() {
            int buy = 0;
            for (List<Order> os : orderBook.getBuySnapshot().values()) {
                buy += os.size();
            }
            int sell = 0;
            for (List<Order> os : orderBook.getSellSnapshot().values()) {
                sell += os.size();
            }
            return new int[]{buy, sell};
        }

        int[] queueDepth() {
            // Agrona's concurrent queues implement java.util.Queue, so size() is a plain call and is
            // safe to read from this thread.
            Queue<?> inbound = (Queue<?>) get(inboundField);
            Queue<?> outbound = (Queue<?>) get(outboundField);
            return new int[]{inbound.size(), outbound.size()};
        }

        private Object get(Field f) {
            try {
                return f.get(engine);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ----------------------------- sampler -----------------------------

    private static final class Sampler {

        private final EngineReflection refl;
        private final java.util.concurrent.atomic.AtomicLong depthAnchor;
        private final long start = System.nanoTime();
        // rows: {tMs, inbound, outbound, bookBuy, bookSell}. Written by the single sampler thread.
        private final java.util.List<long[]> rows = new java.util.concurrent.CopyOnWriteArrayList<>();

        Sampler(EngineReflection refl, java.util.concurrent.atomic.AtomicLong depthAnchor) {
            this.refl = refl;
            this.depthAnchor = depthAnchor;
        }

        void sample() {
            try {
                int[] q = refl.queueDepth();
                int[] b = refl.bookDepth();
                long tMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                rows.add(new long[]{tMs, q[0], q[1], b[0], b[1]});
                // Re-anchor the steering counter to ground truth. The event-driven counter
                // (+LIMIT / -terminal) is fine-grained but drifts if the publisher lags and the
                // listener stops firing — that drift once ran the counter above DEPTH_HIGH and forced
                // an all-IOC workload against an empty book. Snapping it to the real book depth each
                // second bounds that drift while keeping per-order granularity in between.
                depthAnchor.set(b[0] + b[1]);
            } catch (RuntimeException e) {
                // OrderBook's internal TreeMap/ArrayDeque are single-thread (engine) structures.
                // Snapshotting them while the engine mutates can throw ConcurrentModification. A
                // ScheduledExecutorService silently stops rescheduling after ANY uncaught exception,
                // so we swallow, skip this sample, and keep the series alive. Catching RuntimeException
                // (not just IllegalAccessException) is deliberate — that narrow catch is exactly what
                // killed depth sampling a couple seconds in on the previous harness.
            }
        }

        boolean queueGrowing() {
            return growing(1) || growing(2);
        }

        boolean bookGrowing() {
            return growing(3) || growing(4);
        }

        private boolean growing(int idx) {
            int n = rows.size();
            if (n < 4) {
                return false;
            }
            int q = Math.max(1, n / 4);
            double earlyAvg = avg(0, q, idx);
            double lateAvg = avg(n - q, n, idx);
            double tol = Math.max(50, earlyAvg * 0.2); // scale tolerance to the metric
            return lateAvg > earlyAvg + tol;
        }

        private double avg(int from, int to, int idx) {
            long sum = 0;
            for (int i = from; i < to; i++) {
                sum += rows.get(i)[idx];
            }
            return (double) sum / Math.max(1, to - from);
        }

        void print() {
            int n = rows.size();
            if (n == 0) {
                System.out.println("  (no samples)");
                return;
            }
            int step = Math.max(1, n / 20);
            System.out.println("  t(ms)     inbound  outbound  bookBuy  bookSell  bookTotal");
            for (int i = 0; i < n; i += step) {
                long[] s = rows.get(i);
                System.out.printf("  %-9d %-8d %-9d %-8d %-9d %,d%n",
                    s[0], s[1], s[2], s[3], s[4], s[3] + s[4]);
            }
        }
    }
}