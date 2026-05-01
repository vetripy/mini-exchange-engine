package org.trading.exchange.model;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@ToString
@Builder
public class Order {

    private final String orderId;
    private final String clientOrderId;
    private final String userId;
    private final Symbol symbol;
    private final OrderSide side;
    private final OrderType type;
    private final long price;
    private final long timestamp;
    @Builder.Default
    private long remainingQuantity = 0L;
    @Builder.Default
    @Setter
    private OrderState state = OrderState.NEW;

    // helper factory methods for creating different types of orders with validation
    // can be removed or moved to a factory class if needed, used only for tests.

    public static Order createLimitOrder(String id, String userId, OrderSide side, long price,
        long quantity) {
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be a positive value for limit orders");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return Order.builder().orderId(id).userId(userId).side(side).type(OrderType.LIMIT)
            .price(price).remainingQuantity(quantity)
            .timestamp(System.currentTimeMillis()).build();
    }

    public static Order createMarketOrder(String id, String userId, OrderSide side, long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return Order.builder().orderId(id).userId(userId).side(side).type(OrderType.MARKET)
            .price(0).remainingQuantity(quantity)
            .timestamp(System.currentTimeMillis()).build();
    }

    public static Order createIOCOrder(String id, String userId, OrderSide side, Long price,
        Long quantity) {
        if (price == null || price <= 0) {
            throw new IllegalArgumentException("Price must be a positive value for IOC orders");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return Order.builder().orderId(id).userId(userId).side(side).type(OrderType.IOC)
            .price(price).remainingQuantity(quantity)
            .timestamp(System.currentTimeMillis()).build();
    }

    public static Order createFOKOrder(String id, String userId, OrderSide side, Long price,
        Long quantity) {
        if (price == null || price <= 0) {
            throw new IllegalArgumentException("Price must be a positive value for FOK orders");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive value");
        }
        return Order.builder().orderId(id).userId(userId).side(side).type(OrderType.FOK)
            .price(price).remainingQuantity(quantity)
            .timestamp(System.currentTimeMillis()).build();
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
