package org.trading.exchange.listener.impl;

import lombok.extern.slf4j.Slf4j;
import org.trading.exchange.event.TradeEvent;
import org.trading.exchange.listener.TradeListener;

@Slf4j
public class LoggingTradeListener implements TradeListener {

  @Override
  public void onTrade(TradeEvent tradeEvent) {
    log.info("TRADE EXECUTED → {} @ {}", tradeEvent.getQuantity(), tradeEvent.getTradePrice());
  }
}
