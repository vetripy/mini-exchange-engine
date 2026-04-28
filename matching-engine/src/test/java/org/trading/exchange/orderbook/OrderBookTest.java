package org.trading.exchange.orderbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.trading.exchange.model.OrderState.CANCELLED;
import static org.trading.exchange.model.OrderState.FILLED;
import static org.trading.exchange.stub.OrderStub.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.trading.exchange.model.Order;

public class OrderBookTest {

  OrderBook orderBook;
  long sequence = 0L;

  @BeforeEach
  void setUp() {
    orderBook = new OrderBook();
  }

  @Test
  @DisplayName("Test adding orders to the order book")
  void testAddOrder() {
    // Given
    orderBook.addOrder(getValidLimitBuyOrderWith(10L, 10L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(15L, 1L), ++sequence);

    // Then
    assertEquals(1, orderBook.getBuySnapshot().size());
    assertEquals(1, orderBook.getSellSnapshot().size());
  }

  @Test
  @DisplayName("Test matching orders when prices match in the order book")
  void testMatchOrders() {
    // Given
    orderBook.addOrder(getValidLimitBuyOrderWith(8L, 1L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(8L, 1L), ++sequence);

    // Then
    assertEquals(0, orderBook.getBuySnapshot().size());
    assertEquals(0, orderBook.getSellSnapshot().size());
  }

  @Test
  @DisplayName("Test matching orders when multiple orders are added to the order book")
  void testMatchMultipleOrders() {
    // Given
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 1L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(9L, 1L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 2L), ++sequence);
    orderBook.addOrder(getValidLimitBuyOrderWith(10L, 3L), ++sequence);

    orderBook.displayBook();

    // Then
    assertEquals(0, orderBook.getBuySnapshot().size());
    assertEquals(1, orderBook.getSellSnapshot().size());
  }

  @Test
  @DisplayName("Test matching orders when buy price is more than sell price in the order book")
  void testMatchWithBuyMoreThanSellPrice() {
    // Given
    orderBook.addOrder(getValidLimitBuyOrderWith(10L, 1L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(8L, 1L), ++sequence);

    // Then
    assertEquals(0, orderBook.getBuySnapshot().size());
    assertEquals(0, orderBook.getSellSnapshot().size());
  }

  @Test
  @DisplayName("Test partial matching of orders in the order book")
  void testPartialMatch() {
    // Given
    orderBook.addOrder(getValidLimitBuyOrderWith(10L, 10L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 5L), ++sequence);

    // Then
    assertEquals(1L, orderBook.getBuySnapshot().size());
    assertEquals(0L, orderBook.getSellSnapshot().size());
    assertEquals(5L, orderBook.getBuySnapshot().get(10L).getFirst().getRemainingQuantity());
  }

  @Test
  @DisplayName("Test cancelling an order in the order book")
  void testCancelOrder() {
    // Given
    Order buyOrder = getValidLimitBuyOrderWith(10L, 10L);
    orderBook.addOrder(buyOrder, ++sequence);
    orderBook.cancelOrder(buyOrder.getOrderId(), ++sequence);

    // Then
    assertEquals(0, orderBook.getBuySnapshot().size());
  }

  @Test
  @DisplayName("Test cancelling orders that have been partially filled in the order book")
  void testCancelPartiallyFilledOrder() {
    // Given
    Order buyOrder = getValidLimitBuyOrderWith(10L, 10L);
    orderBook.addOrder(buyOrder, ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 5L), ++sequence);
    orderBook.cancelOrder(buyOrder.getOrderId(), ++sequence);

    // Then
    assertEquals(0, orderBook.getBuySnapshot().size());
  }

  @Test
  @DisplayName("Test cancelling an order that has already been fully filled in the order book")
  void testCancelFullyFilledOrder() {
    // Given
    Order buyOrder = getValidLimitBuyOrderWith(10L, 10L);
    orderBook.addOrder(buyOrder, ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 10L), ++sequence);

    // Then
    try {
      orderBook.cancelOrder(buyOrder.getOrderId(), ++sequence);
    } catch (IllegalArgumentException e) {
      assertEquals("Order not found: " + buyOrder.getOrderId(), e.getMessage());
    }
  }

  @Test
  @DisplayName("Test cancelling a non-existent order in the order book")
  void testCancelNonExistentOrder() {
    // Given
    String nonExistentOrderId = UUID.randomUUID().toString();

    // Then
    try {
      orderBook.cancelOrder(nonExistentOrderId, ++sequence);
    } catch (IllegalArgumentException e) {
      assertEquals("Order not found: " + nonExistentOrderId, e.getMessage());
    }
  }

