package org.trading.exchange.model;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderUpdate {
    private final String orderId;
    private final OrderState orderState;
    private final long remainingQuantity;
    private final long timestamp;
}
