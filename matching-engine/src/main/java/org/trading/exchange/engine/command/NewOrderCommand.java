package org.trading.exchange.engine.command;

import lombok.Builder;
import lombok.Getter;
import org.trading.exchange.model.Order;

@Getter
@Builder
public class NewOrderCommand implements EngineCommand {

    private final Order order;

    public static NewOrderCommand of(Order order) {
        return NewOrderCommand.builder()
            .order(order)
            .build();
    }

}