  @Test
  @DisplayName("Test market orders in the order book")
  void testMarketOrders() {
    // build a book using limit orders
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 5L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(11L, 5L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(12L, 5L), ++sequence);
    orderBook.addOrder(getValidLimitBuyOrderWith(9L, 5L), ++sequence);
    orderBook.addOrder(getValidLimitBuyOrderWith(8L, 5L), ++sequence);

    // check book depth
    assertEquals(2, orderBook.getBuySnapshot().size());
    assertEquals(3, orderBook.getSellSnapshot().size());

    // add a market buy order and check if the order is filled
    Order marketBuyOrder = getValidMarketBuyOrderWith(2L);
    orderBook.addOrder(marketBuyOrder, ++sequence);
    assertEquals(FILLED, marketBuyOrder.getState());

    // add a market sell order and check if the order is filled
    Order marketSellOrder = getValidMarketSellOrderWith(2L);
    orderBook.addOrder(marketSellOrder, ++sequence);
    assertEquals(FILLED, marketSellOrder.getState());
  }

  @Test
  @DisplayName("Test market orders that partially fill in the order book")
  void testPartialMarketOrders() {
    // build a book using limit orders
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 5L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(11L, 5L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(12L, 5L), ++sequence);
    orderBook.addOrder(getValidLimitBuyOrderWith(9L, 5L), ++sequence);

    // add a market buy order that partially fills and check the remaining quantity
    Order marketBuyOrder = getValidMarketBuyOrderWith(20L);
    orderBook.addOrder(marketBuyOrder, ++sequence);
    assertEquals(5L, marketBuyOrder.getRemainingQuantity());
    assertEquals(CANCELLED, marketBuyOrder.getState());

    // add a market sell order that partially fills and check the remaining quantity
    Order marketSellOrder = getValidMarketSellOrderWith(12L);
    orderBook.addOrder(marketSellOrder, ++sequence);
    assertEquals(7L, marketSellOrder.getRemainingQuantity());
    assertEquals(CANCELLED, marketSellOrder.getState());
  }

