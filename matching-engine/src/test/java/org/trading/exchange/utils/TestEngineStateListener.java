package org.trading.exchange.utils;

import org.trading.exchange.engine.EngineStateListener;
import org.trading.exchange.model.EngineState;

public class TestEngineStateListener implements EngineStateListener {
  @Override
  public void onStateChange(EngineState oldState, EngineState newState, Throwable cause) {
    System.out.println(
        "Engine state changed from "
            + oldState
            + " to "
            + newState
            + (cause != null ? " due to: " + cause.getMessage() : ""));
  }
}
