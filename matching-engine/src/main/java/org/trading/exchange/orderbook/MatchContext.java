package org.trading.exchange.orderbook;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.trading.exchange.event.EngineEvent;

@Getter
@Setter
public class MatchContext {

    private long sequence;
    private final List<EngineEvent> events = new ArrayList<>(100);

    MatchContext() {
    }

    void emit(EngineEvent event) {
        events.add(event);
    }

    void clear() {
        events.clear();
    }
}