  @Test
  @DisplayName("IOC order partially fills then cancels remainder")
  void testIOCPartialFillCancelsRemainder() {
    // build sell side depth
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 3L), ++sequence);

    // add IOC buy for 5 @ 10 -> should fill 3 and cancel remaining 2
    Order iocBuy = getValidIOCBuyOrderWith(10L, 5L);
    orderBook.addOrder(iocBuy, ++sequence);

    assertEquals(CANCELLED, iocBuy.getState());
    assertEquals(2L, iocBuy.getRemainingQuantity());
  }

  @Test
  @DisplayName("IOC order fully fills when liquidity is available")
  void testIOCFullFill() {
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 5L), ++sequence);

    Order iocBuy = getValidIOCBuyOrderWith(10L, 5L);
    orderBook.addOrder(iocBuy, ++sequence);

    assertEquals(FILLED, iocBuy.getState());
    assertEquals(0L, iocBuy.getRemainingQuantity());
  }

  @Test
  @DisplayName("FOK order cancels when insufficient liquidity at price")
  void testFOKCancelsWhenInsufficient() {
    // only 3 available at price 10
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 3L), ++sequence);

    Order fokBuy = getValidFOKBuyOrderWith(10L, 5L);
    orderBook.addOrder(fokBuy, ++sequence);

    assertEquals(CANCELLED, fokBuy.getState());
    assertEquals(5L, fokBuy.getRemainingQuantity());
  }

  @Test
  @DisplayName("FOK order fills fully when sufficient liquidity")
  void testFOKFillsWhenSufficient() {
    // 5 available at price 10
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 2L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 3L), ++sequence);

    Order fokBuy = getValidFOKBuyOrderWith(10L, 5L);
    orderBook.addOrder(fokBuy, ++sequence);

    assertEquals(FILLED, fokBuy.getState());
    assertEquals(0L, fokBuy.getRemainingQuantity());
  }

  @Test
  @DisplayName("Market buy order with no liquidity gets cancelled")
  void testMarketBuyWithNoLiquidity() {
    Order marketBuy = getValidMarketBuyOrderWith(5L);
    orderBook.addOrder(marketBuy, ++sequence);

    assertEquals(CANCELLED, marketBuy.getState());
    assertEquals(5L, marketBuy.getRemainingQuantity());
  }

  @Test
  @DisplayName("Market sell order with no liquidity gets cancelled")
  void testMarketSellWithNoLiquidity() {
    Order marketSell = getValidMarketSellOrderWith(5L);
    orderBook.addOrder(marketSell, ++sequence);

    assertEquals(CANCELLED, marketSell.getState());
    assertEquals(5L, marketSell.getRemainingQuantity());
  }

  @Test
  @DisplayName("IOC sell order partially fills then cancels remainder")
  void testIOCPartialFillSellCancelsRemainder() {
    orderBook.addOrder(getValidLimitBuyOrderWith(10L, 3L), ++sequence);

    Order iocSell = getValidIOCSellOrderWith(10L, 5L);
    orderBook.addOrder(iocSell, ++sequence);

    assertEquals(CANCELLED, iocSell.getState());
    assertEquals(2L, iocSell.getRemainingQuantity());
  }

  @Test
  @DisplayName("IOC sell order fully fills when liquidity is available")
  void testIOCSellFullFill() {
    orderBook.addOrder(getValidLimitBuyOrderWith(10L, 5L), ++sequence);

    Order iocSell = getValidIOCSellOrderWith(10L, 5L);
    orderBook.addOrder(iocSell, ++sequence);

    assertEquals(FILLED, iocSell.getState());
    assertEquals(0L, iocSell.getRemainingQuantity());
  }

  @Test
  @DisplayName("IOC buy order with no liquidity gets cancelled")
  void testIOCBuyWithNoLiquidity() {
    Order iocBuy = getValidIOCBuyOrderWith(10L, 5L);
    orderBook.addOrder(iocBuy, ++sequence);

    assertEquals(CANCELLED, iocBuy.getState());
    assertEquals(5L, iocBuy.getRemainingQuantity());
  }

  @Test
  @DisplayName("IOC sell order with no liquidity gets cancelled")
  void testIOCSellWithNoLiquidity() {
    Order iocSell = getValidIOCSellOrderWith(10L, 5L);
    orderBook.addOrder(iocSell, ++sequence);

    assertEquals(CANCELLED, iocSell.getState());
    assertEquals(5L, iocSell.getRemainingQuantity());
  }

  @Test
  @DisplayName("FOK buy order with no liquidity gets cancelled")
  void testFOKBuyWithNoLiquidity() {
    Order fokBuy = getValidFOKBuyOrderWith(10L, 5L);
    orderBook.addOrder(fokBuy, ++sequence);

    assertEquals(CANCELLED, fokBuy.getState());
    assertEquals(5L, fokBuy.getRemainingQuantity());
  }

  @Test
  @DisplayName("FOK sell order with no liquidity gets cancelled")
  void testFOKSellWithNoLiquidity() {
    Order fokSell = getValidFOKSellOrderWith(10L, 5L);
    orderBook.addOrder(fokSell, ++sequence);

    assertEquals(CANCELLED, fokSell.getState());
    assertEquals(5L, fokSell.getRemainingQuantity());
  }

  @Test
  @DisplayName("FOK sell order cancels when insufficient liquidity")
  void testFOKSellCancelsWhenInsufficient() {
    // only 3 available at price 10
    orderBook.addOrder(getValidLimitBuyOrderWith(10L, 3L), ++sequence);

    Order fokSell = getValidFOKSellOrderWith(10L, 5L);
    orderBook.addOrder(fokSell, ++sequence);

    assertEquals(CANCELLED, fokSell.getState());
    assertEquals(5L, fokSell.getRemainingQuantity());
  }

  @Test
  @DisplayName("FOK sell order fills fully when sufficient liquidity")
  void testFOKSellFillsWhenSufficient() {
    // 5 available at price 10
    orderBook.addOrder(getValidLimitBuyOrderWith(10L, 2L), ++sequence);
    orderBook.addOrder(getValidLimitBuyOrderWith(10L, 3L), ++sequence);

    Order fokSell = getValidFOKSellOrderWith(10L, 5L);
    orderBook.addOrder(fokSell, ++sequence);

    assertEquals(FILLED, fokSell.getState());
    assertEquals(0L, fokSell.getRemainingQuantity());
  }

  @Test
  @DisplayName("Multiple orders at same price level maintain FIFO order")
  void testFIFOOrderingAtPriceLevel() {
    Order buy1 = getValidLimitBuyOrderWith(10L, 2L);
    Order buy2 = getValidLimitBuyOrderWith(10L, 3L);

    orderBook.addOrder(buy1, ++sequence);
    orderBook.addOrder(buy2, ++sequence);

    Order sell = getValidLimitSellOrderWith(10L, 2L);
    orderBook.addOrder(sell, ++sequence);

    assertEquals(0L, buy1.getRemainingQuantity());
    assertEquals(3L, buy2.getRemainingQuantity());
  }

  @Test
  @DisplayName("Market order fills across multiple price levels")
  void testMarketOrderAcrossMultiplePriceLevels() {
    orderBook.addOrder(getValidLimitSellOrderWith(10L, 2L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(11L, 2L), ++sequence);
    orderBook.addOrder(getValidLimitSellOrderWith(12L, 2L), ++sequence);

    Order marketBuy = getValidMarketBuyOrderWith(5L);
    orderBook.addOrder(marketBuy, ++sequence);

    assertEquals(FILLED, marketBuy.getState());
    assertEquals(0L, marketBuy.getRemainingQuantity());
  }
}
