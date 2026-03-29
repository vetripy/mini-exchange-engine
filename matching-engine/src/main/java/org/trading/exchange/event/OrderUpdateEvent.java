package org.trading.exchange.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.trading.exchange.model.OrderState;

@Getter
@Builder
@ToString
public class OrderUpdateEvent {

    private final long sequence;
    private final String orderId;
    private final OrderState orderState;
    private final long remainingQuantity;
    private final long timestamp;
}
