package org.trading.exchange.utils;

import java.util.ArrayList;
import java.util.List;
import org.trading.exchange.listener.TradeListener;
import org.trading.exchange.model.Trade;

public class TestTradeListener implements TradeListener {

  private final List<Trade> trades = new ArrayList<>();

  @Override
  public void onTrade(Trade trade) {
    trades.add(trade);
  }

  public List<Trade> getTrades() {
    return trades;
  }
}
