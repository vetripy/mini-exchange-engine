package org.trading.exchange.engine.command;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.trading.exchange.model.OrderSide;
import org.trading.exchange.model.OrderType;

@Getter
public class NewOrderCommand implements EngineCommand {

    private final String clientOrderId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final String userId;
    private final long price;
    private final long quantity;
    private final long timestamp;

    @Setter
    private long sequence;

    @Builder
    public NewOrderCommand(String clientOrderId, String symbol, OrderSide side, OrderType type,
                    String userId, long price, long quantity, long timestamp) {
        this.clientOrderId = clientOrderId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.userId = userId;
        this.price = price;
        this.quantity = quantity;
        this.timestamp = timestamp;
    }

    public static NewOrderCommand of(String clientOrderId, String symbol, OrderSide side,
                    OrderType type, String userId, long price, long quantity, long timestamp) {
        return NewOrderCommand.builder().clientOrderId(clientOrderId).symbol(symbol).side(side)
                        .type(type).price(price).userId(userId).quantity(quantity)
                        .timestamp(timestamp).build();
    }
}
