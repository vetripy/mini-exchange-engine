package org.trading.exchange.engine.command;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class CancelOrderCommand implements EngineCommand {

    private final String clientOrderId;

    public static CancelOrderCommand of(String clientOrderId) {
        return CancelOrderCommand.builder().clientOrderId(clientOrderId).build();
    }


}
