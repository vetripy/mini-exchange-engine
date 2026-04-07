package org.trading.exchange.orderbook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.trading.exchange.event.EngineEvent;
import org.trading.exchange.event.OrderUpdateEvent;
import org.trading.exchange.event.TradeEvent;
import org.trading.exchange.model.*;
import org.trading.exchange.util.OrderBookUtil;

@Slf4j
public class OrderBook {

    private final TreeMap<Long, Deque<Order>> buyOrders = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, Deque<Order>> sellOrders = new TreeMap<>();
    private final Map<String, Order> orderIndex = new HashMap<>();

    public List<EngineEvent> addOrder(Order order, long seq) {
        MatchContext ctx = new MatchContext(seq);
        emitOrderUpdate(order, ctx);
        switch (order.getType()) {
            case MARKET -> handleMarket(order, ctx);
            case LIMIT -> handleLimit(order, ctx);
            case IOC -> handleIOC(order, ctx);
            case FOK -> handleFOK(order, ctx);
        }
        return ctx.getEvents();
    }

    public List<EngineEvent> cancelOrder(String orderId, long seq) {
        MatchContext ctx = new MatchContext(seq);
        Order order = orderIndex.get(orderId);

        if (order == null) {
            log.warn("Order not found: {}", orderId);
            throw new IllegalArgumentException("Order not found: " + orderId);
        }

        TreeMap<Long, Deque<Order>> book =
            order.getSide() == OrderSide.BUY ? buyOrders : sellOrders;
        Deque<Order> queue = book.get(order.getPrice());

        if (queue != null) {
            queue.remove(order);
            if (queue.isEmpty()) {
                book.remove(order.getPrice());
            }
        }
        order.setState(OrderState.CANCELLED);
        emitOrderUpdate(order, ctx);
        orderIndex.remove(orderId);
        log.info("Order cancelled with orderId: {}", orderId);
        return ctx.getEvents();
    }

    private void matchBuy(Order order, MatchContext ctx) {
        while (!sellOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Deque<Order> queue = sellOrders.firstEntry().getValue();
            Order sellOrder = queue.peek();

            if (order.getPrice() < sellOrder.getPrice()) {
                break;
            }

            executeTrade(order, sellOrder, ctx);
            if (sellOrder.getRemainingQuantity() == 0) {
                queue.poll();
                orderIndex.remove(sellOrder.getOrderId());
                if (queue.isEmpty()) {
                    sellOrders.pollFirstEntry();
                }
            }
        }
        if (order.getRemainingQuantity() > 0 && order.getType() == OrderType.LIMIT) {
            addToBook(buyOrders, order);
        }
    }

    private void matchSell(Order order, MatchContext ctx) {
        while (!buyOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Deque<Order> queue = buyOrders.firstEntry().getValue();
            Order buyOrder = queue.peek();

            if (order.getPrice() > buyOrder.getPrice()) {
                break;
            }
            executeTrade(buyOrder, order, ctx);
            if (buyOrder.getRemainingQuantity() == 0) {
                queue.poll();
                orderIndex.remove(buyOrder.getOrderId());
                if (queue.isEmpty()) {
                    buyOrders.pollFirstEntry();
                }
            }
        }
        if (order.getRemainingQuantity() > 0 && order.getType() == OrderType.LIMIT) {
            addToBook(sellOrders, order);
        }
    }

    private void matchMarketBuy(Order order, MatchContext ctx) {
        while (!sellOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Deque<Order> queue = sellOrders.firstEntry().getValue();
            Order bestSell = queue.peek();
            executeTrade(order, bestSell, ctx);
            if (bestSell.getRemainingQuantity() == 0) {
                queue.poll();
                orderIndex.remove(bestSell.getOrderId());
                if (queue.isEmpty()) {
                    sellOrders.pollFirstEntry();
                }
            }
        }
        if (order.getRemainingQuantity() > 0) {
            log.info("Market buy order partially filled, remaining quantity: {}",
                order.getRemainingQuantity());
            log.info("Cancelling remaining quantity");
            order.setState(OrderState.CANCELLED);
            emitOrderUpdate(order, ctx);
        }
    }

    private void matchMarketSell(Order order, MatchContext ctx) {
        while (!buyOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Deque<Order> queue = buyOrders.firstEntry().getValue();
            Order bestBuy = queue.peek();
            executeTrade(bestBuy, order, ctx);
            if (bestBuy.getRemainingQuantity() == 0) {
                queue.poll();
                orderIndex.remove(bestBuy.getOrderId());
                if (queue.isEmpty()) {
                    buyOrders.pollFirstEntry();
                }
            }
        }
        if (order.getRemainingQuantity() > 0) {
            log.info("Market sell order partially filled, remaining quantity: {}",
                order.getRemainingQuantity());
            log.info("Cancelling remaining quantity");
            order.setState(OrderState.CANCELLED);
            emitOrderUpdate(order, ctx);
        }
    }

