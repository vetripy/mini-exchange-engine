package org.trading.exchange.stub;

import java.util.UUID;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;

public class OrderStub {

    public static Order getValidLimitBuyOrderWith(Long price, Long quantity) {
        return OrderFactory.createLimitOrder(UUID.randomUUID().getMostSignificantBits(), "user1",
                        OrderSide.BUY, price, quantity);
    }

    public static Order getValidLimitSellOrderWith(Long price, Long quantity) {
        return OrderFactory.createLimitOrder(UUID.randomUUID().getMostSignificantBits(), "user2",
                        OrderSide.SELL, price, quantity);
    }

    public static Order getValidMarketBuyOrderWith(Long quantity) {
        return OrderFactory.createMarketOrder(UUID.randomUUID().getMostSignificantBits(), "user1",
                        OrderSide.BUY, quantity);
    }

    public static Order getValidMarketSellOrderWith(Long quantity) {
        return OrderFactory.createMarketOrder(UUID.randomUUID().getMostSignificantBits(), "user2",
                        OrderSide.SELL, quantity);
    }

    public static Order getValidIOCBuyOrderWith(Long price, Long quantity) {
        return OrderFactory.createIOCOrder(UUID.randomUUID().getMostSignificantBits(), "user1",
                        OrderSide.BUY, price, quantity);
    }

    public static Order getValidIOCSellOrderWith(Long price, Long quantity) {
        return OrderFactory.createIOCOrder(UUID.randomUUID().getMostSignificantBits(), "user2",
                        OrderSide.SELL, price, quantity);
    }

    public static Order getValidFOKBuyOrderWith(Long price, Long quantity) {
        return OrderFactory.createFOKOrder(UUID.randomUUID().getMostSignificantBits(), "user1",
                        OrderSide.BUY, price, quantity);
    }

    public static Order getValidFOKSellOrderWith(Long price, Long quantity) {
        return OrderFactory.createFOKOrder(UUID.randomUUID().getMostSignificantBits(), "user2",
                        OrderSide.SELL, price, quantity);
    }
}
