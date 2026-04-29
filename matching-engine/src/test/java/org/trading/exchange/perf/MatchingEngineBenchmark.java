package org.trading.exchange.perf;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.trading.exchange.engine.MatchingEngine;
import org.trading.exchange.engine.command.CancelOrderCommand;
import org.trading.exchange.engine.command.NewOrderCommand;
import org.trading.exchange.model.EngineMode;
import org.trading.exchange.model.OrderSide;
import org.trading.exchange.model.OrderType;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH Benchmarks for MatchingEngine.
 */
@Slf4j
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {"-XX:+UseG1GC", "-Xms2g", "-Xmx2g", // fixed heap to avoid GC
        // skew
        "-XX:+AlwaysPreTouch"})
public class MatchingEngineBenchmark {

    //
    // // -----------------------------------------------------------------------
    // // Shared state: one engine per fork, reset between iterations
    // // -----------------------------------------------------------------------
    //
    // private static final String[] SYMBOLS = {"AAPL", "GOOGL", "MSFT", "AMZN"};
    //
    private static NewOrderCommand buildBuyLimit(String clientId, String symbol, long price,
            long qty) {
        return NewOrderCommand.builder().clientOrderId(clientId).userId("user-bench").symbol(symbol)
                .side(OrderSide.BUY).type(OrderType.LIMIT).price(price).quantity(qty).build();
    }

    //
    private static NewOrderCommand buildSellLimit(String clientId, String symbol, long price,
            long qty) {
        return NewOrderCommand.builder().clientOrderId(clientId).userId("user-bench").symbol(symbol)
                .side(OrderSide.SELL).type(OrderType.LIMIT).price(price).quantity(qty).build();
    }

    @Benchmark
    public void newOrder_heavyBook_crossingSell(LoadedBookState s) throws InterruptedException {
        s.engine.submit(buildSellLimit(UUID.randomUUID().toString(), "AAPL", 100_00, 100_000));
    }

    // Scenario A: crossing a small portion of the book
    @Benchmark
    public void heavyBook_partialCross(LoadedBookState s) throws InterruptedException {
        s.engine.submit(buildSellLimit(UUID.randomUUID().toString(), "AAPL", 100_00, 100)); // crosses
                                                                                            // 100
                                                                                            // units
    }

    // Scenario B: crossing the entire book (all 100k units)
    @Benchmark
    public void heavyBook_fullCross(LoadedBookState s) throws InterruptedException {
        s.engine.submit(buildSellLimit(UUID.randomUUID().toString(), "AAPL", 100_00, 100_000)); // crosses
                                                                                                // all
    }

    @Benchmark
    public void deepBook_crossMultipleLevels(DeepBookState s) throws InterruptedException {
        // Crosses through 1000 price levels at once
        s.engine.submit(buildSellLimit(UUID.randomUUID().toString(), "AAPL", 100_00, 1000));
    }

    // @State(Scope.Benchmark)
    // public static class LoadedBookState {
    //
    // MatchingEngine engine;
    //
    // @Setup(Level.Trial)
    // public void setup() throws Exception {
    // engine = new MatchingEngine(EngineMode.SYNC);
    // engine.start();
    //
    // // Pre-load 100k unmatched BUYs across 500 price levels
    // for (int i = 1; i <= 100_000; i++) {
    // engine.submit(buildBuyLimit("LOAD-" + i, "AAPL", 100_00 + (i % 500), // spread
    // // across
    // // prices
    // 1));
    // }
    // }
    //
    // // Now measure a crossing SELL on a heavy book
    // }

    @State(Scope.Benchmark)
    public static class LoadedBookState {

        MatchingEngine engine;
        AtomicLong orderCount = new AtomicLong(0);

        @Setup(Level.Trial)
        public void setup() throws Exception {
            engine = new MatchingEngine(EngineMode.SYNC);
            engine.start();

            for (int i = 1; i <= 100_000; i++) {
                engine.submit(buildBuyLimit("LOAD-" + i, "AAPL", 100_00 + (i % 500), 1 // <-- each
                                                                                       // order is
                                                                                       // only 1
                                                                                       // unit, so
                                                                                       // crossing
                                                                                       // 100k units
                                                                                       // means
                                                                                       // matching
                                                                                       // ALL
                ));
            }
            orderCount.set(100_000);
        }

