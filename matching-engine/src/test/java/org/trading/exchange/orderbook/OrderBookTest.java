package org.trading.exchange.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class OrderBookTest {

    OrderBook orderBook;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook();
    }

    @Test
    @DisplayName("Test adding orders to the order book")
    void testAddOrder() {
        //Given
        orderBook.addOrder(getValidBuyOrderWith(10L, 10L));
        orderBook.addOrder(getValidSellOrderWith(15L, 1L));

        //Then
        assertEquals(1, orderBook.getBuySnapshot().size());
        assertEquals(1, orderBook.getSellSnapshot().size());

    }

    @Test
    @DisplayName("Test matching orders when prices match in the order book")
    void testMatchOrders() {
        //Given
        orderBook.addOrder(getValidBuyOrderWith(8L, 1L));
        orderBook.addOrder(getValidSellOrderWith(8L, 1L));

        //Then
        assertEquals(0, orderBook.getBuySnapshot().size());
        assertEquals(0, orderBook.getSellSnapshot().size());
    }

    @Test
    @DisplayName("Test matching orders when multiple orders are added to the order book")
    void testMatchMultipleOrders() {
        //Given
        orderBook.addOrder(getValidSellOrderWith(10L, 1L));
        orderBook.addOrder(getValidSellOrderWith(9L, 1L));
        orderBook.addOrder(getValidSellOrderWith(10L, 2L));
        orderBook.addOrder(getValidBuyOrderWith(10L, 3L));

        orderBook.printDepth();

        //Then
        assertEquals(0, orderBook.getBuySnapshot().size());
        assertEquals(1, orderBook.getSellSnapshot().size());
    }


    @Test
    @DisplayName("Test matching orders when buy price is more than sell price in the order book")
    void testMatchWithBuyMoreThanSellPrice() {
        //Given
        orderBook.addOrder(getValidBuyOrderWith(10L, 1L));
        orderBook.addOrder(getValidSellOrderWith(8L, 1L));

        //Then
        assertEquals(0, orderBook.getBuySnapshot().size());
        assertEquals(0, orderBook.getSellSnapshot().size());
    }

    @Test
    @DisplayName("Test partial matching of orders in the order book")
    void testPartialMatch() {
        //Given
        orderBook.addOrder(getValidBuyOrderWith(10L, 10L));
        orderBook.addOrder(getValidSellOrderWith(10L, 5L));

        //Then
        assertEquals(1L, orderBook.getBuySnapshot().size());
        assertEquals(0L, orderBook.getSellSnapshot().size());
        assertEquals(5L, orderBook.getBuySnapshot().get(10L).getFirst().getRemainingQuantity());
    }

    @Test
    @DisplayName("Test cancelling an order in the order book")
    void testCancelOrder() {
        //Given
        Order buyOrder = getValidBuyOrderWith(10L, 10L);
        orderBook.addOrder(buyOrder);
        orderBook.cancelOrder(buyOrder.getId());

        //Then
        assertEquals(0, orderBook.getBuySnapshot().size());
    }

    @Test
    @DisplayName("Test cancelling orders that have been partially filled in the order book")
    void testCancelPartiallyFilledOrder() {
        //Given
        Order buyOrder = getValidBuyOrderWith(10L, 10L);
        orderBook.addOrder(buyOrder);
        orderBook.addOrder(getValidSellOrderWith(10L, 5L));
        orderBook.cancelOrder(buyOrder.getId());

        //Then
        assertEquals(0, orderBook.getBuySnapshot().size());
    }

    @Test
    @DisplayName("Test cancelling an order that has already been fully filled in the order book")
    void testCancelFullyFilledOrder() {
        //Given
        Order buyOrder = getValidBuyOrderWith(10L, 10L);
        orderBook.addOrder(buyOrder);
        orderBook.addOrder(getValidSellOrderWith(10L, 10L));

        //Then
        try {
            orderBook.cancelOrder(buyOrder.getId());
        } catch (IllegalArgumentException e) {
            assertEquals("Order not found: " + buyOrder.getId(), e.getMessage());
        }
    }

    @Test
    @DisplayName("Test cancelling a non-existent order in the order book")
    void testCancelNonExistentOrder() {
        //Given
        String nonExistentOrderId = UUID.randomUUID().toString();

        //Then
        try {
            orderBook.cancelOrder(nonExistentOrderId);
        } catch (IllegalArgumentException e) {
            assertEquals("Order not found: " + nonExistentOrderId, e.getMessage());
        }
    }


    private Order getValidBuyOrderWith(Long price, Long quantity) {
        return Order.builder()
                .id(UUID.randomUUID().toString())
                .side(OrderSide.BUY)
                .price(price)
                .userId("user1")
                .remainingQuantity(quantity)
                .timestamp(Instant.EPOCH)
                .build();
    }

    private Order getValidSellOrderWith(Long price, Long quantity) {
        return Order.builder()
                .id(UUID.randomUUID().toString())
                .userId("user2")
                .side(OrderSide.SELL)
                .price(price)
                .remainingQuantity(quantity)
                .timestamp(Instant.EPOCH)
                .build();
    }

}
