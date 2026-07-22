# Mini Exchange Engine

A low-latency order matching engine written in modern Java. It uses a single-threaded matching core
that owns all state, a lock-free command intake, and a Disruptor-based outbound event pipeline, which is
roughly how real venues like LMAX are structured.

The goal is an exchange-grade matching core: deterministic, replayable, and allocation-conscious on the
hot path. The core mechanics work today. What's still missing before it could run a real venue is
tracked in the roadmap below.

## Design

A matching engine has to get three things right: price-time priority, determinism (same input produces
same output, so state can be replayed and replicated), and latency under load. The main decisions:

* **Single-threaded matching core.** One thread owns every order book and all mutable state, so there
  are no locks and no memory-visibility hazards on the match path. LMAX and most modern venues do the
  same.
* **Command in, event out, with a sequencer.** An `EngineCommand` goes in, an `OutboundEvent` comes out,
  and a monotonic sequencer stamps ordering onto every command. That's the shape you need for a
  journal-and-replay system.
* **Fixed-point prices as `long`.** No floating point in the matching path. Prices and quantities are
  integers, so comparisons are exact.
* **Disruptor outbound ring.** Trades and order updates publish to a pre-allocated ring buffer (131,072
  slots, `YieldingWaitStrategy`), which keeps the matcher decoupled from slower downstream consumers.
* **Flyweight event slots.** Ring slots are mutated in place instead of allocating a new object per
  emission, keeping the producer side garbage-free.
* **Swappable sink.** `OutboundEventSink` has a synchronous implementation (`DirectOutboundSink`) for
  deterministic in-thread tests and an async one (`RingBufferOutboundSink`) for production. The match
  logic doesn't know which is in use.
* **Lock-free intake.** Commands arrive over an Agrona `ManyToOneConcurrentArrayQueue` (100k capacity),
  so many producer threads can feed the one consumer without locks.

## Architecture

```
   producers (N threads)
        |  submit(EngineCommand)
        v
  ManyToOneConcurrentArrayQueue        lock-free inbound intake (Agrona)
        |
        v
   +-------------------------+
   |      MatchingEngine      |        single thread, owns all state
   |   Sequencer (monotonic)  |
   |   OrderValidator         |
   |   Map<Symbol, OrderBook> |
   +-----------+-------------+
               |  match: price-time priority
               v
   OutboundEventSink  -->  Disruptor ring buffer (async)
                              +-> TradeListener
                              +-> OrderUpdateListener
                              +-> CommandRejectedListener
```

The rule the codebase is built around: anything nondeterministic (clocks, ID assignment, auth) happens
before the sequencer, and everything after it is a pure function of the sequenced command stream.

## Features

Order types:

* `LIMIT` rests on the book if not fully filled
* `MARKET` sweeps available liquidity and cancels any unfilled remainder
* `IOC` (Immediate-Or-Cancel) fills what it can immediately and cancels the rest
* `FOK` (Fill-Or-Kill) fills completely or not at all

Matching:

* Price-time (FIFO) priority: best price first, then earliest arrival within a level
* Partial fills, resting-order aggregation, per-fill trade emission
* Order cancellation with book cleanup
* One `OrderBook` per instrument

Engine:

* `SYNC` and `ASYNC` execution modes behind one API
* Monotonic sequencer stamping every command for ordering and future replay
* Lifecycle state machine (`NEW`, `RUNNING`, `STOPPING`, `STOPPED`, `FAILED`)
* Order validation and structured rejection (`CommandRejectedEvent`)
* Listeners for trades, order updates, rejects, and state changes

On the book:

* `TreeMap<Long, ArrayDeque<Order>>` per side for ordered price levels with FIFO queues
* Agrona `Long2ObjectHashMap` order index for direct lookup on cancel

## Performance

Load is measured with a `SustainedLoadHarness`:

* HdrHistogram latency capture across the full run and per-window thirds, so p99, p99.9, and max are
  visible rather than just the median.
* Coordinated-omission aware. The harness paces to a target rate and watches whether the book is growing
  unboundedly. When the workload isn't in steady state it prints an `INCONCLUSIVE` verdict instead of
  reporting a latency number that's really a GC or book-growth artifact.
* GC behavior is captured alongside throughput (the `gc-loadtest` workflow), so tails can be attributed
  to matching versus pauses.

One recent run sustained a multi-symbol mix (limits, IOCs, and about 20% cancels against levels tens
deep) and processed over 16M outbound events in a ~55s window while streaming book-depth and queue
telemetry. That run's book trend read `GROWING`, so the harness flagged the latency tail as untrustworthy.
Steady-state tuning of the workload mix is still in progress.

## Build and run

Needs JDK 21+ (record patterns and pattern-matching `switch`). Gradle wrapper is included.

```bash
cd matching-engine

./gradlew build          # compile and run tests
./gradlew test           # tests only
./gradlew spotlessApply  # format (Google Java Style)
```

Wiring the engine:

```java
MatchingEngine engine = new MatchingEngine(EngineMode.ASYNC);
engine.addTradeListener(trade -> log.info("TRADE {}", trade));
engine.addOrderUpdateListener(update -> log.info("UPDATE {}", update));
engine.start();

engine.submit(new NewOrderCommand(
        "clOrd-1", "user-1", "AAPL", OrderSide.BUY, OrderType.LIMIT,
        15_025L, 100L, ingressTimestamp));

engine.stop();
```

## Testing

* Unit and integration coverage for the engine, order book, and validators
* Test listener fakes (`TestTradeListener`, `TestOrderUpdateListener`, `TestEngineStateListener`) assert
  emitted events deterministically through the synchronous sink
* Order and command builders (`OrderFactory` and stubs) for readable scenario setup
* Sequence diagrams in `docs/newOrder.puml` and `docs/cancelOrder.puml`

## Roadmap to exchange grade

The matching logic is sound; the remaining work is determinism, durability,
and lifecycle. In priority order:

1. **Remove nondeterminism from the engine thread.** No wall-clock reads inside matching; ingress time
   arrives as data on the command. Ship it with a replay-equality test (same command stream, byte-identical
   events).
2. **Correctness fixes.** Globally unique trade IDs across symbols, `(session, clOrdID)` keying, ownership
   checks on cancel, and self-trade prevention.
3. **Intrusive linked-list book with `PriceLevel` aggregates.** O(1) cancel (the most common op on a real
   venue) and cheap depth for a market-data feed.
4. **Journal, snapshot, replay.** Sequence and fsync the command stream before the engine sees it, then
   reconstruct state on restart. This is what step 1 exists to enable.
5. **Instrument registry** to replace the hardcoded `Symbol` enum: runtime listing with tick size, lot
   size, price bands, and trading state.

Later: order modify/replace with priority rules, price bands and collars, opening and closing auctions, a
per-instrument trading state machine, stop orders, an incremental L2/L3 market-data feed, and per-symbol
sharding across engine instances.

## Tech stack

* Java 21+ (records, pattern-matching `switch`)
* [LMAX Disruptor](https://lmax-exchange.github.io/disruptor/) and [Agrona](https://github.com/aeron-io/agrona) lock-free collections
* HdrHistogram and a custom sustained-load harness
* Gradle wrapper, Spotless (Google Java Style)
* SLF4J and Logback
* JUnit 5
