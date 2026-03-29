package org.trading.exchange.listener.impl;

import org.trading.exchange.event.OrderUpdateEvent;
import org.trading.exchange.listener.OrderUpdateListener;

public class LoggingOrderUpdateListener implements OrderUpdateListener {

    @Override
    public void onOrderUpdate(OrderUpdateEvent update) {
        System.out.println("ORDER UPDATE → " + update.getOrderId() + " " + update.getOrderState()
            + " remaining: " + update.getRemainingQuantity());
    }
}
