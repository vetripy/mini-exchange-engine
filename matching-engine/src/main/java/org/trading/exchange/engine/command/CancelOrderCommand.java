package org.trading.exchange.engine.command;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
public class CancelOrderCommand implements EngineCommand {

    private final String clientOrderId;

    @Setter
    private long sequence;

    @Builder
    public CancelOrderCommand(String clientOrderId) {
        this.clientOrderId = clientOrderId;
    }

    public static CancelOrderCommand of(String clientOrderId) {
        return CancelOrderCommand.builder().clientOrderId(clientOrderId).build();
    }
}
