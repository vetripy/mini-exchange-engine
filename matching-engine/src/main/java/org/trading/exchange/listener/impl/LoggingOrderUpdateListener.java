package org.trading.exchange.listener.impl;

import org.trading.exchange.listener.OrderUpdateListener;
import org.trading.exchange.event.OrderUpdate;

public class LoggingOrderUpdateListener implements OrderUpdateListener {

    @Override
    public void onOrderUpdate(OrderUpdate update) {
        System.out.println("ORDER UPDATE → "
                + update.getOrderId()
                + " "
                + update.getOrderState()
                + " remaining: "
                + update.getRemainingQuantity());
    }
}
