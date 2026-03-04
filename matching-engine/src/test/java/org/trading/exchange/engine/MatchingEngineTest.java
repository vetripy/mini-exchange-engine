package org.trading.exchange.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.trading.exchange.event.OrderEvent;
import org.trading.exchange.event.OrderUpdate;
import org.trading.exchange.model.EngineMode;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderState;
import org.trading.exchange.model.Trade;
import org.trading.exchange.utils.TestOrderUpdateListener;
import org.trading.exchange.utils.TestTradeListener;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.trading.exchange.stub.OrderStub.getValidLimitBuyOrderWith;
import static org.trading.exchange.stub.OrderStub.getValidLimitSellOrderWith;

class MatchingEngineTest {

    private MatchingEngine engine;
    private TestTradeListener tradeListener;
    private TestOrderUpdateListener orderUpdateListener;

    @BeforeEach
    void setup() {
        engine = new MatchingEngine(EngineMode.SYNC);
        tradeListener = new TestTradeListener();
        orderUpdateListener = new TestOrderUpdateListener();

        engine.addTradeListener(tradeListener);
        engine.addOrderUpdateListener(orderUpdateListener);
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
        engine.stop();
        assertThrows(IllegalStateException.class, () -> engine.stop());
    }

    @Test
    @DisplayName("Test simple match produces a trade")
    void simpleMatchProducesTrade() throws Exception {

        Order buy = getValidLimitBuyOrderWith(100L, 5L);
        Order sell = getValidLimitSellOrderWith(100L, 5L);

        engine.submit(OrderEvent.newOrder(buy));
        engine.submit(OrderEvent.newOrder(sell));

        assertEquals(1, tradeListener.getTrades().size());

        Trade trade = tradeListener.getTrades().getFirst();

        assertEquals(100L, trade.getTradePrice());
        assertEquals(5L, trade.getQuantity());
        assertEquals(buy.getOrderId(), trade.getBuyOrderId());
        assertEquals(sell.getOrderId(), trade.getSellOrderId());
    }

    @Test
    @DisplayName("Test submitting an order event when engine is not running throws exception")
    void testSubmitEventWhenEngineNotRunning() {
        // Given
        Order order = getValidLimitBuyOrderWith(10L, 10L);
        OrderEvent event = OrderEvent.newOrder(order);

        // When & Then
        assertThrows(IllegalStateException.class, () -> engine.submit(event));
    }

    @Test
    void partialFillUpdatesStateCorrectly() throws Exception {

        Order buy = getValidLimitBuyOrderWith(100L, 10L);
        Order sell = getValidLimitSellOrderWith(100L, 4L);

        engine.submit(OrderEvent.newOrder(buy));
        engine.submit(OrderEvent.newOrder(sell));

        assertEquals(1, tradeListener.getTrades().size());

        Trade trade = tradeListener.getTrades().getFirst();
        assertEquals(4L, trade.getQuantity());

        OrderUpdate lastUpdate = orderUpdateListener.latest();
        assertEquals(OrderState.PARTIALLY_FILLED, lastUpdate.getOrderState());
        assertEquals(6L, lastUpdate.getRemainingQuantity());
    }

    @Test
    void cancelOrderEmitsCancelledState() throws Exception {

        Order buy = getValidLimitBuyOrderWith(100L, 5L);

        engine.submit(OrderEvent.newOrder(buy));
        engine.submit(OrderEvent.cancelOrder(buy.getOrderId()));

        OrderUpdate last = orderUpdateListener.latest();

        assertEquals(OrderState.CANCELLED, last.getOrderState());
        assertEquals(0L, last.getRemainingQuantity());
    }

    @Test
    void sequenceNumbersAreStrictlyIncreasing() throws Exception {

        Order buy = getValidLimitBuyOrderWith(100L, 5L);
        Order sell = getValidLimitSellOrderWith(100L, 5L);

        engine.submit(OrderEvent.newOrder(buy));
        engine.submit(OrderEvent.newOrder(sell));

        List<Long> sequences = new ArrayList<>();

        tradeListener.getTrades()
                .forEach(t -> sequences.add(t.getSequence()));

        orderUpdateListener.getUpdates()
                .forEach(u -> sequences.add(u.getSequence()));

        for (int i = 1; i < sequences.size(); i++) {
            assertTrue(sequences.get(i) > sequences.get(i - 1));
        }
    }

    @Test
    void matchesAcrossPriceLevels() throws Exception {

        Order sell1 = getValidLimitSellOrderWith(101L, 3L);
        Order sell2 = getValidLimitSellOrderWith(100L, 3L);
        Order buy = getValidLimitBuyOrderWith(101L, 5L);

        engine.submit(OrderEvent.newOrder(sell1));
        engine.submit(OrderEvent.newOrder(sell2));
        engine.submit(OrderEvent.newOrder(buy));

        assertEquals(2, tradeListener.getTrades().size());

        long totalQuantity = tradeListener.getTrades()
                .stream()
                .mapToLong(Trade::getQuantity)
                .sum();

        assertEquals(5L, totalQuantity);
    }

}
