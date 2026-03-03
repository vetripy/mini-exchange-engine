package org.trading.exchange.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.trading.exchange.event.EngineEventHandler;
import org.trading.exchange.model.Order;
import org.trading.exchange.event.OrderUpdate;
import org.trading.exchange.model.Trade;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.trading.exchange.model.OrderState.CANCELLED;
import static org.trading.exchange.model.OrderState.FILLED;
import static org.trading.exchange.stub.OrderStub.*;

public class OrderBookTest {

    OrderBook orderBook;

    @BeforeEach
    void setUp() {
        orderBook = new OrderBook(new EngineEventHandler() {
            @Override
            public void onTrade(Trade event) {

            }

            @Override
            public void onOrderUpdate(OrderUpdate event) {

            }
        });
    }

    @Test
    @DisplayName("Test adding orders to the order book")
    void testAddOrder() {
        //Given
        orderBook.addOrder(getValidLimitBuyOrderWith(10L, 10L));
        orderBook.addOrder(getValidLimitSellOrderWith(15L, 1L));

        //Then
        assertEquals(1, orderBook.getBuySnapshot().size());
        assertEquals(1, orderBook.getSellSnapshot().size());

    }

    @Test
    @DisplayName("Test matching orders when prices match in the order book")
    void testMatchOrders() {
        //Given
        orderBook.addOrder(getValidLimitBuyOrderWith(8L, 1L));
        orderBook.addOrder(getValidLimitSellOrderWith(8L, 1L));

        //Then
        assertEquals(0, orderBook.getBuySnapshot().size());
        assertEquals(0, orderBook.getSellSnapshot().size());
    }

