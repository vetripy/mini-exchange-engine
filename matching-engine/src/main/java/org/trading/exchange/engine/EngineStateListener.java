package org.trading.exchange.engine;

import org.trading.exchange.model.EngineState;

public interface EngineStateListener {
    void onStateChange(EngineState oldState, EngineState newState, Throwable cause);
}