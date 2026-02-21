package org.trading.exchange.stub;

import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;

import java.time.Instant;
import java.util.UUID;

public class OrderStub {
    public static Order getValidBuyOrderWith(Long price, Long quantity) {
        return Order.builder()
                .id(UUID.randomUUID().toString())
                .side(OrderSide.BUY)
                .price(price)
                .userId("user1")
                .remainingQuantity(quantity)
                .timestamp(Instant.EPOCH)
                .build();
    }

    public static Order getValidSellOrderWith(Long price, Long quantity) {
        return Order.builder()
                .id(UUID.randomUUID().toString())
                .userId("user2")
                .side(OrderSide.SELL)
                .price(price)
                .remainingQuantity(quantity)
                .timestamp(Instant.EPOCH)
                .build();
    }
}
