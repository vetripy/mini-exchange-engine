package org.trading.exchange.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.trading.exchange.engine.command.CancelOrderCommand;
import org.trading.exchange.engine.command.EngineCommand;
import org.trading.exchange.engine.command.NewOrderCommand;
import org.trading.exchange.event.EngineEvent;
import org.trading.exchange.event.OrderUpdateEvent;
import org.trading.exchange.event.TradeEvent;
import org.trading.exchange.listener.OrderUpdateListener;
import org.trading.exchange.listener.TradeListener;
import org.trading.exchange.model.EngineMode;
import org.trading.exchange.model.EngineState;
import org.trading.exchange.model.Envelope;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.Symbol;
import org.trading.exchange.orderbook.OrderBook;
import org.trading.exchange.sequencer.Sequencer;
import org.trading.exchange.util.EnvelopeUtil;
import org.trading.exchange.validators.OrderValidator;

@Slf4j
public class MatchingEngine {

    private final BlockingQueue<Envelope<EngineCommand>> inboundEvents;
    private final BlockingQueue<EngineEvent> outboundEvents;
    private final Sequencer sequencer;
    private final Map<String, String> clientIdToOrderId = new HashMap<>();
    private final Map<String, OrderBook> books = new HashMap<>();
    private final OrderValidator orderValidator = new OrderValidator();

    private final List<TradeListener> tradeListeners = new CopyOnWriteArrayList<>();
    private final List<OrderUpdateListener> orderUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<EngineStateListener> stateListeners = new CopyOnWriteArrayList<>();

    private final EngineMode mode;
    @Getter
    private volatile EngineState state;
    private Thread engineThread;
    private Thread publisherThread;

    public MatchingEngine(EngineMode mode) {
        this.mode = mode;
        this.state = EngineState.NEW;
        this.inboundEvents = new LinkedBlockingQueue<>();
        this.sequencer = new Sequencer();
        this.outboundEvents = new ArrayBlockingQueue<>(10_000);
        for (Symbol symbol : Symbol.values()) {
            books.put(symbol.name(), new OrderBook());
        }
    }

    public synchronized void start() {
        if (this.state != EngineState.NEW && this.state != EngineState.STOPPED) {
            throw new IllegalStateException("Engine can't be started from state: " + this.state);
        }

        transitionTo(EngineState.RUNNING, null);

        if (mode == EngineMode.ASYNC) {
            engineThread = new Thread(this::engineLoop, "engine-thread");
            engineThread.start();

            publisherThread = new Thread(this::publishLoop, "publisher-thread");
            publisherThread.start();
        }
    }

    public synchronized void stop() throws InterruptedException {
        if (this.state != EngineState.RUNNING) {
            throw new IllegalStateException("Engine not running");
        }

        transitionTo(EngineState.STOPPING, null);

        if (engineThread != null) {
            engineThread.interrupt();
            engineThread.join();
        }

        transitionTo(EngineState.STOPPED, null);

        if (publisherThread != null) {
            publisherThread.interrupt();
            publisherThread.join();
        }
    }

    private void engineLoop() {
        while (this.state == EngineState.RUNNING) {
            try {
                Envelope<EngineCommand> envelope = inboundEvents.take();
                process(envelope);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void publishLoop() {
        try {
            while (this.state == EngineState.RUNNING || !outboundEvents.isEmpty()) {
                EngineEvent event = outboundEvents.take();
                publishDirect(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failEngine(e);
        } catch (Exception e) {
            failEngine(e);
        }
    }

    public void submit(EngineCommand command) throws InterruptedException {
        if (this.state != EngineState.RUNNING) {
            throw new IllegalStateException("Engine is not running");
        }

        long seq = sequencer.getNextSequence();
        Envelope<EngineCommand> envelope = EnvelopeUtil.wrap(seq, command);

        if (EngineMode.SYNC.equals(this.mode)) {
            process(envelope);
        } else {
            inboundEvents.put(envelope);
        }
    }

    private void process(Envelope<EngineCommand> event) {
        EngineCommand command = EnvelopeUtil.unwrap(event);
        long seq = event.sequence();

        switch (command) {
            case NewOrderCommand cmd -> handleNewOrder(cmd, seq);
            case CancelOrderCommand cmd -> handleCancelOrder(cmd, seq);
            default -> throw new IllegalStateException("Unsupported engine command: " + command);
        }
    }

    private void handleNewOrder(NewOrderCommand newOrderCommand, long seq) {
        Order order = buildOrderFromCommand(newOrderCommand, seq);

        orderValidator.validateInvariants(order);
        
        // thread safe since orders are only added in the engine thread (single threaded)
        if (clientIdToOrderId.containsKey(order.getClientOrderId())) {
            throw new IllegalArgumentException("Duplicate clientOrderId");
        }
        clientIdToOrderId.put(order.getClientOrderId(), order.getOrderId());
        log.info("Processing new order: {} with sequence: {}", order, seq);
        OrderBook orderBook = books.get(order.getSymbol().name());
        List<EngineEvent> events = orderBook.addOrder(order, seq);
        events.forEach(this::handleOutbound);
    }

    private Order buildOrderFromCommand(NewOrderCommand cmd, long seq) {
        String orderId = cmd.getSymbol() + "-" + seq;
        return Order.builder().orderId(orderId).clientOrderId(cmd.getClientOrderId())
            .userId(cmd.getUserId()).symbol(Symbol.from(cmd.getSymbol())).side(cmd.getSide())
            .type(cmd.getType()).price(cmd.getPrice()).remainingQuantity(cmd.getQuantity())
            .build();

    }

    private void handleCancelOrder(CancelOrderCommand cancelOrderCommand, long seq) {
        String clientOrderId = cancelOrderCommand.getClientOrderId();
        log.info("Processing cancel order: {} with sequence: {}", clientOrderId, seq);

        String orderId = clientIdToOrderId.get(cancelOrderCommand.getClientOrderId());
        if (orderId == null) {
            throw new IllegalArgumentException("Unknown clientOrderId");
        }
        String symbol = parseSymbolFromOrderId(orderId);

        OrderBook orderBook = books.get(Symbol.from(symbol).name());
        List<EngineEvent> events = orderBook.cancelOrder(orderId, seq);
        events.forEach(this::handleOutbound);
    }

    private String parseSymbolFromOrderId(String orderId) {
        return orderId.split("-")[0];
    }

    private void publishDirect(EngineEvent engineEvent) {
        switch (engineEvent) {
            case TradeEvent event -> tradeListeners.forEach(l -> l.onTrade(event));
            case OrderUpdateEvent event ->
                orderUpdateListeners.forEach(l -> l.onOrderUpdate(event));
            default -> throw new IllegalStateException("Unsupported engine event: " + engineEvent);
        }
    }

    private void handleOutbound(EngineEvent event) {
        if (mode == EngineMode.ASYNC) {
            try {
                outboundEvents.put(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failEngine(e);
            }
        } else {
            publishDirect(event);
        }
    }

    private void failEngine(Throwable cause) {
        synchronized (this) {
            if (state == EngineState.FAILED) {
                return;
            }

            transitionTo(EngineState.FAILED, cause);
        }

        if (engineThread != null) {
            engineThread.interrupt();
        }

        if (publisherThread != null) {
            publisherThread.interrupt();
        }
    }

    private synchronized void transitionTo(EngineState newState, Throwable cause) {
        EngineState oldState = this.state;
        this.state = newState;

        stateListeners.forEach(listener -> listener.onStateChange(oldState, newState, cause));
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

}
