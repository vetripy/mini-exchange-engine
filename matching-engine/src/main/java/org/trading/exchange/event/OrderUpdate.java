package org.trading.exchange.event;

import lombok.Builder;
import lombok.Getter;
import org.trading.exchange.model.OrderState;

@Getter
@Builder
public class OrderUpdate {
    private final long sequence;
    private final String orderId;
    private final OrderState orderState;
    private final long remainingQuantity;
    private final long timestamp;
}
