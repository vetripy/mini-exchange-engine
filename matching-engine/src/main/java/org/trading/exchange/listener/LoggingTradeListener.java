package org.trading.exchange.listener;

import org.trading.exchange.event.TradeListener;
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