package org.trading.exchange.model;

import lombok.*;

import java.time.Instant;

@Getter
@ToString
@Builder(access = AccessLevel.PRIVATE)
public class Order {
    private final String id;
    private final String userId;
    private final OrderSide side;
    private final Long price;
    private final Instant timestamp;
    private final OrderType type;
    private Long remainingQuantity;
    @Setter
    private OrderState state;

    public static Order createLimitOrder(String id, String userId, OrderSide side, Long price, Long quantity) {
        if (price == null || price <= 0) {
            throw new IllegalArgumentException("Price must be a positive value for limit orders");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return Order.builder()
                .id(id)
                .userId(userId)
                .side(side)
                .type(OrderType.LIMIT)
                .price(price)
                .remainingQuantity(quantity)
                .timestamp(Instant.now())
                .build();
    }

    public static Order createMarketOrder(String id, String userId, OrderSide side, Long quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return Order.builder()
                .id(id)
                .userId(userId)
                .side(side)
                .type(OrderType.MARKET)
                .price(null)
                .remainingQuantity(quantity)
                .timestamp(Instant.now())
                .build();
    }

    public void reduceQuantity(long quantity) {
        if (quantity > remainingQuantity) {
            throw new IllegalArgumentException("Cannot reduce more than remaining quantity");
        }
        this.remainingQuantity -= quantity;
        if (this.remainingQuantity == 0) {
            this.state = OrderState.FILLED;
        } else {
            this.state = OrderState.PARTIALLY_FILLED;
        }
    }
}
