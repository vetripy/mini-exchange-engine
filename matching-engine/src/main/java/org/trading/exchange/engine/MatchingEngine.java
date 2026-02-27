package org.trading.exchange.engine;

import org.trading.exchange.event.EngineEventHandler;
import org.trading.exchange.event.OrderEvent;
import org.trading.exchange.listener.OrderUpdateListener;
import org.trading.exchange.listener.TradeListener;
import org.trading.exchange.listener.impl.LoggingOrderUpdateListener;
import org.trading.exchange.listener.impl.LoggingTradeListener;
import org.trading.exchange.model.Order;
import org.trading.exchange.event.OrderUpdate;
import org.trading.exchange.model.Trade;
import org.trading.exchange.orderbook.OrderBook;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


public class MatchingEngine implements EngineEventHandler {

    private final BlockingQueue<OrderEvent> events;
    private final OrderBook orderBook;
    private final List<TradeListener> tradeListeners = new ArrayList<>();
    private final List<OrderUpdateListener> orderUpdateListeners = new ArrayList<>();
    private volatile boolean running;
    private Thread engineThread;
    private long sequence = 0L;


    MatchingEngine() {
        this.orderBook = new OrderBook(this);
        this.running = false;
        this.events = new LinkedBlockingQueue<>();
        this.addTradeListener(new LoggingTradeListener());
        this.addOrderUpdateListener(new LoggingOrderUpdateListener());
    }

    public void start() {
        if (running) {
            throw new IllegalStateException("Engine is already running");
        }
        running = true;
        engineThread = new Thread(this::run);
        engineThread.setName("engine-thread");
        engineThread.start();
    }

    private void run() {
        while (running) {
            try {
                OrderEvent event = events.take(); // blocks
                process(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void process(OrderEvent event) {
        switch (event.getType()) {
            case NEW_ORDER -> handleNewOrder(event.getOrder());
            case CANCEL_ORDER -> handleCancelOrder(event.getOrderId());
        }
    }

    private void handleNewOrder(Order order) {
        System.out.println("Processing new order: " + order);
        orderBook.addOrder(order);
    }

    private void handleCancelOrder(String orderId) {
        System.out.println("Processing cancel order: " + orderId);
        orderBook.cancelOrder(orderId);
    }

    public boolean submit(OrderEvent event) {
        if (!running) {
            throw new IllegalStateException("Engine is not running");
        }
        return events.offer(event);
    }

    public void stop() {
        if (!running) {
            throw new IllegalStateException("Engine is not running");
        }
        running = false;
        engineThread.interrupt();
    }

    private long nextSequence() {
        return ++sequence;
    }

    @Override
    public void onTrade(Trade event) {
        Trade trade = Trade.builder()
                .sequence(nextSequence())
                .tradeId(java.util.UUID.randomUUID().toString())
                .buyOrderId(event.getBuyOrderId())
                .sellOrderId(event.getSellOrderId())
                .tradePrice(event.getTradePrice())
                .quantity(event.getQuantity())
                .timestamp(System.currentTimeMillis())
                .build();

        for (TradeListener listener : tradeListeners) {
            listener.onTrade(trade);
        }
    }

    @Override
    public void onOrderUpdate(OrderUpdate event) {
        OrderUpdate update = OrderUpdate.builder()
                .sequence(nextSequence())
                .orderId(event.getOrderId())
                .orderState(event.getOrderState())
                .remainingQuantity(event.getRemainingQuantity())
                .timestamp(System.nanoTime())
                .build();

        for (OrderUpdateListener listener : orderUpdateListeners) {
            listener.onOrderUpdate(update);
        }
    }

    public void addTradeListener(TradeListener listener) {
        tradeListeners.add(listener);
    }

    public void addOrderUpdateListener(OrderUpdateListener listener) {
        orderUpdateListeners.add(listener);
    }
}
