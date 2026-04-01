package org.trading.exchange.utils;

import lombok.extern.slf4j.Slf4j;
import org.trading.exchange.engine.EngineStateListener;
import org.trading.exchange.model.EngineState;

@Slf4j
public class TestEngineStateListener implements EngineStateListener {

    @Override
    public void onStateChange(EngineState oldState, EngineState newState, Throwable cause) {
        if (cause != null) {
            log.warn("Engine state changed from {} to {} due to: {}", oldState, newState,
                cause.getMessage());
        } else {
            log.info("Engine state changed from {} to {}", oldState, newState);
        }
    }
}
