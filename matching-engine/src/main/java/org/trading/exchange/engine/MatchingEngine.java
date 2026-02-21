package org.trading.exchange.engine;

import org.trading.exchange.event.OrderEvent;
import org.trading.exchange.model.Order;
import org.trading.exchange.orderbook.OrderBook;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


public class MatchingEngine {

    private final BlockingQueue<OrderEvent> events = new LinkedBlockingQueue<>();
    private volatile boolean running = false;
    private Thread engineThread;
    private final OrderBook orderBook = new OrderBook();

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
}
