package org.trading.exchange.orderbook;

import org.trading.exchange.event.TradeListener;
import org.trading.exchange.model.*;

import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.ArrayList;


public class OrderBook {

    private final TreeMap<Long, Deque<Order>> buyOrders = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, Deque<Order>> sellOrders = new TreeMap<>();
    private final Map<String, Order> orderIndex = new HashMap<>();
    private final List<TradeListener> tradeListeners = new ArrayList<>();


    public void addOrder(Order order) {
        switch (order.getType()) {
            case MARKET -> handleMarket(order);
            case LIMIT -> handleLimit(order);
            case IOC -> handleIOC(order);
            case FOK -> handleFOK(order);
        }
    }

    private void handleMarket(Order order) {
        if (order.getSide() == OrderSide.BUY) {
            matchMarketBuy(order);
        } else {
            matchMarketSell(order);
        }
    }

    private void handleLimit(Order order) {
        if (order.getSide() == OrderSide.BUY) {
            matchBuy(order);
        } else {
            matchSell(order);
        }
    }

    private void handleFOK(Order order) {
        boolean canFill = order.getSide() == OrderSide.BUY
                ? availableSellLiquidity(order.getPrice()) >= order.getRemainingQuantity()
                : availableBuyLiquidity(order.getPrice()) >= order.getRemainingQuantity();

        if (canFill) {
            if (order.getSide() == OrderSide.BUY) {
                matchBuy(order);
            } else {
                matchSell(order);
            }
        } else {
            order.setState(OrderState.CANCELLED);
            System.out.println("FOK order cancelled due to insufficient liquidity");
        }
    }

    private void handleIOC(Order order) {
        if (order.getSide() == OrderSide.BUY) {
            matchBuy(order);
        } else {
            matchSell(order);
        }

        if (order.getRemainingQuantity() > 0) {
            order.setState(OrderState.CANCELLED);
            System.out.println("IOC remainder cancelled");
        }
    }

    public void cancelOrder(String orderId) {
        Order order = orderIndex.get(orderId);

        if (order == null) {
            System.out.println("Order not found: " + orderId);
            throw new IllegalArgumentException("Order not found: " + orderId);
        }

        TreeMap<Long, Deque<Order>> book = order.getSide() == OrderSide.BUY ? buyOrders : sellOrders;
        Deque<Order> queue = book.get(order.getPrice());

        if (queue != null) {
            queue.remove(order);
            if (queue.isEmpty()) {
                book.remove(order.getPrice());
            }
        }
        order.setState(OrderState.CANCELLED);
        orderIndex.remove(orderId);
        System.out.println("Cancelled order: " + orderId);

    }

