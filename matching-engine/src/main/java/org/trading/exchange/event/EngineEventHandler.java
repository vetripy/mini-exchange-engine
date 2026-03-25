package org.trading.exchange.event;

public interface EngineEventHandler {

    void onTrade(TradeEvent event);

    void onOrderUpdate(OrderUpdate event);
}
