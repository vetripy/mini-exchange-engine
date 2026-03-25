package org.trading.exchange.listener;

import org.trading.exchange.event.TradeEvent;

public interface TradeListener {
    void onTrade(TradeEvent tradeEvent);
}