    public Map<Long, List<Order>> getBuySnapshot() {
        Map<Long, List<Order>> snapshot = new TreeMap<>(Comparator.reverseOrder());
        for (Map.Entry<Long, Deque<Order>> entry : buyOrders.entrySet()) {
            snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return snapshot;
    }

    public Map<Long, List<Order>> getSellSnapshot() {
        Map<Long, List<Order>> snapshot = new TreeMap<>();
        for (Map.Entry<Long, Deque<Order>> entry : sellOrders.entrySet()) {
            snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return snapshot;
    }

    private void matchBuy(Order order) {
        while (!sellOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Deque<Order> queue = sellOrders.firstEntry().getValue();
            Order sellOrder = queue.peek();

            if (order.getPrice() < sellOrder.getPrice()) break;

            executeTrade(order, sellOrder);
            if (sellOrder.getRemainingQuantity() == 0) {
                queue.poll();
                orderIndex.remove(sellOrder.getId());
                if (queue.isEmpty()) {
                    sellOrders.pollFirstEntry();
                }
            }
        }
        if (order.getRemainingQuantity() > 0 && order.getType() == OrderType.LIMIT) {
            addToBook(buyOrders, order);
        }
    }

    private void matchSell(Order order) {
        while (!buyOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Deque<Order> queue = buyOrders.firstEntry().getValue();
            Order buyOrder = queue.peek();

            if (order.getPrice() > buyOrder.getPrice()) break;
            executeTrade(buyOrder, order);
            if (buyOrder.getRemainingQuantity() == 0) {
                queue.poll();
                orderIndex.remove(buyOrder.getId());
                if (queue.isEmpty()) {
                    buyOrders.pollFirstEntry();
                }
            }
        }
        if (order.getRemainingQuantity() > 0 && order.getType() == OrderType.LIMIT) {
            addToBook(sellOrders, order);
        }

    }

    private void matchMarketBuy(Order order) {
        while (!sellOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Deque<Order> queue = sellOrders.firstEntry().getValue();
            Order bestSell = queue.peek();
            executeTrade(order, bestSell);
            if (bestSell.getRemainingQuantity() == 0) {
                queue.poll();
                orderIndex.remove(bestSell.getId());
                if (queue.isEmpty()) {
                    sellOrders.pollFirstEntry();
                }
            }
        }
        if (order.getRemainingQuantity() > 0) {
            System.out.println("Market buy order partially filled, remaining quantity: " + order.getRemainingQuantity());
            System.out.println("Cancelling remaining quantity");
            order.setState(OrderState.CANCELLED);
        }
    }

    private void matchMarketSell(Order order) {
        while (!buyOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Deque<Order> queue = buyOrders.firstEntry().getValue();
            Order bestBuy = queue.peek();
            executeTrade(bestBuy, order);
            if (bestBuy.getRemainingQuantity() == 0) {
                queue.poll();
                orderIndex.remove(bestBuy.getId());
                if (queue.isEmpty()) {
                    buyOrders.pollFirstEntry();
                }
            }
        }
        if (order.getRemainingQuantity() > 0) {
            System.out.println("Market sell order partially filled, remaining quantity: " + order.getRemainingQuantity());
            System.out.println("Cancelling remaining quantity");
            order.setState(OrderState.CANCELLED);
        }
    }

    private void addToBook(TreeMap<Long, Deque<Order>> book, Order order) {
        book.computeIfAbsent(order.getPrice(), k -> new java.util.LinkedList<>()).offerLast(order);
        orderIndex.put(order.getId(), order);
    }

    private void executeTrade(Order buyOrder, Order sellOrder) {
        long tradeQuantity = Math.min(buyOrder.getRemainingQuantity(), sellOrder.getRemainingQuantity());
        buyOrder.reduceQuantity(tradeQuantity);
        sellOrder.reduceQuantity(tradeQuantity);
        Long tradePrice = sellOrder.getPrice() == null ? buyOrder.getPrice() : sellOrder.getPrice();
        publishTrade(buyOrder, sellOrder, tradePrice, tradeQuantity);
    }

    private void publishTrade(Order buyOrder, Order sellOrder, Long tradePrice, long tradeQuantity) {
        Trade trade = Trade.builder()
                .tradeId(java.util.UUID.randomUUID().toString())
                .buyOrderId(buyOrder.getId())
                .sellOrderId(sellOrder.getId())
                .price(tradePrice)
                .quantity(tradeQuantity)
                .timestamp(System.currentTimeMillis())
                .build();

        for (TradeListener listener : tradeListeners) {
            listener.onTrade(trade);
        }
    }

    private long availableSellLiquidity(Long priceLimit) {
        Long total = 0L;

        for (var entry : sellOrders.entrySet()) {
            if (entry.getKey() > priceLimit) break;

            for (Order o : entry.getValue()) {
                total += o.getRemainingQuantity();
            }
        }

        return total;
    }

    private long availableBuyLiquidity(Long priceLimit) {
        Long total = 0L;

        for (var entry : buyOrders.entrySet()) {
            if (entry.getKey() < priceLimit) break;

            for (Order o : entry.getValue()) {
                total += o.getRemainingQuantity();
            }
        }

        return total;
    }

    public void addTradeListener(TradeListener listener) {
        tradeListeners.add(listener);
    }

    public void printDepth() {

        System.out.println("\n================ ORDER BOOK ================");

        System.out.println("\nSELL SIDE");
        System.out.printf("%-10s %-10s%n", "Price", "Quantity");

        for (var entry : sellOrders.entrySet()) {
            long totalQty = entry.getValue()
                    .stream()
                    .mapToLong(Order::getRemainingQuantity)
                    .sum();

            if (totalQty > 0) {
                System.out.printf("%-10d %-10d%n",
                        entry.getKey(),
                        totalQty);
            }
        }

        System.out.println("\n--------------------------------------------");

        System.out.println("\nBUY SIDE");
        System.out.printf("%-10s %-10s%n", "Price", "Quantity");

        for (var entry : buyOrders.entrySet()) {
            long totalQty = entry.getValue()
                    .stream()
                    .mapToLong(Order::getRemainingQuantity)
                    .sum();

            if (totalQty > 0) {
                System.out.printf("%-10d %-10d%n",
                        entry.getKey(),
                        totalQty);
            }
        }

        System.out.println("\n============================================\n");
    }
}
