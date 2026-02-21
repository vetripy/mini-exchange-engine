package org.trading.exchange.model;

import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Getter
@ToString
public class Trade {
    private final String id;
    private final String buyOrderId;
    private final String sellOrderId;
    private final long price;
    private final long quantity;
    private final Instant timestamp;

    public Trade(String buyOrderId, String sellOrderId, long price, long quantity) {
        this.id = UUID.randomUUID().toString();
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.price = price;
        this.quantity = quantity;
        this.timestamp = Instant.now();
    }
}
