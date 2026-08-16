package org.trading.exchange.orderbook;

import static org.trading.exchange.util.OrderBookUtil.getClientOrderId;
import static org.trading.exchange.util.OrderBookUtil.getOrderId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.agrona.collections.Long2ObjectHashMap;
import org.trading.exchange.event.DirectOutboundSink;
import org.trading.exchange.event.OutboundEventSink;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;
import org.trading.exchange.model.OrderState;
import org.trading.exchange.util.OrderBookUtil;

public class OrderBook {

    private final TreeMap<Long, ArrayDeque<Order>> buyOrders =
                    new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, ArrayDeque<Order>> sellOrders = new TreeMap<>();
    private final Long2ObjectHashMap<Order> orderIndex = new Long2ObjectHashMap<>();
    private final MatchContext ctx;
    private final Consumer<String> onOrderTerminated;
    private final LongSupplier tradeIdSupplier;

    public OrderBook() {
        this(new DirectOutboundSink(List.of(), List.of()), clientOrderId -> {
        }, new AtomicLong()::incrementAndGet);
    }

    public OrderBook(OutboundEventSink sink, Consumer<String> onOrderTerminated, LongSupplier tradeIdSupplier) {
        this.ctx = new MatchContext(sink);
        this.onOrderTerminated = onOrderTerminated;
        this.tradeIdSupplier = tradeIdSupplier;
    }

    public void addOrder(Order order, long seq) {
        ctx.setSequence(seq);
        switch (order.getType()) {
            case MARKET -> handleMarket(order, ctx);
            case LIMIT -> handleLimit(order, ctx);
            case IOC -> handleIOC(order, ctx);
            case FOK -> handleFOK(order, ctx);
        }
    }

    public boolean cancelOrder(long orderId, long seq) {
        ctx.setSequence(seq);
        Order order = orderIndex.get(orderId);

        if (order == null) {
            return false;
        }

        TreeMap<Long, ArrayDeque<Order>> book =
                        order.getSide() == OrderSide.BUY ? buyOrders : sellOrders;
        ArrayDeque<Order> queue = book.get(order.getPrice());

        if (queue != null) {
            queue.remove(order);
            if (queue.isEmpty()) {
                book.remove(order.getPrice());
            }
        }
        order.setState(OrderState.CANCELLED);
        emitOrderUpdate(order, ctx);
        orderIndex.remove(orderId);
        return true;
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
            order.setState(OrderState.CANCELLED);
        }
    }

    private void matchMarketSell(Order order, MatchContext ctx) {
        matchSellWithoutResting(order, ctx, false);
        if (order.getRemainingQuantity() > 0) {
            order.setState(OrderState.CANCELLED);
        }
    }

    private void matchBuyWithoutResting(Order order, MatchContext ctx, boolean checkPrice) {
        while (!sellOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Entry<Long, ArrayDeque<Order>> entry = sellOrders.firstEntry();
            ArrayDeque<Order> queue = entry.getValue();
            Order sellOrder = queue.peekFirst();

            if (checkPrice && (!(order.getPrice() >= sellOrder.getPrice()))) {
                break;
            }
            executeTrade(sellOrder, order, ctx);
            if (sellOrder.getRemainingQuantity() == 0) {
                queue.pollFirst();
                orderIndex.remove(sellOrder.getOrderId());
                if (queue.isEmpty()) {
                    sellOrders.pollFirstEntry();
                }
            }

        }
    }

    private void matchSellWithoutResting(Order order, MatchContext ctx, boolean checkPrice) {
        while (!buyOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Entry<Long, ArrayDeque<Order>> entry = buyOrders.firstEntry();
            ArrayDeque<Order> queue = entry.getValue();
            Order buyOrder = queue.peekFirst();

            if (checkPrice && (!(order.getPrice() <= buyOrder.getPrice()))) {
                break;
            }

            executeTrade(buyOrder, order, ctx);
            if (buyOrder.getRemainingQuantity() == 0) {
                queue.pollFirst();
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
        }

        emitOrderUpdate(order, ctx);
    }

    private void addToBook(TreeMap<Long, ArrayDeque<Order>> book, Order order) {
        book.computeIfAbsent(order.getPrice(), k -> new ArrayDeque<>()).addLast(order);
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
        ctx.emitOrderUpdate(order.getOrderId(), order.getClientOrderId(), order.getState(),
                        order.getSymbol(), order.getRemainingQuantity(), order.getTimestamp());
        if (order.getState().isTerminal()) {
            onOrderTerminated.accept(order.getClientOrderId());
        }
    }

    private void emitTrade(Order restingOrder, Order matchingOrder, long price, long quantity,
                    MatchContext ctx) {
        long buyOrderId = getOrderId(restingOrder, matchingOrder, OrderSide.BUY);
        long sellOrderId = getOrderId(restingOrder, matchingOrder, OrderSide.SELL);
        String buyClientOrderId = getClientOrderId(restingOrder, matchingOrder, OrderSide.BUY);
        String sellClientOrderId = getClientOrderId(restingOrder, matchingOrder, OrderSide.SELL);

        ctx.emitTrade(tradeIdSupplier.getAsLong(), buyOrderId, buyClientOrderId, sellOrderId,
                        sellClientOrderId, restingOrder.getSymbol(), price, quantity,
                        matchingOrder.getTimestamp());
    }

    public Map<Long, List<Order>> getBuySnapshot() {
        Map<Long, List<Order>> snapshot = new TreeMap<>(Comparator.reverseOrder());
        for (Map.Entry<Long, ArrayDeque<Order>> entry : buyOrders.entrySet()) {
            snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return snapshot;
    }

    public Map<Long, List<Order>> getSellSnapshot() {
        Map<Long, List<Order>> snapshot = new TreeMap<>();
        for (Map.Entry<Long, ArrayDeque<Order>> entry : sellOrders.entrySet()) {
            snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return snapshot;
    }

    public void displayBook() {
        OrderBookUtil.printDepth(this.getBuySnapshot(), this.getSellSnapshot());
    }
}
