package org.trading.exchange.event;

import lombok.Getter;
import lombok.ToString;
import org.trading.exchange.model.Order;

@Getter
@ToString
public class OrderEvent {
    private final EventType type;
    private final String orderId;
    private final Order order;

    private OrderEvent(EventType type, String orderId, Order order) {
        this.type = type;
        this.orderId = orderId;
        this.order = order;
    }

    public static OrderEvent newOrder(Order order) {
        return new OrderEvent(EventType.NEW_ORDER, null, order);
    }

    public static OrderEvent cancelOrder(String orderId) {
        return new OrderEvent(EventType.CANCEL_ORDER, orderId, null);
    }
}
