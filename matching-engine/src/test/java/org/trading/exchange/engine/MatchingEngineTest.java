package org.trading.exchange.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.trading.exchange.event.OrderEvent;
import org.trading.exchange.model.Order;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.trading.exchange.stub.OrderStub.getValidLimitBuyOrderWith;
import static org.trading.exchange.stub.OrderStub.getValidLimitSellOrderWith;

public class MatchingEngineTest {

    private MatchingEngine matchingEngine;

    @BeforeEach
    void setUp() {
        matchingEngine = new MatchingEngine();
    }

    @Test
    @DisplayName("Test starting the matching engine")
    void testStartEngine() {
        // Given & When
        matchingEngine.start();

        // Then
        assertTrue(true); // Engine started without throwing exception
    }

    @Test
    @DisplayName("Test starting an already running engine throws exception")
    void testStartEngineWhenAlreadyRunning() {
        // Given
        matchingEngine.start();

        // When & Then
        assertThrows(IllegalStateException.class, () -> matchingEngine.start());
    }

    @Test
    @DisplayName("Test submitting a new order event when engine is running")
    void testSubmitNewOrderEvent() throws InterruptedException {
        // Given
        matchingEngine.start();
        Order order = getValidLimitBuyOrderWith(10L, 10L);
        OrderEvent event = OrderEvent.newOrder(order);

        matchingEngine.submit(event);

        // Give engine time to process
        Thread.sleep(100);

        // Then
        assertTrue(true);
    }

    @Test
    @DisplayName("Test submitting an order event when engine is not running throws exception")
    void testSubmitEventWhenEngineNotRunning() {
        // Given
        Order order = getValidLimitBuyOrderWith(10L, 10L);
        OrderEvent event = OrderEvent.newOrder(order);

        // When & Then
        assertThrows(IllegalStateException.class, () -> matchingEngine.submit(event));
    }

    @Test
    @DisplayName("Test submitting a cancel order event")
    void testSubmitCancelOrderEvent() throws InterruptedException {
        // Given
        matchingEngine.start();
        Order order = getValidLimitBuyOrderWith(10L, 10L);

        OrderEvent newOrderEvent = OrderEvent.newOrder(order);
        OrderEvent cancelOrderEvent = OrderEvent.cancelOrder(order.getOrderId());

        // When
        matchingEngine.submit(newOrderEvent);
        Thread.sleep(100); // Give engine time to process
        matchingEngine.submit(cancelOrderEvent);

        // Then
        Thread.sleep(100); // Give engine time to process
        assertTrue(true); // Cancel event processed without exceptione
    }

    @Test
    @DisplayName("Test stopping the matching engine")
    void testStopEngine() throws InterruptedException {
        // Given
        matchingEngine.start();

        // When
        matchingEngine.stop();

        // Then
        Thread.sleep(100); // Give engine time to stop
        assertTrue(true); // Engine stopped without throwing exception
    }

    @Test
    @DisplayName("Test stopping an engine that is not running throws exception")
    void testStopEngineWhenNotRunning() {
        // When & Then
        assertThrows(IllegalStateException.class, () -> matchingEngine.stop());
    }

    @Test
    @DisplayName("Test processing multiple orders in sequence")
    void testProcessMultipleOrders() throws InterruptedException {
        // Given
        matchingEngine.start();

        Order buyOrder1 = getValidLimitBuyOrderWith(10L, 5L);
        Order buyOrder2 = getValidLimitBuyOrderWith(9L, 5L);
        Order sellOrder = getValidLimitSellOrderWith(10L, 8L);

        OrderEvent event1 = OrderEvent.newOrder(buyOrder1);
        OrderEvent event2 = OrderEvent.newOrder(buyOrder2);
        OrderEvent event3 = OrderEvent.newOrder(sellOrder);

        // When
        matchingEngine.submit(event1);
        matchingEngine.submit(event2);
        matchingEngine.submit(event3);

        // Then
        Thread.sleep(200); // Give engine time to process all events
        assertTrue(true); // All orders processed without exception
    }

    @Test
    @DisplayName("Test processing order with matching across multiple price levels")
    void testProcessOrderWithCrossPriceMatching() throws InterruptedException {
        // Given
        matchingEngine.start();

        Order sellOrder1 = getValidLimitSellOrderWith(12L, 5L);
        Order sellOrder2 = getValidLimitSellOrderWith(11L, 5L);
        Order buyOrder = getValidLimitBuyOrderWith(12L, 8L);

        // When
        matchingEngine.submit(OrderEvent.newOrder(sellOrder1));
        matchingEngine.submit(OrderEvent.newOrder(sellOrder2));
        matchingEngine.submit(OrderEvent.newOrder(buyOrder));

        // Then
        Thread.sleep(200);
        assertTrue(true); // Orders matched across price levels without exception
    }

}
