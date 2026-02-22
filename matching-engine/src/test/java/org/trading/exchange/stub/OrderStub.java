package org.trading.exchange.stub;

import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;

import java.util.UUID;

public class OrderStub {
    public static Order getValidBuyOrderWith(Long price, Long quantity) {
        return new Order(
                UUID.randomUUID().toString(),
                "user1",
                OrderSide.BUY,
                price,
                quantity
        );
    }

    public static Order getValidSellOrderWith(Long price, Long quantity) {
        return new Order(
                UUID.randomUUID().toString(),
                "user2",
                OrderSide.SELL,
                price,
                quantity
        );
    }
}
