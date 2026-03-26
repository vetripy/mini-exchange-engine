package org.trading.exchange.utils;

import java.util.ArrayList;
import java.util.List;
import org.trading.exchange.event.OrderUpdate;
import org.trading.exchange.listener.OrderUpdateListener;

public class TestOrderUpdateListener implements OrderUpdateListener {

    private final List<OrderUpdate> updates = new ArrayList<>();

    @Override
    public void onOrderUpdate(OrderUpdate update) {
        updates.add(update);
    }

    public List<OrderUpdate> getUpdates() {
        return updates;
    }

    public OrderUpdate latest() {
        return updates.getLast();
    }
}
