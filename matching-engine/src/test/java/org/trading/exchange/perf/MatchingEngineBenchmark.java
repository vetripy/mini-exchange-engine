package org.trading.exchange.perf;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
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

/**
 * Consolidated JMH Benchmarks for MatchingEngine.
 * <p>
 * Run via Gradle: ./gradlew jmh                                    # all benchmarks ./gradlew jmh
 * --include "baseline"              # baseline scenarios only ./gradlew jmh --include "heavyBook" #
 * heavy book scenarios ./gradlew jmh -Pjmh.profilers="gc"              # with GC profiler
 * <p>
 * Benchmark Categories: - baseline_*        : Core operations (single order, matching, cancel) -
 * heavyBook_*       : Large pre-loaded book scenarios - deepBook_*        : Multi-level price
 * traversal - partialFill_*     : Partial order matching - multiSymbol_*     : Cross-symbol
 * distribution - async_*           : ASYNC mode producer overhead
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 3, timeUnit = TimeUnit.SECONDS)
public class MatchingEngineBenchmark {

    // ==================================================================================
    // BASELINE SCENARIOS - Core functionality with clean state per iteration
    // ==================================================================================

    @State(Scope.Benchmark)
    public static class BaselineState {

        MatchingEngine engine;
        AtomicLong clientIdCounter;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            startEngine();
        }

        @Setup(Level.Iteration)
        public void setupIteration() throws Exception {
            if (engine != null) {
                engine.stop();
            }
            startEngine();
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() throws Exception {
            engine.stop();
        }

        private void startEngine() throws Exception {
            engine = new MatchingEngine(EngineMode.SYNC);
            engine.start();
            clientIdCounter = new AtomicLong(0);
        }

        String nextClientId() {
            return "B-" + clientIdCounter.incrementAndGet();
        }
    }

    /**
     * Baseline: Single limit BUY that rests on book (no match). Measures: validation + book
     * insertion + map update.
     */
    @Benchmark
    public void baseline_limitBuy_noMatch(BaselineState s) throws InterruptedException {
        s.engine.submit(buildBuyLimit(s.nextClientId(), "AAPL", 100_00, 1));
    }

    /**
     * Baseline: Two crossing limit orders (one trade). Measures: matching + trade execution + event
     * emission.
     */
    @Benchmark
    public void baseline_matchingTrade(BaselineState s) throws InterruptedException {
        long n = s.clientIdCounter.incrementAndGet();
        s.engine.submit(buildBuyLimit("B-" + n, "AAPL", 150_00, 5));
        s.engine.submit(buildSellLimit("S-" + n, "AAPL", 150_00, 5));
    }

    /**
     * Baseline: Market order hitting pre-seeded limit. Measures: market order matching path.
     */
    @Benchmark
    public void baseline_marketOrder(BaselineState s) throws InterruptedException {
        long n = s.clientIdCounter.incrementAndGet();
        s.engine.submit(buildBuyLimit("L-" + n, "AAPL", 155_00, 10));
        s.engine.submit(buildMarketOrder("M-" + n, "AAPL", OrderSide.SELL, 10));
    }

    // ==================================================================================
    // CANCEL SCENARIOS - Pre-loaded orders for cancel path isolation
    // ==================================================================================

    @State(Scope.Thread)
    public static class CancelState {

        MatchingEngine engine;
        Queue<String> cancelableOrderIds = new ConcurrentLinkedQueue<>();
        AtomicLong idGenerator = new AtomicLong(0);

        private static final int BATCH_SIZE = 100_000_00;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            engine = new MatchingEngine(EngineMode.SYNC);
            engine.start();
        }

        @Setup(Level.Iteration)
        public void setupIteration() throws Exception {
            System.out.println("Pre-creating " + BATCH_SIZE + " cancelable orders...");

            for (int i = 0; i < BATCH_SIZE; i++) {
                String cid = "CANCEL-" + idGenerator.incrementAndGet();
                engine.submit(buildBuyLimit(cid, "AAPL", 150_00 + (i % 100), 10));
                cancelableOrderIds.offer(cid);
            }
        }

        @TearDown(Level.Iteration)
        public void tearDownIteration() {
            int remaining = cancelableOrderIds.size();
            System.out.println("Used: " + (BATCH_SIZE - remaining) + "/" + BATCH_SIZE);
            cancelableOrderIds.clear();
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() throws Exception {
            engine.stop();
        }
    }


    /**
     * Cancel path: Remove resting order from book. Measures: order lookup + book removal + map
     * cleanup.
     */
    @Benchmark
    public void baseline_cancelOrder(CancelState s) throws InterruptedException {
        String cid = s.cancelableOrderIds.poll();
        if (cid == null) {
            throw new IllegalStateException("Ran out of orders");
        }
        s.engine.submit(CancelOrderCommand.builder().clientOrderId(cid).build());
    }

    // ==================================================================================
    // HEAVY BOOK SCENARIOS - Large order count, test scalability
    // ==================================================================================

    @State(Scope.Benchmark)
    public static class HeavyBookState {

        MatchingEngine engine;
        AtomicLong sellCounter = new AtomicLong(0);

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            engine = new MatchingEngine(EngineMode.SYNC);
            engine.start();

            // Load 100k BUY orders across 500 price levels
            for (int i = 1; i <= 100_000; i++) {
                engine.submit(buildBuyLimit(
                    "HEAVY-" + i,
                    "AAPL",
                    100_00 + (i % 500),
                    1
                ));
            }
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() throws Exception {
            engine.stop();
        }
    }

    /**
     * Heavy book: Partial cross (100 units on 100k order book). Measures: matching overhead with
     * large book.
     */
    @Benchmark
    public void heavyBook_partialCross(HeavyBookState s) throws InterruptedException {
        s.engine.submit(
            buildSellLimit("SELL-" + s.sellCounter.incrementAndGet(), "AAPL", 100_00, 100));
    }

    /**
     * Heavy book: Full cross (100k units on 100k order book). Measures: bulk matching performance.
     */
    @Benchmark
    public void heavyBook_fullCross(HeavyBookState s) throws InterruptedException {
        s.engine.submit(
            buildSellLimit("SELL-" + s.sellCounter.incrementAndGet(), "AAPL", 100_00, 100_000));
    }

    // ==================================================================================
    // DEEP BOOK SCENARIOS - Multi-level price traversal
    // ==================================================================================

    @State(Scope.Benchmark)
    public static class DeepBookState {

        MatchingEngine engine;
        AtomicLong sellCounter = new AtomicLong(0);

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            engine = new MatchingEngine(EngineMode.SYNC);
            engine.start();

            // Load 10k orders at different prices (sparse book, deep levels)
            for (int i = 0; i < 10_000; i++) {
                engine.submit(buildBuyLimit(
                    "DEEP-" + i,
                    "AAPL",
                    100_00 + (i * 10),  // Every 10 cents
                    1
                ));
            }
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() throws Exception {
            engine.stop();
        }
    }

    /**
     * Deep book: Cross through 1000 price levels. Measures: TreeMap traversal efficiency.
     */
    @Benchmark
    public void deepBook_crossMultipleLevels(DeepBookState s) throws InterruptedException {
        s.engine.submit(
            buildSellLimit("XING-" + s.sellCounter.incrementAndGet(), "AAPL", 100_00, 1000));
    }

    // ==================================================================================
    // PARTIAL FILL SCENARIOS - Fractional order matching
    // ==================================================================================

    @State(Scope.Benchmark)
    public static class PartialFillState {

        MatchingEngine engine;
        AtomicLong sellCounter = new AtomicLong(0);

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            engine = new MatchingEngine(EngineMode.SYNC);
            engine.start();

            // Load 1k orders of 50 units each
            for (int i = 0; i < 1000; i++) {
                engine.submit(buildBuyLimit(
                    "PARTIAL-" + i,
                    "AAPL",
                    100_00 + (i % 50),
                    50
                ));
            }
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() throws Exception {
            engine.stop();
        }
    }

    /**
     * Partial fill: Small cross (100 units) - partial fills. Measures: partial order update
     * overhead.
     */
    @Benchmark
    public void partialFill_small(PartialFillState s) throws InterruptedException {
        s.engine.submit(
            buildSellLimit("PS-" + s.sellCounter.incrementAndGet(), "AAPL", 100_00, 100));
    }

    /**
     * Partial fill: Medium cross (500 units) - multiple partials. Measures: accumulated partial
     * fill overhead.
     */
    @Benchmark
    public void partialFill_medium(PartialFillState s) throws InterruptedException {
        s.engine.submit(
            buildSellLimit("PM-" + s.sellCounter.incrementAndGet(), "AAPL", 100_00, 500));
    }

    /**
     * Partial fill: Full cross (50k units) - all complete fills. Measures: baseline for comparison
     * with partial scenarios.
     */
    @Benchmark
    public void partialFill_fullMatch(PartialFillState s) throws InterruptedException {
        s.engine.submit(
            buildSellLimit("PF-" + s.sellCounter.incrementAndGet(), "AAPL", 100_00, 50_000));
    }

    // ==================================================================================
    // MULTI-SYMBOL SCENARIOS - Cross-book distribution
    // ==================================================================================

    private static final String[] SYMBOLS = {"AAPL", "GOOGL", "MSFT", "AMZN"};

    @State(Scope.Benchmark)
    public static class MultiSymbolState {

        MatchingEngine engine;
        AtomicLong counter = new AtomicLong(0);

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            startEngine();
        }

        @Setup(Level.Iteration)
        public void setupIteration() throws Exception {
            if (engine != null) {
                engine.stop();
            }
            startEngine();
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() throws Exception {
            engine.stop();
        }

        private void startEngine() throws Exception {
            engine = new MatchingEngine(EngineMode.SYNC);
            engine.start();
            counter = new AtomicLong(0);
        }
    }

    /**
     * Multi-symbol: Distribute orders across all symbols. Measures: per-book isolation, cache
     * effects.
     */
    @Benchmark
    public void multiSymbol_distribution(MultiSymbolState s) throws InterruptedException {
        long n = s.counter.incrementAndGet();
        String symbol = SYMBOLS[(int) (n % SYMBOLS.length)];
        s.engine.submit(buildBuyLimit("MS-" + n, symbol, 100_00, 1));
    }

    // ==================================================================================
    // ASYNC MODE SCENARIOS - Producer-side overhead
    // ==================================================================================

    @State(Scope.Benchmark)
    public static class AsyncState {

        MatchingEngine engine;
        AtomicLong clientIdCounter;

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            engine = new MatchingEngine(EngineMode.ASYNC);
            engine.start();
            clientIdCounter = new AtomicLong(0);
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() throws Exception {
            engine.stop();
        }

        String nextClientId() {
            return "ASYNC-" + clientIdCounter.incrementAndGet();
        }
    }

    /**
     * Async mode: Submit cost (sequencer + queue enqueue). Measures: producer-side latency only
     * (not end-to-end).
     */
    @Benchmark
    public void async_submitCost(AsyncState s) throws InterruptedException {
        s.engine.submit(buildBuyLimit(s.nextClientId(), "GOOGL", 180_00, 1));
    }

    // ==================================================================================
    // WORST CASE SCENARIOS - Edge cases and stress tests
    // ==================================================================================

    @State(Scope.Benchmark)
    public static class WorstCaseState {

        MatchingEngine engine;
        AtomicLong sellCounter = new AtomicLong(0);

        @Setup(Level.Trial)
        public void setupTrial() throws Exception {
            engine = new MatchingEngine(EngineMode.SYNC);
            engine.start();

            // Worst case: 10k orders at 10k unique prices
            for (int i = 0; i < 10_000; i++) {
                engine.submit(buildBuyLimit(
                    "WC-" + i,
                    "AAPL",
                    100_00 + i,  // Every order at unique price
                    100
                ));
            }
        }

        @TearDown(Level.Trial)
        public void tearDownTrial() throws Exception {
            engine.stop();
        }
    }

    /**
     * Worst case: Market order crossing entire sparse book. Measures: maximum price level traversal
     * overhead.
     */
    @Benchmark
    public void worstCase_marketSweep(WorstCaseState s) throws InterruptedException {
        s.engine.submit(
            buildSellMarket("SWEEP-" + s.sellCounter.incrementAndGet(), "AAPL", 1_000_000));
    }

    // ==================================================================================
    // ORDER BUILDER HELPERS
    // ==================================================================================

    private static NewOrderCommand buildBuyLimit(String clientId, String symbol, long price,
        long qty) {
        return NewOrderCommand.builder()
            .clientOrderId(clientId)
            .userId("user-bench")
            .symbol(symbol)
            .side(OrderSide.BUY)
            .type(OrderType.LIMIT)
            .price(price)
            .quantity(qty)
            .build();
    }

    private static NewOrderCommand buildSellLimit(String clientId, String symbol, long price,
        long qty) {
        return NewOrderCommand.builder()
            .clientOrderId(clientId)
            .userId("user-bench")
            .symbol(symbol)
            .side(OrderSide.SELL)
            .type(OrderType.LIMIT)
            .price(price)
            .quantity(qty)
            .build();
    }

    private static NewOrderCommand buildMarketOrder(String clientId, String symbol, OrderSide side,
        long qty) {
        return NewOrderCommand.builder()
            .clientOrderId(clientId)
            .userId("user-bench")
            .symbol(symbol)
            .side(side)
            .type(OrderType.MARKET)
            .quantity(qty)
            .build();
    }

    private static NewOrderCommand buildSellMarket(String clientId, String symbol, long qty) {
        return buildMarketOrder(clientId, symbol, OrderSide.SELL, qty);
    }

    // ==================================================================================
    // MAIN - Run from IDE or standalone
    // ==================================================================================

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(MatchingEngineBenchmark.class.getSimpleName())
            .warmupIterations(3)
            .measurementIterations(5)
            .forks(2)
            .build();
        new Runner(opt).run();
    }
}