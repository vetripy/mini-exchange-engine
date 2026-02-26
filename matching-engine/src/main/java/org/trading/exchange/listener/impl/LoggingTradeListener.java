package org.trading.exchange.listener.impl;

import org.trading.exchange.listener.TradeListener;
import org.trading.exchange.model.Trade;


public class LoggingTradeListener implements TradeListener {

    @Override
    public void onTrade(Trade trade) {
        System.out.println("TRADE EXECUTED → "
                + trade.getQuantity()
                + " @ "
                + trade.getPrice());
    }
}