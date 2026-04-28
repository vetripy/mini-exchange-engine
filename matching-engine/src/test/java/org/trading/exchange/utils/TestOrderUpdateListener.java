package org.trading.exchange.utils;

import java.util.ArrayList;
import java.util.List;
import org.trading.exchange.event.OrderUpdateEvent;
import org.trading.exchange.listener.OrderUpdateListener;

public class TestOrderUpdateListener implements OrderUpdateListener {

  private final List<OrderUpdateEvent> updates = new ArrayList<>();

  @Override
  public void onOrderUpdate(OrderUpdateEvent update) {
    updates.add(update);
  }

  public List<OrderUpdateEvent> getUpdates() {
    return updates;
  }

  public OrderUpdateEvent latest() {
    return updates.getLast();
  }
}
