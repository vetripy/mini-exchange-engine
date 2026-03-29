package org.trading.exchange.utils;

import java.util.ArrayList;
import java.util.List;
import org.trading.exchange.event.TradeEvent;
import org.trading.exchange.listener.TradeListener;

public class TestTradeListener implements TradeListener {

    private final List<TradeEvent> tradeEvents = new ArrayList<>();

    @Override
    public void onTrade(TradeEvent tradeEvent) {
        tradeEvents.add(tradeEvent);
    }

    public List<TradeEvent> getTrades() {
        return tradeEvents;
    }
}
