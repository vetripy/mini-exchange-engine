package org.trading.exchange.orderbook;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.trading.exchange.event.EngineEvent;

@Getter
public class MatchContext {

    private final long sequence;
    private final List<EngineEvent> events;

    MatchContext(long sequence) {
        this.sequence = sequence;
        this.events = new ArrayList<>(32);
    }

    void emit(EngineEvent event) {
        events.add(event);
    }
}
