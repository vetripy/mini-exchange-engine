package org.trading.exchange.event;

import com.lmax.disruptor.EventHandler;
import java.util.List;
import org.trading.exchange.listener.CommandRejectedListener;
import org.trading.exchange.listener.OrderUpdateListener;
import org.trading.exchange.listener.TradeListener;

public class OutboundEventHandler implements EventHandler<OutboundEvent> {

    private final List<TradeListener> tradeListeners;
    private final List<OrderUpdateListener> orderUpdateListeners;
    private final List<CommandRejectedListener> commandRejectedListeners;

    public OutboundEventHandler(List<TradeListener> tradeListeners,
                    List<OrderUpdateListener> orderUpdateListeners,
                    List<CommandRejectedListener> commandRejectedListeners) {
        this.tradeListeners = tradeListeners;
        this.orderUpdateListeners = orderUpdateListeners;
        this.commandRejectedListeners = commandRejectedListeners;
    }

    @Override
    public void onEvent(OutboundEvent event, long sequence, boolean endOfBatch) {
        if (event.type == OutboundEvent.TYPE_ORDER_UPDATE) {
            OrderUpdateEvent update = event.toOrderUpdateEvent();
            for (OrderUpdateListener listener : orderUpdateListeners) {
                listener.onOrderUpdate(update);
            }
        } else if (event.type == OutboundEvent.TYPE_TRADE) {
            TradeEvent trade = event.toTradeEvent();
            for (TradeListener listener : tradeListeners) {
                listener.onTrade(trade);
            }
        } else if (event.type == OutboundEvent.TYPE_COMMAND_REJECTED) {
            CommandRejectedEvent rejected = event.toCommandRejectedEvent();
            for (CommandRejectedListener listener : commandRejectedListeners) {
                listener.onCommandRejected(rejected);
            }
        }
    }
}
