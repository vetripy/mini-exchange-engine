package org.trading.exchange.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

@Getter
@ToString
@Builder
@AllArgsConstructor
public class Order {
    private final String id;
    private final String userId;
    private final OrderSide side;
    private final long price;
    private long remainingQuantity;
    private final Instant timestamp;

    public void reduceQuantity(long quantity) {
        if (quantity > remainingQuantity) {
            throw new IllegalArgumentException("Cannot reduce more than remaining quantity");
        }
        this.remainingQuantity -= quantity;
    }
}
