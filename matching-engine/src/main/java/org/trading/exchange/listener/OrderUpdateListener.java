package org.trading.exchange.listener;

import org.trading.exchange.event.OrderUpdateEvent;

public interface OrderUpdateListener {

  void onOrderUpdate(OrderUpdateEvent orderUpdateEvent);
}
