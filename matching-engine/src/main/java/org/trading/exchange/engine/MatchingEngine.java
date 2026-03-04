package org.trading.exchange.engine;

import org.trading.exchange.event.EngineEvent;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


public class MatchingEngine implements EngineEventHandler {

    private final BlockingQueue<OrderEvent> inboundEvents;
    private final BlockingQueue<EngineEvent> outboundEvents;
    private final OrderBook orderBook;
    private final List<TradeListener> tradeListeners = new ArrayList<>();
    private final List<OrderUpdateListener> orderUpdateListeners = new ArrayList<>();
    private volatile boolean running;
    private Thread engineThread;

    private long sequence = 0L;


    MatchingEngine() {
        this.orderBook = new OrderBook(this);
        this.running = false;
        this.inboundEvents = new LinkedBlockingQueue<>();
        this.outboundEvents = new ArrayBlockingQueue<>(10000);
        Thread publisherThread = new Thread(this::publishLoop);
        publisherThread.start();
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
                OrderEvent event = inboundEvents.take(); // blocks
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

    public void submit(OrderEvent event) throws InterruptedException {
        if (!running) {
            throw new IllegalStateException("Engine is not running");
        }
        inboundEvents.put(event);
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
    public void onTrade(Trade event) throws InterruptedException {
        Trade trade = Trade.builder()
                .sequence(nextSequence())
                .tradeId(java.util.UUID.randomUUID().toString())
                .buyOrderId(event.getBuyOrderId())
                .sellOrderId(event.getSellOrderId())
                .tradePrice(event.getTradePrice())
                .quantity(event.getQuantity())
                .timestamp(System.currentTimeMillis())
                .build();

        outboundEvents.put(EngineEvent.builder()
                .sequenceNumber(trade.getSequence())
                .type(EngineEvent.Type.TRADE)
                .data(trade)
                .build());
    }

    @Override
    public void onOrderUpdate(OrderUpdate event) throws InterruptedException {
        OrderUpdate update = OrderUpdate.builder()
                .sequence(nextSequence())
                .orderId(event.getOrderId())
                .orderState(event.getOrderState())
                .remainingQuantity(event.getRemainingQuantity())
                .timestamp(System.nanoTime())
                .build();

        outboundEvents.put(EngineEvent.builder()
                .sequenceNumber(update.getSequence())
                .type(EngineEvent.Type.ORDER_UPDATE)
                .data(update)
                .build());
    }

    public void addTradeListener(TradeListener listener) {
        tradeListeners.add(listener);
    }

    public void addOrderUpdateListener(OrderUpdateListener listener) {
        orderUpdateListeners.add(listener);
    }

    private void publishLoop() {
        try {
            while (true) {
                EngineEvent event = outboundEvents.take(); // blocks
                switch (event.getType()) {
                    case TRADE -> tradeListeners.forEach(listener -> {
                        listener.onTrade((Trade) event.getData());
                    });
                    case ORDER_UPDATE -> orderUpdateListeners.forEach(listener -> {
                        listener.onOrderUpdate((OrderUpdate) event.getData());
                    });
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
