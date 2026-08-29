package org.trading.exchange.orderbook;

import java.util.Iterator;
import java.util.NoSuchElementException;
import org.trading.exchange.model.Order;

/** FIFO queue of resting orders at a single price, as an intrusive doubly-linked list. */
final class PriceLevel implements Iterable<Order> {

    private Order head;
    private Order tail;
    private long totalQuantity;
    private int orderCount;

    boolean isEmpty() {
        return head == null;
    }

    Order peekFirst() {
        return head;
    }

    long totalQuantity() {
        return totalQuantity;
    }

    int orderCount() {
        return orderCount;
    }

    void addLast(Order order) {
        order.setPrev(tail);
        order.setNext(null);
        if (tail == null) {
            head = order;
        } else {
            tail.setNext(order);
        }
        tail = order;
        totalQuantity += order.getRemainingQuantity();
        orderCount++;
    }

    /** Unlinks the given order in O(1) — no scan, regardless of its position in the queue. */
    void remove(Order order) {
        Order prev = order.getPrev();
        Order next = order.getNext();

        if (prev == null) {
            head = next;
        } else {
            prev.setNext(next);
        }

        if (next == null) {
            tail = prev;
        } else {
            next.setPrev(prev);
        }

        order.setPrev(null);
        order.setNext(null);
        totalQuantity -= order.getRemainingQuantity();
        orderCount--;
    }

    /** Reflects a fill against the order at the head of the queue into the level's aggregate. */
    void reduceQuantity(long quantity) {
        totalQuantity -= quantity;
    }

    @Override
    public Iterator<Order> iterator() {
        return new Iterator<>() {
            private Order cursor = head;

            @Override
            public boolean hasNext() {
                return cursor != null;
            }

            @Override
            public Order next() {
                if (cursor == null) {
                    throw new NoSuchElementException();
                }
                Order current = cursor;
                cursor = cursor.getNext();
                return current;
            }
        };
    }
}
