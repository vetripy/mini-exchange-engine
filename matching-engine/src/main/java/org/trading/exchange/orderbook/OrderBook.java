package org.trading.exchange.orderbook;

import static org.trading.exchange.util.OrderBookUtil.getClientOrderId;
import static org.trading.exchange.util.OrderBookUtil.getOrderId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
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
import org.trading.exchange.model.STPPolicy;
import org.trading.exchange.util.OrderBookUtil;

public class OrderBook {

    private final TreeMap<Long, PriceLevel> buyOrders = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> sellOrders = new TreeMap<>();
    private final Long2ObjectHashMap<Order> orderIndex = new Long2ObjectHashMap<>();
    private final MatchContext ctx;
    private final Consumer<String> onOrderTerminated;
    private final LongSupplier tradeIdSupplier;
    private final STPPolicy stpPolicy;

    public OrderBook() {
        this(new DirectOutboundSink(List.of(), List.of(), List.of()), clientOrderId -> {
        }, new AtomicLong()::incrementAndGet, STPPolicy.CANCEL_NEWEST);
    }

    public OrderBook(OutboundEventSink sink, Consumer<String> onOrderTerminated,
                    LongSupplier tradeIdSupplier, STPPolicy stpPolicy) {
        this.ctx = new MatchContext(sink);
        this.onOrderTerminated = onOrderTerminated;
        this.tradeIdSupplier = tradeIdSupplier;
        this.stpPolicy = stpPolicy;
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

        TreeMap<Long, PriceLevel> book = order.getSide() == OrderSide.BUY ? buyOrders : sellOrders;
        PriceLevel level = book.get(order.getPrice());

        if (level != null) {
            level.remove(order);
            if (level.isEmpty()) {
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
        if (order.getRemainingQuantity() > 0 && order.getState() != OrderState.CANCELLED) {
            addToBook(buyOrders, order);
        }
    }

    private void matchLimitSell(Order order, MatchContext ctx) {
        matchSellWithoutResting(order, ctx, true);
        if (order.getRemainingQuantity() > 0 && order.getState() != OrderState.CANCELLED) {
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
            Entry<Long, PriceLevel> entry = sellOrders.firstEntry();
            PriceLevel level = entry.getValue();
            Order sellOrder = level.peekFirst();

            if (checkPrice && (!(order.getPrice() >= sellOrder.getPrice()))) {
                break;
            }

            if (isSelfTrade(order, sellOrder)) {
                if (applyStp(order, sellOrder, level, sellOrders, ctx)) {
                    break;
                }
                continue;
            }

            executeTrade(sellOrder, order, ctx);
            if (sellOrder.getRemainingQuantity() == 0) {
                level.remove(sellOrder);
                orderIndex.remove(sellOrder.getOrderId());
                if (level.isEmpty()) {
                    sellOrders.pollFirstEntry();
                }
            }

        }
    }

    private void matchSellWithoutResting(Order order, MatchContext ctx, boolean checkPrice) {
        while (!buyOrders.isEmpty() && order.getRemainingQuantity() > 0) {
            Entry<Long, PriceLevel> entry = buyOrders.firstEntry();
            PriceLevel level = entry.getValue();
            Order buyOrder = level.peekFirst();

            if (checkPrice && (!(order.getPrice() <= buyOrder.getPrice()))) {
                break;
            }

            if (isSelfTrade(order, buyOrder)) {
                if (applyStp(order, buyOrder, level, buyOrders, ctx)) {
                    break;
                }
                continue;
            }

            executeTrade(buyOrder, order, ctx);
            if (buyOrder.getRemainingQuantity() == 0) {
                level.remove(buyOrder);
                orderIndex.remove(buyOrder.getOrderId());
                if (level.isEmpty()) {
                    buyOrders.pollFirstEntry();
                }
            }
        }
    }

    private boolean isSelfTrade(Order aggressor, Order resting) {
        return Objects.equals(aggressor.getUserId(), resting.getUserId());
    }

    /**
     * Returns true if the aggressor is done matching (break), false to retry the loop (continue).
     */
    private boolean applyStp(Order aggressor, Order resting, PriceLevel restingLevel,
                    TreeMap<Long, PriceLevel> restingBook, MatchContext ctx) {
        return switch (stpPolicy) {
            case CANCEL_NEWEST -> {
                aggressor.setState(OrderState.CANCELLED);
                yield true;
            }
            case CANCEL_OLDEST -> {
                cancelResting(resting, restingLevel, restingBook, ctx);
                yield false;
            }
            case CANCEL_BOTH -> {
                cancelResting(resting, restingLevel, restingBook, ctx);
                aggressor.setState(OrderState.CANCELLED);
                yield true;
            }
        };
    }

    private void cancelResting(Order resting, PriceLevel level, TreeMap<Long, PriceLevel> book,
                    MatchContext ctx) {
        level.remove(resting);
        orderIndex.remove(resting.getOrderId());
        if (level.isEmpty()) {
            book.remove(resting.getPrice());
        }
        resting.setState(OrderState.CANCELLED);
        emitOrderUpdate(resting, ctx);
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
                        ? availableSellLiquidity(order.getPrice(), order.getUserId()) >= order
                                        .getRemainingQuantity()
                        : availableBuyLiquidity(order.getPrice(), order.getUserId()) >= order
                                        .getRemainingQuantity();

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

    private void addToBook(TreeMap<Long, PriceLevel> book, Order order) {
        book.computeIfAbsent(order.getPrice(), k -> new PriceLevel()).addLast(order);
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

    private long availableSellLiquidity(long priceLimit, String excludeUserId) {
        long total = 0L;

        for (var entry : sellOrders.entrySet()) {
            if (entry.getKey() > priceLimit) {
                break;
            }

            for (Order o : entry.getValue()) {
                if (!Objects.equals(o.getUserId(), excludeUserId)) {
                    total += o.getRemainingQuantity();
                }
            }
        }

        return total;
    }

    private long availableBuyLiquidity(long priceLimit, String excludeUserId) {
        long total = 0L;

        for (var entry : buyOrders.entrySet()) {
            if (entry.getKey() < priceLimit) {
                break;
            }

            for (Order o : entry.getValue()) {
                if (!Objects.equals(o.getUserId(), excludeUserId)) {
                    total += o.getRemainingQuantity();
                }
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
        for (Map.Entry<Long, PriceLevel> entry : buyOrders.entrySet()) {
            snapshot.put(entry.getKey(), toList(entry.getValue()));
        }
        return snapshot;
    }

    public Map<Long, List<Order>> getSellSnapshot() {
        Map<Long, List<Order>> snapshot = new TreeMap<>();
        for (Map.Entry<Long, PriceLevel> entry : sellOrders.entrySet()) {
            snapshot.put(entry.getKey(), toList(entry.getValue()));
        }
        return snapshot;
    }

    private static List<Order> toList(PriceLevel level) {
        List<Order> orders = new ArrayList<>();
        for (Order order : level) {
            orders.add(order);
        }
        return orders;
    }

    public void displayBook() {
        OrderBookUtil.printDepth(this.getBuySnapshot(), this.getSellSnapshot());
    }
}
