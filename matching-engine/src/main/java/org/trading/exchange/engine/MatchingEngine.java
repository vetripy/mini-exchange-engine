package org.trading.exchange.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;
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

    private final ManyToOneConcurrentArrayQueue<Envelope<EngineCommand>> inboundEvents;
    private final OneToOneConcurrentArrayQueue<EngineEvent> outboundEvents;
    private final Sequencer sequencer;
    private final Map<String, Order> clientIdToOrder = new HashMap<>();
    private final Map<Symbol, OrderBook> books = new HashMap<>();
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
        this.inboundEvents = new ManyToOneConcurrentArrayQueue<>(100_000);
        this.sequencer = new Sequencer();
        this.outboundEvents = new OneToOneConcurrentArrayQueue<>(100_000);
        for (Symbol symbol : Symbol.values()) {
            books.put(symbol, new OrderBook());
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
        if (this.state != EngineState.RUNNING && this.state != EngineState.FAILED) {
            throw new IllegalStateException("Engine cannot be stopped from state: " + this.state);
        }

        if (this.state == EngineState.RUNNING) {
            transitionTo(EngineState.STOPPING, null);
        }

        if (engineThread != null) {
            engineThread.interrupt();
            try {
                engineThread.join(5000); // 5 second timeout
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for engine thread to stop");
                Thread.currentThread().interrupt();
            }
        }

        if (publisherThread != null) {
            publisherThread.interrupt();
            try {
                publisherThread.join(5000); // 5 second timeout
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for publisher thread to stop");
                Thread.currentThread().interrupt();
            }
        }

        if (this.state != EngineState.STOPPED) {
            transitionTo(EngineState.STOPPED, null);
        }
    }

    private void engineLoop() {
        while (this.state == EngineState.RUNNING) {
            Envelope<EngineCommand> envelope = inboundEvents.poll();
            if (envelope == null) {
                // Queue empty, brief sleep to avoid busy-waiting
                Thread.onSpinWait();  // or: Thread.yield()
                continue;
            }
            try {
                process(envelope);
            } catch (IllegalArgumentException e) {
                log.warn("Command rejected: {}", e.getMessage());
            } catch (RuntimeException e) {
                log.error("Unexpected exception in engine loop, failing engine", e);
                failEngine(e);
                break;
            }
        }
    }

    private void publishLoop() {
        try {
            while (this.state == EngineState.RUNNING || !outboundEvents.isEmpty()) {
                EngineEvent event = outboundEvents.poll();
                if (event == null) {
                    Thread.onSpinWait();
                    continue;
                }
                publishDirect(event);
            }
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
            boolean accepted = inboundEvents.offer(envelope);
            if (!accepted) {
                throw new IllegalStateException(
                    "Engine inbound queue full — apply backpressure upstream");
            }
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

        if (clientIdToOrder.putIfAbsent(order.getClientOrderId(), order) != null) {
            throw new IllegalArgumentException("Duplicate clientOrderId");
        }
        OrderBook orderBook = books.get(order.getSymbol());
        List<EngineEvent> events = orderBook.addOrder(order, seq);

        if (order.getState().isTerminal()) {
            clientIdToOrder.remove(order.getClientOrderId());
        }

        events.forEach(this::handleOutbound);
    }

    private Order buildOrderFromCommand(NewOrderCommand cmd, long seq) {
        String orderId = Long.toString(seq);
        return new Order(orderId, cmd.getClientOrderId(), cmd.getUserId(),
            Symbol.from(cmd.getSymbol()), cmd.getSide(), cmd.getType(), cmd.getPrice(),
            cmd.getQuantity(), System.currentTimeMillis());
    }

    private void handleCancelOrder(CancelOrderCommand cancelOrderCommand, long seq) {
        String clientOrderId = cancelOrderCommand.getClientOrderId();

        Order order = clientIdToOrder.get(clientOrderId);
        if (order == null) {
            throw new IllegalArgumentException("Unknown clientOrderId");
        }
        String orderId = order.getOrderId();
        Symbol symbol = order.getSymbol();

        OrderBook orderBook = books.get(symbol);
        List<EngineEvent> events = orderBook.cancelOrder(orderId, seq);
        clientIdToOrder.remove(clientOrderId);
        events.forEach(this::handleOutbound);
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
            boolean accepted = outboundEvents.offer(event);
            if (!accepted) {
                throw new IllegalStateException(
                    "Engine outbound queue full — apply backpressure upstream");
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
