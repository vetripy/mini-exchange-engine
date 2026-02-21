package org.trading.exchange.orderbook;

import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;

import java.util.Comparator;
import java.util.Deque;
import java.util.TreeMap;

public class OrderBook {

    private final TreeMap<Long, Deque<Order>> buyOrders = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, Deque<Order>> sellOrders = new TreeMap<>();

    public void addOrder(Order order) {
        if (order.getSide() == OrderSide.BUY) {
            matchBuy(order);
        } else {
            matchSell(order);
        }
    }

    private void matchBuy(Order order) {
        while (!sellOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Deque<Order> queue = sellOrders.firstEntry().getValue();
            Order sellOrder = queue.peek();

            if (order.getPrice() >= sellOrder.getPrice()) {
                executeTrade(order, sellOrder);
                if (sellOrder.getRemainingQuantity() == 0) {
                    queue.poll();
                    if (queue.isEmpty()) {
                        sellOrders.pollFirstEntry();
                    }
                }
            }
        }
        if (order.getRemainingQuantity() > 0) {
            addToBook(buyOrders, order);
        }
    }

    private void matchSell(Order order) {
        while (!buyOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Deque<Order> queue = buyOrders.firstEntry().getValue();
            Order buyOrder = queue.peek();

            if (order.getPrice() <= buyOrder.getPrice()) {
                executeTrade(buyOrder, order);
                if (buyOrder.getRemainingQuantity() == 0) {
                    queue.poll();
                    if (queue.isEmpty()) {
                        buyOrders.pollFirstEntry();
                    }
                }
            }
        }
        if (order.getRemainingQuantity() > 0) {
            addToBook(sellOrders, order);
        }

    }

    private void addToBook(TreeMap<Long, Deque<Order>> book, Order order) {
        book.computeIfAbsent(order.getPrice(), k -> new java.util.LinkedList<>()).offerLast(order);
    }

    private void executeTrade(Order buyOrder, Order sellOrder) {
        long tradeQuantity = Math.min(buyOrder.getRemainingQuantity(), sellOrder.getRemainingQuantity());
        buyOrder.reduceQuantity(tradeQuantity);
        sellOrder.reduceQuantity(tradeQuantity);
        System.out.println("Executed trade: " + tradeQuantity + " @ " + sellOrder.getPrice());
    }
}
