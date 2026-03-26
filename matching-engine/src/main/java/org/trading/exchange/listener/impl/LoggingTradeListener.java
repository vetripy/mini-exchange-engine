package org.trading.exchange.listener.impl;

import org.trading.exchange.event.TradeEvent;
import org.trading.exchange.listener.TradeListener;

public class LoggingTradeListener implements TradeListener {

  @Override
  public void onTrade(TradeEvent tradeEvent) {
    System.out.println(
        "TRADE EXECUTED → " + tradeEvent.getQuantity() + " @ " + tradeEvent.getTradePrice());
  }
}
