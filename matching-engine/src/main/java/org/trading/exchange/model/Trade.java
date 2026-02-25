package org.trading.exchange.model;

import lombok.Getter;
import lombok.Builder;

@Getter
@Builder
public class Trade {

    private final String tradeId;
    private final String buyOrderId;
    private final String sellOrderId;
    private final long price;
    private final long quantity;
    private final long timestamp;
}
