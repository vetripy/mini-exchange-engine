package org.trading.exchange.engine;

import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.Getter;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.trading.exchange.engine.command.CancelOrderCommand;
import org.trading.exchange.engine.command.EngineCommand;
import org.trading.exchange.engine.command.NewOrderCommand;
import org.trading.exchange.event.DirectOutboundSink;
import org.trading.exchange.event.OutboundEvent;
import org.trading.exchange.event.OutboundEventFactory;
import org.trading.exchange.event.OutboundEventHandler;
import org.trading.exchange.event.OutboundEventSink;
import org.trading.exchange.event.RingBufferOutboundSink;
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

public class MatchingEngine {

    private final ManyToOneConcurrentArrayQueue<Envelope<EngineCommand>> inboundEvents;
    private final Disruptor<OutboundEvent> disruptor;
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

    public MatchingEngine(EngineMode mode) {
        this.mode = mode;
        this.state = EngineState.NEW;
        this.inboundEvents = new ManyToOneConcurrentArrayQueue<>(100_000);
        this.sequencer = new Sequencer();
        this.disruptor = new Disruptor<>(new OutboundEventFactory(), 131_072, // ring capacity
                        DaemonThreadFactory.INSTANCE, // creates the consumer thread
                        ProducerType.SINGLE, // engine thread is the sole writer
                        new YieldingWaitStrategy() // low-latency but yields to scheduler
        );
        disruptor.handleEventsWith(new OutboundEventHandler(tradeListeners, orderUpdateListeners));

        OutboundEventSink sink = mode == EngineMode.ASYNC
                        ? new RingBufferOutboundSink(disruptor.getRingBuffer())
                        : new DirectOutboundSink(tradeListeners, orderUpdateListeners);

        for (Symbol symbol : Symbol.values()) {
            books.put(symbol, new OrderBook(sink, clientIdToOrder::remove));
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

            disruptor.start();
        }
    }

    public synchronized void stop() {
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
                Thread.currentThread().interrupt();
            }
        }

        if (mode == EngineMode.ASYNC) {
            disruptor.shutdown();
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
                Thread.onSpinWait(); // or: Thread.yield()
                continue;
            }
            try {
                process(envelope); // rejects are a return code now; nothing to catch for them
            } catch (RuntimeException e) {
                failEngine(e);
                break;
            }
        }
    }

    public void submit(EngineCommand command) throws InterruptedException {
        if (this.state != EngineState.RUNNING) {
            throw new IllegalStateException("Engine is not running");
        }

        long seq = sequencer.getNextSequence();
        Envelope<EngineCommand> envelope = EnvelopeUtil.wrap(seq, command);

        if (EngineMode.SYNC.equals(this.mode)) {
            ProcessResult result = process(envelope);
            if (result.isRejected()) {
                throw new IllegalArgumentException(result.message());
            }
        } else {
            boolean accepted = inboundEvents.offer(envelope);
            if (!accepted) {
                throw new IllegalStateException(
                                "Engine inbound queue full — apply backpressure upstream");
            }
        }
    }

    private ProcessResult process(Envelope<EngineCommand> event) {
        EngineCommand command = EnvelopeUtil.unwrap(event);
        long seq = event.sequence();

        return switch (command) {
            case NewOrderCommand cmd -> handleNewOrder(cmd, seq);
            case CancelOrderCommand cmd -> handleCancelOrder(cmd, seq);
            default -> throw new IllegalStateException("Unsupported engine command: " + command);
        };
    }

    private ProcessResult handleNewOrder(NewOrderCommand newOrderCommand, long seq) {
        Order order = buildOrderFromCommand(newOrderCommand, seq);

        orderValidator.validateInvariants(order);

        if (clientIdToOrder.putIfAbsent(order.getClientOrderId(), order) != null) {
            return ProcessResult.DUPLICATE_CLIENT_ORDER_ID;
        }
        OrderBook orderBook = books.get(order.getSymbol());
        // clientIdToOrder cleanup (for this order AND any resting counterparties it fills) is
        // driven by OrderBook's onOrderTerminated callback, not here.
        orderBook.addOrder(order, seq);
        return ProcessResult.ACCEPTED;
    }

    private Order buildOrderFromCommand(NewOrderCommand cmd, long seq) {
        return new Order(seq, cmd.getClientOrderId(), cmd.getUserId(), Symbol.from(cmd.getSymbol()),
                        cmd.getSide(), cmd.getType(), cmd.getPrice(), cmd.getQuantity(),
                        System.currentTimeMillis());
    }

    private ProcessResult handleCancelOrder(CancelOrderCommand cancelOrderCommand, long seq) {
        String clientOrderId = cancelOrderCommand.getClientOrderId();

        Order order = clientIdToOrder.get(clientOrderId);
        if (order == null) {
            return ProcessResult.UNKNOWN_CLIENT_ORDER_ID;
        }

        OrderBook orderBook = books.get(order.getSymbol());
        // clientIdToOrder cleanup happens via OrderBook's onOrderTerminated callback, not here.
        if (!orderBook.cancelOrder(order.getOrderId(), seq)) {
            return ProcessResult.UNKNOWN_CLIENT_ORDER_ID;
        }
        return ProcessResult.ACCEPTED;
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
