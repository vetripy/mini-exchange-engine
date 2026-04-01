package org.trading.exchange.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.trading.exchange.stub.EngineCommandStub.getValidLimitBuyCommand;
import static org.trading.exchange.stub.EngineCommandStub.getValidLimitSellCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.trading.exchange.engine.command.CancelOrderCommand;
import org.trading.exchange.engine.command.EngineCommand;
import org.trading.exchange.engine.command.NewOrderCommand;
import org.trading.exchange.event.OrderUpdateEvent;
import org.trading.exchange.event.TradeEvent;
import org.trading.exchange.listener.impl.LoggingOrderUpdateListener;
import org.trading.exchange.model.EngineMode;
import org.trading.exchange.model.EngineState;
import org.trading.exchange.model.OrderState;
import org.trading.exchange.model.Symbol;
import org.trading.exchange.utils.TestEngineStateListener;
import org.trading.exchange.utils.TestOrderUpdateListener;
import org.trading.exchange.utils.TestTradeListener;

class MatchingEngineTest {

    private MatchingEngine engine;
    private TestTradeListener tradeListener;
    private TestOrderUpdateListener orderUpdateListener;

    @BeforeEach
    void setup() {
        engine = new MatchingEngine(EngineMode.SYNC);
        tradeListener = new TestTradeListener();
        orderUpdateListener = new TestOrderUpdateListener();
        TestEngineStateListener testEngineStateListener = new TestEngineStateListener();
        LoggingOrderUpdateListener loggingOrderUpdateListener = new LoggingOrderUpdateListener();

        engine.addTradeListener(tradeListener);
        engine.addOrderUpdateListener(orderUpdateListener);
        engine.addOrderUpdateListener(loggingOrderUpdateListener);
        engine.addStateListener(testEngineStateListener);
        engine.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        engine.stop();
    }

    @Test
    @DisplayName("Test starting the matching engine twice throws exception")
    void startTwiceThrows() {
        assertThrows(IllegalStateException.class, () -> engine.start());
    }

    @Test
    @DisplayName("Test stopping the matching engine twice throws exception")
    void stopWhenNotRunningThrows() throws InterruptedException {
        MatchingEngine testEngine = new MatchingEngine(EngineMode.SYNC);
        testEngine.start();
        testEngine.stop();
        assertThrows(IllegalStateException.class, testEngine::stop);
    }

    @Test
    @DisplayName("Test engine state transitions")
    void testEngineStateTransitions() throws Exception {
        MatchingEngine testEngine = new MatchingEngine(EngineMode.SYNC);
        assertEquals(EngineState.NEW, testEngine.getState());

        testEngine.start();
        assertEquals(EngineState.RUNNING, testEngine.getState());

        testEngine.stop();
        assertEquals(EngineState.STOPPED, testEngine.getState());
    }

    @Test
    @DisplayName("Test simple match produces a trade")
    void simpleMatchProducesTrade() throws Exception {

        EngineCommand buyCommand = getValidLimitBuyCommand("TEST1", 100L, 5L);
        EngineCommand sellCommand = getValidLimitSellCommand("TEST1", 100L, 5L);

        engine.submit(buyCommand);
        engine.submit(sellCommand);

        assertEquals(1, tradeListener.getTrades().size());

        TradeEvent tradeEvent = tradeListener.getTrades().getFirst();

        assertEquals(100L, tradeEvent.getTradePrice());
        assertEquals(5L, tradeEvent.getQuantity());
        assertEquals(((NewOrderCommand) buyCommand).getClientOrderId(),
                tradeEvent.getBuyClientOrderId());
        assertEquals(((NewOrderCommand) sellCommand).getClientOrderId(),
                tradeEvent.getSellClientOrderId());
    }

    @Test
    @DisplayName("Test submitting an order event when engine is not running throws exception")
    void testSubmitEventWhenEngineNotRunning() {
        MatchingEngine testEngine = new MatchingEngine(EngineMode.SYNC);
        // Given
        EngineCommand cmd = getValidLimitBuyCommand("TEST1", 10L, 10L);

        // When & Then
        assertThrows(IllegalStateException.class, () -> testEngine.submit(cmd));
    }

