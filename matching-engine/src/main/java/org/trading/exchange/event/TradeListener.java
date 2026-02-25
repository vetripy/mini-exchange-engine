package org.trading.exchange.event;

import org.trading.exchange.model.Trade;

public interface TradeListener {
    void onTrade(Trade trade);
}
