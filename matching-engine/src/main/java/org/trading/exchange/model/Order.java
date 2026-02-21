package org.trading.exchange.model;

import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Getter
@ToString
public class Order {
    private final String id;
    private final String userId;
    private final OrderSide side;
    private final long price;
    private long remainingQuantity;
    private final Instant timestamp;

    public Order(String userId, OrderSide side, long price, long remainingQuantity) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.side = side;
        this.price = price;
        this.remainingQuantity = remainingQuantity;
        this.timestamp = Instant.now();
    }

    public void reduceQuantity(long quantity) {
        if (quantity > remainingQuantity) {
            throw new IllegalArgumentException("Cannot reduce more than remaining quantity");
        }
        this.remainingQuantity -= quantity;
    }
}
