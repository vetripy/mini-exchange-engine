package org.trading.exchange.stub;

import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;

import java.util.UUID;

public class OrderStub {
    public static Order getValidLimitBuyOrderWith(Long price, Long quantity) {
        return Order.createLimitOrder(
                UUID.randomUUID().toString(),
                "user1",
                OrderSide.BUY,
                price,
                quantity
        );
    }

    public static Order getValidLimitSellOrderWith(Long price, Long quantity) {
        return Order.createLimitOrder(
                UUID.randomUUID().toString(),
                "user2",
                OrderSide.SELL,
                price,
                quantity
        );
    }

    public static Order getValidMarketBuyOrderWith(Long quantity) {
        return Order.createMarketOrder(
                UUID.randomUUID().toString(),
                "user1",
                OrderSide.BUY,
                quantity
        );
    }

    public static Order getValidMarketSellOrderWith(Long quantity) {
        return Order.createMarketOrder(
                UUID.randomUUID().toString(),
                "user2",
                OrderSide.SELL,
                quantity
        );
    }

    public static Order getValidIOCBuyOrderWith(Long price, Long quantity) {
        return Order.createIOCOrder(
                UUID.randomUUID().toString(),
                "user1",
                OrderSide.BUY,
                price,
                quantity
        );
    }

    public static Order getValidIOCSellOrderWith(Long price, Long quantity) {
        return Order.createIOCOrder(
                UUID.randomUUID().toString(),
                "user2",
                OrderSide.SELL,
                price,
                quantity
        );
    }

    public static Order getValidFOKBuyOrderWith(Long price, Long quantity) {
        return Order.createFOKOrder(
                UUID.randomUUID().toString(),
                "user1",
                OrderSide.BUY,
                price,
                quantity
        );
    }

    public static Order getValidFOKSellOrderWith(Long price, Long quantity) {
        return Order.createFOKOrder(
                UUID.randomUUID().toString(),
                "user2",
                OrderSide.SELL,
                price,
                quantity
        );
    }
}
