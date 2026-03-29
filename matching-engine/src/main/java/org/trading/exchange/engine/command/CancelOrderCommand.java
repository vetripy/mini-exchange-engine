package org.trading.exchange.engine.command;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class CancelOrderCommand implements EngineCommand {

    private final String orderId;

    public static CancelOrderCommand of(String orderId) {
        return CancelOrderCommand.builder()
            .orderId(orderId)
            .build();
    }


}
