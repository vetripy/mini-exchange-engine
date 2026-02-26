package org.trading.exchange.listener;

import org.trading.exchange.model.Trade;

public interface TradeListener {
    void onTrade(Trade trade);
}
