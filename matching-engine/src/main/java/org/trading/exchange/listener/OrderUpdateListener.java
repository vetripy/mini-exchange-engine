package org.trading.exchange.listener;

import org.trading.exchange.event.OrderUpdate;

public interface OrderUpdateListener {
    void onOrderUpdate(OrderUpdate orderUpdate);
}
