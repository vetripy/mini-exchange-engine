package org.trading.exchange.stub;

import java.util.UUID;
import org.trading.exchange.engine.command.EngineCommand;
import org.trading.exchange.engine.command.NewOrderCommand;
import org.trading.exchange.model.OrderSide;
import org.trading.exchange.model.OrderType;

public class EngineCommandStub {

  public static EngineCommand getValidLimitBuyCommand(String symbol, long price, long quantity) {
    return NewOrderCommand.of(UUID.randomUUID().toString(), symbol, OrderSide.BUY, OrderType.LIMIT,
            "user1", price, quantity, System.currentTimeMillis());
  }

  public static EngineCommand getValidLimitSellCommand(String symbol, long price, long quantity) {
    return NewOrderCommand.of(UUID.randomUUID().toString(), symbol, OrderSide.SELL, OrderType.LIMIT,
            "user2", price, quantity, System.currentTimeMillis());
  }

}
