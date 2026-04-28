package org.trading.exchange.listener.impl;

import lombok.extern.slf4j.Slf4j;
import org.trading.exchange.event.OrderUpdateEvent;
import org.trading.exchange.listener.OrderUpdateListener;

@Slf4j
public class LoggingOrderUpdateListener implements OrderUpdateListener {

  @Override
  public void onOrderUpdate(OrderUpdateEvent update) {
    log.info("ORDER UPDATE → {} {} remaining: {}", update.getOrderId(), update.getOrderState(),
            update.getRemainingQuantity());
  }
}
