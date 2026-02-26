package org.trading.exchange.listener;

import org.trading.exchange.model.OrderUpdate;

public interface OrderUpdateListener {
    void onOrderUpdate(OrderUpdate orderUpdate);
}
