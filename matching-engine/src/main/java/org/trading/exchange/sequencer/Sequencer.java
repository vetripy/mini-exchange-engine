package org.trading.exchange.sequencer;

import org.trading.exchange.event.OrderEvent;
import org.trading.exchange.model.Envelope;
import org.trading.exchange.util.EnvelopeUtil;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class Sequencer {
    private final BlockingQueue<Envelope<EngineCommand>> messageQueue;
    private final AtomicLong sequenceNumber = new AtomicLong(0);

    public Sequencer(BlockingQueue<Envelope<EngineCommand>> messageQueue) {
        this.messageQueue = messageQueue;
    }

    public void submit(EnginCommand command) throws InterruptedException {
        long seqNum = sequenceNumber.incrementAndGet();
        Envelope<EngineCommand> envelope = EnvelopeUtil.wrap(seqNum, command);
        messageQueue.put(envelope);
    }
}