    private void handleMarket(Order order, MatchContext ctx) {
        if (order.getSide() == OrderSide.BUY) {
            matchMarketBuy(order, ctx);
        } else {
            matchMarketSell(order, ctx);
        }
    }

    private void handleLimit(Order order, MatchContext ctx) {
        if (order.getSide() == OrderSide.BUY) {
            matchBuy(order, ctx);
        } else {
            matchSell(order, ctx);
        }
    }

    private void handleFOK(Order order, MatchContext ctx) {
        boolean canFill = order.getSide() == OrderSide.BUY
            ? availableSellLiquidity(order.getPrice()) >= order.getRemainingQuantity()
            : availableBuyLiquidity(order.getPrice()) >= order.getRemainingQuantity();

        if (canFill) {
            if (order.getSide() == OrderSide.BUY) {
                matchBuy(order, ctx);
            } else {
                matchSell(order, ctx);
            }
        } else {
            order.setState(OrderState.CANCELLED);
            emitOrderUpdate(order, ctx);
            log.info("FOK order cancelled due to insufficient liquidity");
        }
    }

    private void handleIOC(Order order, MatchContext ctx) {
        if (order.getSide() == OrderSide.BUY) {
            matchBuy(order, ctx);
        } else {
            matchSell(order, ctx);
        }

        if (order.getRemainingQuantity() > 0) {
            order.setState(OrderState.CANCELLED);
            emitOrderUpdate(order, ctx);
            log.info("IOC order remainder cancelled");
        }
    }

    private void addToBook(TreeMap<Long, Deque<Order>> book, Order order) {
        book.computeIfAbsent(order.getPrice(), k -> new java.util.LinkedList<>()).offerLast(order);
        orderIndex.put(order.getOrderId(), order);
    }

    private void executeTrade(Order buyOrder, Order sellOrder, MatchContext ctx) {
        long tradeQuantity =
            Math.min(buyOrder.getRemainingQuantity(), sellOrder.getRemainingQuantity());
        buyOrder.reduceQuantity(tradeQuantity);
        sellOrder.reduceQuantity(tradeQuantity);
        Long tradePrice = sellOrder.getPrice() == null ? buyOrder.getPrice() : sellOrder.getPrice();
        emitOrderUpdate(buyOrder, ctx);
        emitOrderUpdate(sellOrder, ctx);
        emitTrade(buyOrder, sellOrder, tradePrice, tradeQuantity, ctx);
    }

    private long availableSellLiquidity(Long priceLimit) {
        long total = 0L;

        for (var entry : sellOrders.entrySet()) {
            if (entry.getKey() > priceLimit) {
                break;
            }

            for (Order o : entry.getValue()) {
                total += o.getRemainingQuantity();
            }
        }

        return total;
    }

    private long availableBuyLiquidity(Long priceLimit) {
        long total = 0L;

        for (var entry : buyOrders.entrySet()) {
            if (entry.getKey() < priceLimit) {
                break;
            }

            for (Order o : entry.getValue()) {
                total += o.getRemainingQuantity();
            }
        }

        return total;
    }

    private void emitOrderUpdate(Order order, MatchContext ctx) {
        OrderUpdateEvent update = OrderUpdateEvent.builder().sequence(ctx.getSequence())
            .orderId(order.getOrderId()).clientOrderId(order.getClientOrderId())
            .symbol(order.getSymbol()).orderState(order.getState())
            .remainingQuantity(order.getRemainingQuantity()).timestamp(System.nanoTime())
            .build();

        ctx.emit(update);
    }

    private void emitTrade(Order buyOrder, Order sellOrder, Long price, Long quantity,
        MatchContext ctx) {
        TradeEvent tradeEvent = TradeEvent.builder().sequence(ctx.getSequence())
            .buyOrderId(buyOrder.getOrderId()).buyClientOrderId(buyOrder.getClientOrderId())
            .symbol(buyOrder.getSymbol()).sellOrderId(sellOrder.getOrderId())
            .sellClientOrderId(sellOrder.getClientOrderId()).tradePrice(price)
            .quantity(quantity).timestamp(System.currentTimeMillis()).build();

        ctx.emit(tradeEvent);
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

    public void displayBook() {
        OrderBookUtil.printDepth(this.getBuySnapshot(), this.getSellSnapshot());
    }
}