    @Test
    void partialFillUpdatesStateCorrectly() throws Exception {

        EngineCommand buyCommand = getValidLimitBuyCommand("TEST1", 100L, 10L);
        EngineCommand sellCommand = getValidLimitSellCommand("TEST1", 100L, 4L);

        engine.submit(buyCommand);
        engine.submit(sellCommand);

        assertEquals(1, tradeListener.getTrades().size());

        TradeEvent tradeEvent = tradeListener.getTrades().getFirst();
        assertEquals(4L, tradeEvent.getQuantity());

        OrderUpdateEvent lastUpdate =
                orderUpdateListener.getUpdates().stream()
                        .filter(orderUpdate -> Objects.equals(orderUpdate.getClientOrderId(),
                                ((NewOrderCommand) buyCommand).getClientOrderId()))
                        .toList().getLast();

        assertEquals(OrderState.PARTIALLY_FILLED, lastUpdate.getOrderState());
        assertEquals(6L, lastUpdate.getRemainingQuantity());
    }

    @Test
    void cancelOrderEmitsCancelledState() throws Exception {

        EngineCommand buyCommand = getValidLimitBuyCommand("TEST1", 100L, 5L);

        engine.submit(buyCommand);
        engine.submit(CancelOrderCommand.of(((NewOrderCommand) buyCommand).getClientOrderId()));

        OrderUpdateEvent last = orderUpdateListener.latest();

        assertEquals(OrderState.CANCELLED, last.getOrderState());
    }

    @Test
    void sequenceNumbersAreStrictlyIncreasing() throws Exception {

        EngineCommand buyCommand = getValidLimitBuyCommand("TEST1", 100L, 5L);
        EngineCommand sellCommand = getValidLimitSellCommand("TEST1", 100L, 5L);

        engine.submit(buyCommand);
        engine.submit(sellCommand);

        List<Long> sequences = new ArrayList<>();

        orderUpdateListener.getUpdates().forEach(u -> sequences.add(u.getSequence()));

        for (int i = 1; i < sequences.size(); i++) {
            assertTrue(sequences.get(i) >= sequences.get(i - 1));
        }
    }

    @Test
    void matchesAcrossPriceLevels() throws Exception {

        EngineCommand sellCommand1 = getValidLimitSellCommand("TEST1", 101L, 3L);
        EngineCommand sellCommand2 = getValidLimitSellCommand("TEST1", 100L, 3L);
        EngineCommand buyCommand = getValidLimitBuyCommand("TEST1", 101L, 5L);

        engine.submit(sellCommand1);
        engine.submit(sellCommand2);
        engine.submit(buyCommand);

        assertEquals(2, tradeListener.getTrades().size());

        long totalQuantity =
                tradeListener.getTrades().stream().mapToLong(TradeEvent::getQuantity).sum();

        assertEquals(5L, totalQuantity);
    }

    @Test
    @DisplayName("Test cancelling unknown client order ID throws exception")
    void testCancelUnknownClientOrderId() throws Exception {
        EngineCommand buyCommand = getValidLimitBuyCommand("TEST1", 100L, 5L);
        engine.submit(buyCommand);

        assertThrows(IllegalArgumentException.class,
                () -> engine.submit(CancelOrderCommand.of("unknown-client-id")));
    }

    @Test
    @DisplayName("Test order book maintains separate books per symbol")
    void testMultipleSymbols() throws Exception {
        EngineCommand buyCommand1 = getValidLimitBuyCommand("TEST1", 100L, 5L);
        EngineCommand sellCommand1 = getValidLimitSellCommand("TEST1", 100L, 5L);
        EngineCommand buyCommand2 = getValidLimitBuyCommand("TEST2", 200L, 5L);
        EngineCommand sellCommand2 = getValidLimitSellCommand("TEST2", 200L, 5L);

        engine.submit(buyCommand1);
        engine.submit(sellCommand1);
        engine.submit(buyCommand2);
        engine.submit(sellCommand2);

        assertEquals(2, tradeListener.getTrades().size());
        TradeEvent tradeEvent1 = tradeListener.getTrades().get(0);
        TradeEvent tradeEvent2 = tradeListener.getTrades().get(1);

        assertEquals(Symbol.TEST1, tradeEvent1.getSymbol());
        assertEquals(Symbol.TEST2, tradeEvent2.getSymbol());
    }
}
