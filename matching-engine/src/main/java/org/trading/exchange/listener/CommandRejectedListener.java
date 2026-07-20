package org.trading.exchange.listener;

import org.trading.exchange.event.CommandRejectedEvent;

public interface CommandRejectedListener {

    void onCommandRejected(CommandRejectedEvent commandRejectedEvent);
}
