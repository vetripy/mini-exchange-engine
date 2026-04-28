package org.trading.exchange.event;

import lombok.Builder;
import lombok.Getter;

import org.trading.exchange.model.OrderState;
import org.trading.exchange.model.Symbol;

@Getter
@Builder
public class OrderUpdateEvent implements EngineEvent {

  private final long sequence;
  private final String orderId;
  private final String clientOrderId;
  private final OrderState orderState;
  private final Symbol symbol;
  private final long remainingQuantity;
  private final long timestamp;
}
