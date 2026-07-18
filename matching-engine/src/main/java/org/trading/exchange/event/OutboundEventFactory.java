package org.trading.exchange.event;


import com.lmax.disruptor.EventFactory;

public class OutboundEventFactory implements EventFactory<OutboundEvent> {

    @Override
    public OutboundEvent newInstance() {
        return new OutboundEvent();
    }
}
