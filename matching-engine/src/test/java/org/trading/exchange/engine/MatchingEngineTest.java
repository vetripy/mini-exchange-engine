package org.trading.exchange.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.trading.exchange.stub.OrderStub.getValidLimitBuyOrderWith;
import static org.trading.exchange.stub.OrderStub.getValidLimitSellOrderWith;

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
import org.trading.exchange.event.OrderEvent;
import org.trading.exchange.event.OrderUpdateEvent;
import org.trading.exchange.event.TradeEvent;
import org.trading.exchange.model.EngineMode;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderState;
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

        engine.addTradeListener(tradeListener);
        engine.addOrderUpdateListener(orderUpdateListener);
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
    @DisplayName("Test simple match produces a trade")
    void simpleMatchProducesTrade() throws Exception {

        Order buy = getValidLimitBuyOrderWith(100L, 5L);
        Order sell = getValidLimitSellOrderWith(100L, 5L);

        engine.submit(NewOrderCommand.of(buy));
        engine.submit(NewOrderCommand.of(sell));

        assertEquals(1, tradeListener.getTrades().size());

        TradeEvent tradeEvent = tradeListener.getTrades().getFirst();

        assertEquals(100L, tradeEvent.getTradePrice());
        assertEquals(5L, tradeEvent.getQuantity());
        assertEquals(buy.getOrderId(), tradeEvent.getBuyOrderId());
        assertEquals(sell.getOrderId(), tradeEvent.getSellOrderId());
    }

    @Test
    @DisplayName("Test submitting an order event when engine is not running throws exception")
    void testSubmitEventWhenEngineNotRunning() {
        MatchingEngine testEngine = new MatchingEngine(EngineMode.SYNC);
        // Given
        Order order = getValidLimitBuyOrderWith(10L, 10L);
        EngineCommand event = NewOrderCommand.of(order);

        // When & Then
        assertThrows(IllegalStateException.class, () -> testEngine.submit(event));
    }

    @Test
    void partialFillUpdatesStateCorrectly() throws Exception {

        Order buy = getValidLimitBuyOrderWith(100L, 10L);
        Order sell = getValidLimitSellOrderWith(100L, 4L);

        engine.submit(NewOrderCommand.of(buy));
        engine.submit(NewOrderCommand.of(sell));

        assertEquals(1, tradeListener.getTrades().size());

        TradeEvent tradeEvent = tradeListener.getTrades().getFirst();
        assertEquals(4L, tradeEvent.getQuantity());

        OrderUpdateEvent lastUpdate = orderUpdateListener.getUpdates().stream()
            .filter(orderUpdate -> Objects.equals(orderUpdate.getOrderId(), buy.getOrderId()))
            .toList().getLast();

        assertEquals(OrderState.PARTIALLY_FILLED, lastUpdate.getOrderState());
        assertEquals(6L, lastUpdate.getRemainingQuantity());
    }

    @Test
    void cancelOrderEmitsCancelledState() throws Exception {

        Order buy = getValidLimitBuyOrderWith(100L, 5L);

        engine.submit(NewOrderCommand.of(buy));
        engine.submit(CancelOrderCommand.of(buy.getOrderId()));

        System.out.println(orderUpdateListener.getUpdates());
        OrderUpdateEvent last = orderUpdateListener.latest();

        assertEquals(OrderState.CANCELLED, last.getOrderState());
    }

    @Test
    void sequenceNumbersAreStrictlyIncreasing() throws Exception {

        Order buy = getValidLimitBuyOrderWith(100L, 5L);
        Order sell = getValidLimitSellOrderWith(100L, 5L);

        engine.submit(NewOrderCommand.of(buy));
        engine.submit(NewOrderCommand.of(sell));

        List<Long> sequences = new ArrayList<>();

        orderUpdateListener.getUpdates().forEach(u -> sequences.add(u.getSequence()));

        for (int i = 1; i < sequences.size(); i++) {
            assertTrue(sequences.get(i) >= sequences.get(i - 1));
        }
    }

    @Test
    void matchesAcrossPriceLevels() throws Exception {

        Order sell1 = getValidLimitSellOrderWith(101L, 3L);
        Order sell2 = getValidLimitSellOrderWith(100L, 3L);
        Order buy = getValidLimitBuyOrderWith(101L, 5L);

        engine.submit(NewOrderCommand.of(sell1));
        engine.submit(NewOrderCommand.of(sell2));
        engine.submit(NewOrderCommand.of(buy));

        assertEquals(2, tradeListener.getTrades().size());

        long totalQuantity =
            tradeListener.getTrades().stream().mapToLong(TradeEvent::getQuantity).sum();

        assertEquals(5L, totalQuantity);
    }
}
