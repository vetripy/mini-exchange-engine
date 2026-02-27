package org.trading.exchange.event;

import org.trading.exchange.model.Trade;

public interface EngineEventHandler {

    void onTrade(Trade event);

    void onOrderUpdate(OrderUpdate event);

}
