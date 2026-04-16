package org.trading.exchange.util;

import java.util.List;
import java.util.Map;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;

public final class OrderBookUtil {

    public static void printDepth(Map<Long, List<Order>> buyOrders,
            Map<Long, List<Order>> sellOrders) {
        System.out.println("\n================ ORDER BOOK ================");

        System.out.println("\nSELL SIDE");
        System.out.printf("%-10s %-10s%n", "Price", "Quantity");

        for (var entry : sellOrders.entrySet()) {
            long totalQty = entry.getValue().stream().mapToLong(Order::getRemainingQuantity).sum();

            if (totalQty > 0) {
                System.out.printf("%-10d %-10d%n", entry.getKey(), totalQty);
            }
        }

        System.out.println("\n--------------------------------------------");

        System.out.println("\nBUY SIDE");
        System.out.printf("%-10s %-10s%n", "Price", "Quantity");

        for (var entry : buyOrders.entrySet()) {
            long totalQty = entry.getValue().stream().mapToLong(Order::getRemainingQuantity).sum();

            if (totalQty > 0) {
                System.out.printf("%-10d %-10d%n", entry.getKey(), totalQty);
            }
        }

        System.out.println("\n============================================\n");
    }

    public static String getOrderId(Order restingOrder, Order matchingOrder, OrderSide side) {
        return side == restingOrder.getSide() ? restingOrder.getOrderId()
                : matchingOrder.getOrderId();
    }

    public static String getClientOrderId(Order restingOrder, Order matchingOrder, OrderSide side) {
        return side == restingOrder.getSide() ? restingOrder.getClientOrderId()
                : matchingOrder.getClientOrderId();
    }

}
