package org.trading.exchange.listeners;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.trading.exchange.event.OrderUpdateEvent;
import org.trading.exchange.listener.OrderUpdateListener;

@Slf4j
public class TestOrderUpdateListener implements OrderUpdateListener {

    private final List<OrderUpdateEvent> updates = new ArrayList<>();

    @Override
    public void onOrderUpdate(OrderUpdateEvent update) {
        log.info("ORDER UPDATE → {} {} remaining: {}", update.getOrderId(), update.getOrderState(),
            update.getRemainingQuantity());
        updates.add(update);
    }

    public List<OrderUpdateEvent> getUpdates() {
        return updates;
    }

    public OrderUpdateEvent latest() {
        return updates.getLast();
    }
}
