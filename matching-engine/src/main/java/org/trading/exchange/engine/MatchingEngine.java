package org.trading.exchange.engine;

import org.trading.exchange.event.EngineEvent;
import org.trading.exchange.event.EngineEventHandler;
import org.trading.exchange.event.OrderEvent;
import org.trading.exchange.listener.OrderUpdateListener;
import org.trading.exchange.listener.TradeListener;
import org.trading.exchange.model.EngineMode;
import org.trading.exchange.model.EngineState;
import org.trading.exchange.model.Order;
import org.trading.exchange.event.OrderUpdate;
import org.trading.exchange.model.Trade;
import org.trading.exchange.orderbook.OrderBook;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;


public class MatchingEngine implements EngineEventHandler {

    private final BlockingQueue<OrderEvent> inboundEvents;
    private final BlockingQueue<EngineEvent> outboundEvents;
    private final OrderBook orderBook;

    private final List<TradeListener> tradeListeners = new CopyOnWriteArrayList<>();
    private final List<OrderUpdateListener> orderUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<EngineStateListener> stateListeners = new CopyOnWriteArrayList<>();

    private final EngineMode mode;
    private volatile EngineState state;
    private Thread engineThread;
    private Thread publisherThread;

    private long sequence = 0L;

    public MatchingEngine(EngineMode mode) {
        this.mode = mode;
        this.state = EngineState.NEW;
        this.inboundEvents = new LinkedBlockingQueue<>();
        this.outboundEvents = new ArrayBlockingQueue<>(10_000);
        this.orderBook = new OrderBook(this);
    }

    private synchronized void transitionTo(EngineState newState, Throwable cause) {
        EngineState oldState = this.state;
        this.state = newState;

        stateListeners.forEach(listener ->
                listener.onStateChange(oldState, newState, cause)
        );
    }

    public synchronized void start() {
        if (this.state != EngineState.NEW && this.state != EngineState.STOPPED) {
            throw new IllegalStateException("Engine can't be started from state: " + this.state);
        }

        transitionTo(EngineState.RUNNING, null);

        engineThread = new Thread(this::engineLoop, "engine-thread");
        engineThread.start();

        if (mode == EngineMode.ASYNC) {
            publisherThread = new Thread(this::publishLoop, "publisher-thread");
            publisherThread.start();
        }
    }

    private void engineLoop() {
        while (this.state == EngineState.RUNNING) {
            try {
                OrderEvent event = inboundEvents.take();
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
        if (this.state != EngineState.RUNNING) {
            throw new IllegalStateException("Engine is not running");
        }
        inboundEvents.put(event);
    }

    public synchronized void stop() throws InterruptedException {
        if (this.state != EngineState.RUNNING) {
            throw new IllegalStateException("Engine not running");
        }

        transitionTo(EngineState.STOPPING, null);
        engineThread.interrupt();
        engineThread.join();
        transitionTo(EngineState.STOPPED, null);

        if (publisherThread != null) {
            publisherThread.interrupt();
            publisherThread.join();
        }
    }

    private long nextSequence() {
        return ++sequence;
    }

    @Override
    public void onTrade(Trade event) throws InterruptedException {
        Trade trade = Trade.builder()
                .sequence(nextSequence())
                .tradeId(event.getTradeId())
                .buyOrderId(event.getBuyOrderId())
                .sellOrderId(event.getSellOrderId())
                .tradePrice(event.getTradePrice())
                .quantity(event.getQuantity())
                .timestamp(System.currentTimeMillis())
                .build();

        EngineEvent engineEvent = EngineEvent.builder()
                .sequenceNumber(trade.getSequence())
                .type(EngineEvent.Type.TRADE)
                .data(trade)
                .build();

        if (mode == EngineMode.ASYNC) {
            outboundEvents.put(engineEvent);
        } else {
            publishDirect(engineEvent);
        }
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

        EngineEvent engineEvent = EngineEvent.builder()
                .sequenceNumber(update.getSequence())
                .type(EngineEvent.Type.ORDER_UPDATE)
                .data(update)
                .build();

        if (mode == EngineMode.ASYNC) {
            outboundEvents.put(engineEvent);
        } else {
            publishDirect(engineEvent);
        }
    }

    public void addTradeListener(TradeListener listener) {
        tradeListeners.add(listener);
    }

    public void addOrderUpdateListener(OrderUpdateListener listener) {
        orderUpdateListeners.add(listener);
    }

    public void addStateListener(EngineStateListener listener) {
        stateListeners.add(listener);
    }

    private void publishLoop() {
        try {
            while (this.state == EngineState.RUNNING || !outboundEvents.isEmpty()) {
                EngineEvent event = outboundEvents.take();
                publishDirect(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void publishDirect(EngineEvent event) {
        switch (event.getType()) {
            case TRADE -> tradeListeners.forEach(l ->
                    l.onTrade((Trade) event.getData())
            );
            case ORDER_UPDATE -> orderUpdateListeners.forEach(l ->
                    l.onOrderUpdate((OrderUpdate) event.getData())
            );
        }
    }
}