    @Test
    @DisplayName("Test matching orders when multiple orders are added to the order book")
    void testMatchMultipleOrders() {
        //Given
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 1L));
        orderBook.addOrder(getValidLimitSellOrderWith(9L, 1L));
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 2L));
        orderBook.addOrder(getValidLimitBuyOrderWith(10L, 3L));

        orderBook.displayBook();

        //Then
        assertEquals(0, orderBook.getBuySnapshot().size());
        assertEquals(1, orderBook.getSellSnapshot().size());
    }


    @Test
    @DisplayName("Test matching orders when buy price is more than sell price in the order book")
    void testMatchWithBuyMoreThanSellPrice() {
        //Given
        orderBook.addOrder(getValidLimitBuyOrderWith(10L, 1L));
        orderBook.addOrder(getValidLimitSellOrderWith(8L, 1L));

        //Then
        assertEquals(0, orderBook.getBuySnapshot().size());
        assertEquals(0, orderBook.getSellSnapshot().size());
    }

    @Test
    @DisplayName("Test partial matching of orders in the order book")
    void testPartialMatch() {
        //Given
        orderBook.addOrder(getValidLimitBuyOrderWith(10L, 10L));
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 5L));

        //Then
        assertEquals(1L, orderBook.getBuySnapshot().size());
        assertEquals(0L, orderBook.getSellSnapshot().size());
        assertEquals(5L, orderBook.getBuySnapshot().get(10L).getFirst().getRemainingQuantity());
    }

    @Test
    @DisplayName("Test cancelling an order in the order book")
    void testCancelOrder() {
        //Given
        Order buyOrder = getValidLimitBuyOrderWith(10L, 10L);
        orderBook.addOrder(buyOrder);
        orderBook.cancelOrder(buyOrder.getOrderId());

        //Then
        assertEquals(0, orderBook.getBuySnapshot().size());
    }

    @Test
    @DisplayName("Test cancelling orders that have been partially filled in the order book")
    void testCancelPartiallyFilledOrder() {
        //Given
        Order buyOrder = getValidLimitBuyOrderWith(10L, 10L);
        orderBook.addOrder(buyOrder);
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 5L));
        orderBook.cancelOrder(buyOrder.getOrderId());

        //Then
        assertEquals(0, orderBook.getBuySnapshot().size());
    }

    @Test
    @DisplayName("Test cancelling an order that has already been fully filled in the order book")
    void testCancelFullyFilledOrder() {
        //Given
        Order buyOrder = getValidLimitBuyOrderWith(10L, 10L);
        orderBook.addOrder(buyOrder);
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 10L));

        //Then
        try {
            orderBook.cancelOrder(buyOrder.getOrderId());
        } catch (IllegalArgumentException e) {
            assertEquals("Order not found: " + buyOrder.getOrderId(), e.getMessage());
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

    @Test
    @DisplayName("Test market orders in the order book")
    void testMarketOrders() {
        // build a book using limit orders
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 5L));
        orderBook.addOrder(getValidLimitSellOrderWith(11L, 5L));
        orderBook.addOrder(getValidLimitSellOrderWith(12L, 5L));
        orderBook.addOrder(getValidLimitBuyOrderWith(9L, 5L));
        orderBook.addOrder(getValidLimitBuyOrderWith(8L, 5L));

        // check book depth
        assertEquals(2, orderBook.getBuySnapshot().size());
        assertEquals(3, orderBook.getSellSnapshot().size());

        // add a market buy order and check if the order is filled
        Order marketBuyOrder = getValidMarketBuyOrderWith(2L);
        orderBook.addOrder(marketBuyOrder);
        assertEquals(FILLED, marketBuyOrder.getState());

        // add a market sell order and check if the order is filled
        Order marketSellOrder = getValidMarketSellOrderWith(2L);
        orderBook.addOrder(marketSellOrder);
        assertEquals(FILLED, marketSellOrder.getState());
    }

    @Test
    @DisplayName("Test market orders that partially fill in the order book")
    void testPartialMarketOrders() {
        // build a book using limit orders
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 5L));
        orderBook.addOrder(getValidLimitSellOrderWith(11L, 5L));
        orderBook.addOrder(getValidLimitSellOrderWith(12L, 5L));
        orderBook.addOrder(getValidLimitBuyOrderWith(9L, 5L));

        // add a market buy order that partially fills and check the remaining quantity
        Order marketBuyOrder = getValidMarketBuyOrderWith(20L);
        orderBook.addOrder(marketBuyOrder);
        assertEquals(5L, marketBuyOrder.getRemainingQuantity());
        assertEquals(CANCELLED, marketBuyOrder.getState());

        // add a market sell order that partially fills and check the remaining quantity
        Order marketSellOrder = getValidMarketSellOrderWith(12L);
        orderBook.addOrder(marketSellOrder);
        assertEquals(7L, marketSellOrder.getRemainingQuantity());
        assertEquals(CANCELLED, marketSellOrder.getState());
    }

    @Test
    @DisplayName("IOC order partially fills then cancels remainder")
    void testIOCPartialFillCancelsRemainder() {
        // build sell side depth
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 3L));

        // add IOC buy for 5 @ 10 -> should fill 3 and cancel remaining 2
        Order iocBuy = getValidIOCBuyOrderWith(10L, 5L);
        orderBook.addOrder(iocBuy);

        assertEquals(CANCELLED, iocBuy.getState());
        assertEquals(2L, iocBuy.getRemainingQuantity());
    }

    @Test
    @DisplayName("IOC order fully fills when liquidity is available")
    void testIOCFullFill() {
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 5L));

        Order iocBuy = getValidIOCBuyOrderWith(10L, 5L);
        orderBook.addOrder(iocBuy);

        assertEquals(FILLED, iocBuy.getState());
        assertEquals(0L, iocBuy.getRemainingQuantity());
    }

    @Test
    @DisplayName("FOK order cancels when insufficient liquidity at price")
    void testFOKCancelsWhenInsufficient() {
        // only 3 available at price 10
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 3L));

        Order fokBuy = getValidFOKBuyOrderWith(10L, 5L);
        orderBook.addOrder(fokBuy);

        assertEquals(CANCELLED, fokBuy.getState());
        assertEquals(5L, fokBuy.getRemainingQuantity());
    }

    @Test
    @DisplayName("FOK order fills fully when sufficient liquidity")
    void testFOKFillsWhenSufficient() {
        // 5 available at price 10
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 2L));
        orderBook.addOrder(getValidLimitSellOrderWith(10L, 3L));

        Order fokBuy = getValidFOKBuyOrderWith(10L, 5L);
        orderBook.addOrder(fokBuy);

        assertEquals(FILLED, fokBuy.getState());
        assertEquals(0L, fokBuy.getRemainingQuantity());
    }
}