        // Log it to confirm
        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            System.out.println("Loaded " + orderCount.get() + " orders");
            engine.stop();
        }
    }

    // Scenario C: very deep book — crossing multiple price levels
    @State(Scope.Benchmark)
    public static class DeepBookState {

        MatchingEngine engine;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            engine = new MatchingEngine(EngineMode.SYNC);
            engine.start();

            // Load 10k orders, but spread them across 5000 price levels (sparse book)
            for (int i = 0; i < 10_000; i++) {
                engine.submit(buildBuyLimit("DEEP-" + i, "AAPL", 100_00 + (i * 100), // every order
                                                                                     // at a
                                                                                     // different
                                                                                     // price
                        1));
            }
        }
    }

    //
    // // -----------------------------------------------------------------------
    // // Helper factories
    // // -----------------------------------------------------------------------
    //
    // private static NewOrderCommand buildMarketOrder(String clientId, String symbol, OrderSide
    // side,
    // long qty) {
    // return NewOrderCommand.builder().clientOrderId(clientId).userId("user-bench").symbol(symbol)
    // .side(side).type(OrderType.MARKET).quantity(qty).build();
    // }
    //
    // public static void main(String[] args) throws RunnerException {
    // Options opt = new OptionsBuilder().include(MatchingEngineBenchmark.class.getSimpleName())
    // .warmupIterations(3).measurementIterations(5).forks(1) // use 2 for final numbers
    // .build();
    // new Runner(opt).run();
    // }
    //
    // /**
    // * Baseline: submit a single limit BUY that rests on the book (no match). Isolates order
    // * validation + order-book insertion cost.
    // */
    // @Benchmark
    // public void newOrder_sync_limitBuy_noMatch(SyncEngineState s, Blackhole bh)
    // throws InterruptedException {
    // String cid = s.nextClientId();
    // s.engine.submit(buildBuyLimit(cid, "AAPL", 100_00, 1));
    // }
    //
    // // -----------------------------------------------------------------------
    // // Benchmarks — SYNC mode
    // // -----------------------------------------------------------------------
    //
    // @Benchmark
    // public void newOrder_sync_matchingTrade(CrossingOrderState s) throws InterruptedException {
    // long n = s.counter.incrementAndGet();
    // // Place a resting BUY, then a crossing SELL — one trade fires per pair
    // s.engine.submit(buildBuyLimit("B-" + n, "AAPL", 150_00, 5));
    // s.engine.submit(buildSellLimit("S-" + n, "AAPL", 150_00, 5));
    // }
    //
    // /**
    // * Market order hitting a pre-seeded book. Seeds 1 resting limit per invocation then sends a
    // * crossing market order.
    // */
    // @Benchmark
    // public void newOrder_sync_marketOrder(CrossingOrderState s) throws InterruptedException {
    // long n = s.counter.incrementAndGet();
    // s.engine.submit(buildBuyLimit("L-" + n, "AAPL", 155_00, 10));
    // s.engine.submit(buildMarketOrder("M-" + n, "AAPL", OrderSide.SELL, 10));
    // }
    //
    // /**
    // * Cancel an order that is resting on the book. Uses the pre-loaded CancelOrderState so cancel
    // * logic is isolated.
    // */
    // @Benchmark
    // public void cancelOrder_sync(CancelOrderState s) throws InterruptedException {
    // String cid = s.nextClientId();
    // CancelOrderCommand cancel = CancelOrderCommand.builder().clientOrderId(cid).build();
    // s.engine.submit(cancel);
    // }
    //
    // /**
    // * Measures cost of sequencer.getNextSequence() + LinkedBlockingQueue.put(). Engine thread
    // * drains asynchronously; this is purely the producer side.
    // */
    // @Benchmark
    // public void newOrder_async_submitCost(AsyncEngineState s) throws InterruptedException {
    // String cid = s.nextClientId();
    // s.engine.submit(buildBuyLimit(cid, "GOOGL", 180_00, 1));
    // }
    //
    // /**
    // * Distributes orders evenly across all configured symbols. Useful to check per-book
    // contention
    // * or cache effects.
    // */
    // @Benchmark
    // public void newOrder_sync_multiSymbol(SyncEngineState s) throws InterruptedException {
    // long n = s.clientIdCounter.incrementAndGet();
    // String symbol = SYMBOLS[(int) (n % SYMBOLS.length)];
    // s.engine.submit(buildBuyLimit("MS-" + n, symbol, 100_00, 1));
    // }
    //
    // // -----------------------------------------------------------------------
    // // Benchmarks — ASYNC mode (measures enqueue / submit overhead only)
    // // -----------------------------------------------------------------------
    //
    // /**
    // * SYNC engine — process() is called on the caller's thread. Suitable for latency / throughput
    // * benchmarks without thread coordination noise.
    // */
    // @State(Scope.Benchmark)
    // public static class SyncEngineState {
    //
    // MatchingEngine engine;
    // AtomicLong clientIdCounter;
    //
    // @Setup(Level.Trial)
    // public void setupTrial() throws Exception {
    // try {
    // log.debug("Setting up SyncEngineState");
    // engine = new MatchingEngine(EngineMode.SYNC);
    // engine.start();
    // clientIdCounter = new AtomicLong(0);
    // log.debug("SyncEngineState setup complete");
    // } catch (Exception e) {
    // log.error("Failed to setup SyncEngineState", e);
    // throw e;
    // }
    // }
    //
    // @TearDown(Level.Trial)
    // public void tearDownTrial() throws Exception {
    // try {
    // log.debug("Tearing down SyncEngineState");
    // if (engine != null) {
    // engine.stop();
    // }
    // log.debug("SyncEngineState teardown complete");
    // } catch (Exception e) {
    // log.error("Failed to teardown SyncEngineState", e);
    // throw e;
    // }
    // }
    //
    // /**
    // * Returns a unique client order ID for each invocation.
    // */
    // String nextClientId() {
    // return "C-" + clientIdCounter.incrementAndGet();
    // }
    // }
    //
    // // -----------------------------------------------------------------------
    // // Benchmarks — multi-symbol spread
    // // -----------------------------------------------------------------------
    //
    // /**
    // * ASYNC engine — commands are queued; the engine thread processes them. Benchmarks here
    // measure
    // * producer-side submit() overhead (enqueue cost), not end-to-end latency.
    // */
    // @State(Scope.Benchmark)
    // public static class AsyncEngineState {
    //
    // MatchingEngine engine;
    // AtomicLong clientIdCounter;
    //
    // @Setup(Level.Trial)
    // public void setupTrial() throws Exception {
    // try {
    // log.debug("Setting up AsyncEngineState");
    // engine = new MatchingEngine(EngineMode.ASYNC);
    // engine.start();
    // clientIdCounter = new AtomicLong(0);
    // log.debug("AsyncEngineState setup complete");
    // } catch (Exception e) {
    // log.error("Failed to setup AsyncEngineState", e);
    // throw e;
    // }
    // }
    //
    // @TearDown(Level.Trial)
    // public void tearDownTrial() throws Exception {
    // try {
    // log.debug("Tearing down AsyncEngineState");
    // if (engine != null) {
    // engine.stop();
    // }
    // log.debug("AsyncEngineState teardown complete");
    // } catch (Exception e) {
    // log.error("Failed to teardown AsyncEngineState", e);
    // throw e;
    // }
    // }
    //
    // String nextClientId() {
    // return "CA-" + clientIdCounter.incrementAndGet();
    // }
    // }
    //
    // /**
    // * Pre-built cancel scenario: submits a batch of orders during setup so the benchmark body
    // only
    // * measures the cancel path.
    // */
    // @State(Scope.Thread)
    // public static class CancelOrderState {
    //
    // private static final int PRELOAD_SIZE = 10_000;
    // // Reuses the SyncEngineState but sets up pre-loaded orders.
    // MatchingEngine engine;
    // String[] clientOrderIds;
    // int index = 0;
    //
    // @Setup(Level.Trial)
    // public void setup() throws Exception {
    // try {
    // log.info("Setting up CancelOrderState with {} pre-loaded orders", PRELOAD_SIZE);
    // engine = new MatchingEngine(EngineMode.SYNC);
    // engine.start();
    // clientOrderIds = new String[PRELOAD_SIZE];
    // for (int i = 0; i < PRELOAD_SIZE; i++) {
    // String cid = "PRE-" + i;
    // clientOrderIds[i] = cid;
    // engine.submit(buildBuyLimit(cid, "AAPL", 150_00, 10));
    // }
    // log.info("CancelOrderState setup complete");
    // } catch (Exception e) {
    // log.error("Failed to setup CancelOrderState", e);
    // throw e;
    // }
    // }
    //
    // @TearDown(Level.Trial)
    // public void tearDown() throws Exception {
    // try {
    // log.info("Tearing down CancelOrderState");
    // if (engine != null) {
    // engine.stop();
    // }
    // log.info("CancelOrderState teardown complete");
    // } catch (Exception e) {
    // log.error("Failed to teardown CancelOrderState", e);
    // throw e;
    // }
    // }
    //
    // /**
    // * Round-robins through the pre-loaded client IDs.
    // */
    // String nextClientId() {
    // String cid = clientOrderIds[index % clientOrderIds.length];
    // index++;
    // return cid;
    // }
    // }
    //
    // // -----------------------------------------------------------------------
    // // Main — run from IDE or standalone jar
    // // -----------------------------------------------------------------------
    //
    // /**
    // * Submit a SELL limit that immediately crosses the best resting BUY. Measures matching +
    // event
    // * publication on the hot path.
    // * <p>
    // * Setup: seeds one resting BUY per iteration via @Setup(Level.Invocation).
    // */
    // @State(Scope.Thread)
    // public static class CrossingOrderState {
    //
    // MatchingEngine engine;
    // AtomicLong counter = new AtomicLong();
    //
    // @Setup(Level.Trial)
    // public void setup() throws Exception {
    // try {
    // log.info("Setting up CrossingOrderState");
    // engine = new MatchingEngine(EngineMode.SYNC);
    // engine.start();
    // log.info("CrossingOrderState setup complete");
    // } catch (Exception e) {
    // log.error("Failed to setup CrossingOrderState", e);
    // throw e;
    // }
    // }
    //
    // @TearDown(Level.Trial)
    // public void tearDown() throws Exception {
    // try {
    // log.info("Tearing down CrossingOrderState");
    // if (engine != null) {
    // engine.stop();
    // }
    // log.info("CrossingOrderState teardown complete");
    // } catch (Exception e) {
    // log.error("Failed to teardown CrossingOrderState", e);
    // throw e;
    // }
    // }
    // }
}
