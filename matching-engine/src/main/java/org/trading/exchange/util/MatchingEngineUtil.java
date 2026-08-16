package org.trading.exchange.util;

import org.trading.exchange.engine.command.NewOrderCommand;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.Symbol;

public final class MatchingEngineUtil {

    public static Order buildOrderFromCommand(NewOrderCommand cmd, long seq) {
        return new Order(seq, cmd.getClientOrderId(), cmd.getUserId(), Symbol.from(cmd.getSymbol()),
            cmd.getSide(), cmd.getType(), cmd.getPrice(), cmd.getQuantity(),
            cmd.getTimestamp());
    }

}
