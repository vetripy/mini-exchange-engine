package org.trading.exchange.model;

import lombok.Getter;
import lombok.Builder;

@Getter
@Builder
public class Trade {

    private final long sequence;
    private final String tradeId;
    private final String buyOrderId;
    private final String sellOrderId;
    private final long tradePrice;
    private final long quantity;
    private final long timestamp;
}
