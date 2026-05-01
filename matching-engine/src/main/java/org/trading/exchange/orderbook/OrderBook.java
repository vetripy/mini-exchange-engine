package org.trading.exchange.orderbook;

import static org.trading.exchange.util.OrderBookUtil.getClientOrderId;
import static org.trading.exchange.util.OrderBookUtil.getOrderId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.trading.exchange.event.EngineEvent;
import org.trading.exchange.event.OrderUpdateEvent;
import org.trading.exchange.event.TradeEvent;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;
import org.trading.exchange.model.OrderState;
import org.trading.exchange.util.OrderBookUtil;

@Slf4j
public class OrderBook {

    private final TreeMap<Long, Deque<Order>> buyOrders = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, Deque<Order>> sellOrders = new TreeMap<>();
    private final Map<String, Order> orderIndex = new HashMap<>();

    public List<EngineEvent> addOrder(Order order, long seq) {
        MatchContext ctx = new MatchContext(seq);
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

    private void matchLimitBuy(Order order, MatchContext ctx) {
        matchBuyWithoutResting(order, ctx, true);
        if (order.getRemainingQuantity() > 0) {
            addToBook(buyOrders, order);
        }
    }

    private void matchLimitSell(Order order, MatchContext ctx) {
        matchSellWithoutResting(order, ctx, true);
        if (order.getRemainingQuantity() > 0) {
            addToBook(sellOrders, order);
        }
    }

    private void matchMarketBuy(Order order, MatchContext ctx) {
        matchBuyWithoutResting(order, ctx, false);
        if (order.getRemainingQuantity() > 0) {
            log.info("Market buy order not fully filled, cancelling remaining quantity: {}",
                order.getRemainingQuantity());
            order.setState(OrderState.CANCELLED);
        }
    }

    private void matchMarketSell(Order order, MatchContext ctx) {
        matchSellWithoutResting(order, ctx, false);
        if (order.getRemainingQuantity() > 0) {
            log.info("Market sell order not fully filled, cancelling remaining quantity: {}",
                order.getRemainingQuantity());
            order.setState(OrderState.CANCELLED);
        }
    }

    private void matchBuyWithoutResting(Order order, MatchContext ctx, boolean checkPrice) {
        while (!sellOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Entry<Long, Deque<Order>> entry = sellOrders.firstEntry();
            Deque<Order> queue = entry.getValue();
            Order sellOrder = queue.peek();

            if (checkPrice && (!(order.getPrice() >= sellOrder.getPrice()))) {
                break;
            }
            executeTrade(sellOrder, order, ctx);
            if (sellOrder.getRemainingQuantity() == 0) {
                queue.poll();
                orderIndex.remove(sellOrder.getOrderId());
                if (queue.isEmpty()) {
                    sellOrders.pollFirstEntry();
                }
            }

        }
    }

    private void matchSellWithoutResting(Order order, MatchContext ctx, boolean checkPrice) {
        while (!buyOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Entry<Long, Deque<Order>> entry = buyOrders.firstEntry();
            Deque<Order> queue = entry.getValue();
            Order buyOrder = queue.peek();

            if (checkPrice && (!(order.getPrice() <= buyOrder.getPrice()))) {
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
    }

    private void handleMarket(Order order, MatchContext ctx) {
        if (order.getSide() == OrderSide.BUY) {
            matchMarketBuy(order, ctx);
        } else {
            matchMarketSell(order, ctx);
        }
        emitOrderUpdate(order, ctx);
    }

    private void handleLimit(Order order, MatchContext ctx) {
        if (order.getSide() == OrderSide.BUY) {
            matchLimitBuy(order, ctx);
        } else {
            matchLimitSell(order, ctx);
        }
        emitOrderUpdate(order, ctx);
    }

    private void handleFOK(Order order, MatchContext ctx) {
        boolean canFill = order.getSide() == OrderSide.BUY
            ? availableSellLiquidity(order.getPrice()) >= order.getRemainingQuantity()
            : availableBuyLiquidity(order.getPrice()) >= order.getRemainingQuantity();

        if (canFill) {
            if (order.getSide() == OrderSide.BUY) {
                matchBuyWithoutResting(order, ctx, true);
            } else {
                matchSellWithoutResting(order, ctx, true);
            }
        } else {
            order.setState(OrderState.CANCELLED);
            log.info("FOK order cancelled due to insufficient liquidity");
        }
        emitOrderUpdate(order, ctx);
    }

    private void handleIOC(Order order, MatchContext ctx) {
        if (order.getSide() == OrderSide.BUY) {
            matchBuyWithoutResting(order, ctx, true);
        } else {
            matchSellWithoutResting(order, ctx, true);
        }

        if (order.getRemainingQuantity() > 0) {
            order.setState(OrderState.CANCELLED);
            log.info("IOC order remainder cancelled");
        }

        emitOrderUpdate(order, ctx);
    }

    private void addToBook(TreeMap<Long, Deque<Order>> book, Order order) {
        book.computeIfAbsent(order.getPrice(), k -> new java.util.ArrayDeque<>(20))
            .offerLast(order);
        orderIndex.put(order.getOrderId(), order);
    }

    private void executeTrade(Order restingOrder, Order matchingOrder, MatchContext ctx) {
        long tradeQuantity = Math.min(restingOrder.getRemainingQuantity(),
            matchingOrder.getRemainingQuantity());
        restingOrder.reduceQuantity(tradeQuantity);
        matchingOrder.reduceQuantity(tradeQuantity);
        long tradePrice = restingOrder.getPrice();
        emitOrderUpdate(restingOrder, ctx);
        emitTrade(restingOrder, matchingOrder, tradePrice, tradeQuantity, ctx);
    }

    private long availableSellLiquidity(long priceLimit) {
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

    private long availableBuyLiquidity(long priceLimit) {
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
            .remainingQuantity(order.getRemainingQuantity())
            .timestamp(order.getTimestamp()).build();

        ctx.emit(update);
    }

    private void emitTrade(Order restingOrder, Order matchingOrder, long price, long quantity,
        MatchContext ctx) {
        String buyOrderId = getOrderId(restingOrder, matchingOrder, OrderSide.BUY);
        String sellOrderId = getOrderId(restingOrder, matchingOrder, OrderSide.SELL);
        String buyClientOrderId = getClientOrderId(restingOrder, matchingOrder, OrderSide.BUY);
        String sellClientOrderId = getClientOrderId(restingOrder, matchingOrder, OrderSide.SELL);

        TradeEvent tradeEvent = TradeEvent.builder().sequence(ctx.getSequence())
            .buyOrderId(buyOrderId).buyClientOrderId(buyClientOrderId)
            .symbol(restingOrder.getSymbol()).sellOrderId(sellOrderId)
            .sellClientOrderId(sellClientOrderId).tradePrice(price).quantity(quantity)
            .timestamp(matchingOrder.getTimestamp()).build();
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
