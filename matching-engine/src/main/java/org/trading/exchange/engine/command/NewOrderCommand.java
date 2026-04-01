package org.trading.exchange.engine.command;

import lombok.Builder;
import lombok.Getter;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;
import org.trading.exchange.model.OrderType;

@Getter
@Builder
public class NewOrderCommand implements EngineCommand {

    private final String clientOrderId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final String userId;
    private final Long price;
    private final Long quantity;


    public static NewOrderCommand of(
        String clientOrderId,
        String symbol,
        OrderSide side,
        OrderType type,
        String userId,
        Long price,
        Long quantity
    ) {
        return NewOrderCommand.builder().clientOrderId(clientOrderId)
            .symbol(symbol)
            .side(side)
            .type(type)
            .price(price)
            .userId(userId)
            .quantity(quantity)
            .build();
    }
}
