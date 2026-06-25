package org.trading.exchange.validators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.trading.exchange.model.Order;
import org.trading.exchange.model.OrderSide;
import org.trading.exchange.model.OrderType;
import org.trading.exchange.stub.OrderFactory;

class OrderValidatorTest {

    private OrderValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OrderValidator();
    }

    @Nested
    @DisplayName("Null Order Validation")
    class NullOrderValidation {

        @Test
        @DisplayName("Should throw NullPointerException when order is null")
        void validateNullOrder() {
            assertThrows(NullPointerException.class, () -> validator.validateInvariants(null),
                "Order cannot be null");
        }
    }

    @Nested
    @DisplayName("Order ID Validation")
    class OrderIdValidation {

        @Test
        @DisplayName("Should throw NullPointerException when order ID is null")
        void validateNullOrderId() {
            Order order = new Order(null, null, "user123", null, OrderSide.BUY,
                OrderType.LIMIT, 100L, 10L, System.currentTimeMillis());

            assertThrows(NullPointerException.class, () -> validator.validateInvariants(order),
                "Order ID cannot be null");
        }
    }

    @Nested
    @DisplayName("Quantity Validation")
    class QuantityValidation {

        @Test
        @DisplayName("Should throw IllegalStateException when quantity is zero")
        void validateZeroQuantity() {
            Order order = new Order("order123", null, "user123", null, OrderSide.BUY,
                OrderType.LIMIT, 100L, 0L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Quantity must be positive");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when quantity is negative")
        void validateNegativeQuantity() {
            Order order = new Order("order123", null, "user123", null, OrderSide.BUY,
                OrderType.LIMIT, 100L, -10L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Quantity must be positive");
        }
    }

    @Nested
    @DisplayName("LIMIT Order Validation")
    class LimitOrderValidation {

        @Test
        @DisplayName("Should pass with valid LIMIT order")
        void validateValidLimitOrder() {
            Order order = OrderFactory.createLimitOrder("order123", "user123", OrderSide.BUY, 100L,
                10L);
            assertDoesNotThrow(() -> validator.validateInvariants(order));
        }

        @Test
        @DisplayName("Should throw IllegalStateException when LIMIT order has null price")
        void validateLimitOrderWithNullPrice() {
            Order order = new Order("order123", null, "user123", null, OrderSide.BUY,
                OrderType.LIMIT, 0L, 10L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Order type LIMIT requires positive price");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when LIMIT order has zero price")
        void validateLimitOrderWithZeroPrice() {
            Order order = new Order("order123", null, "user123", null, OrderSide.BUY,
                OrderType.LIMIT, 0L, 10L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Order type LIMIT requires positive price");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when LIMIT order has negative price")
        void validateLimitOrderWithNegativePrice() {
            Order order = new Order("order123", null, "user123", null, OrderSide.BUY,
                OrderType.LIMIT, -100L, 10L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Order type LIMIT requires positive price");
        }
    }

    @Nested
    @DisplayName("IOC Order Validation")
    class IOCOrderValidation {

        @Test
        @DisplayName("Should pass with valid IOC order")
        void validateValidIOCOrder() {
            Order order = OrderFactory.createIOCOrder("order123", "user123", OrderSide.SELL, 100L,
                10L);
            assertDoesNotThrow(() -> validator.validateInvariants(order));
        }

        @Test
        @DisplayName("Should throw IllegalStateException when IOC order has null price")
        void validateIOCOrderWithNullPrice() {
            Order order = new Order("order123", null, "user123", null, OrderSide.SELL,
                OrderType.IOC, 0L, 10L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Order type IOC requires positive price");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when IOC order has zero price")
        void validateIOCOrderWithZeroPrice() {
            Order order = new Order("order123", null, "user123", null, OrderSide.SELL,
                OrderType.IOC, 0L, 10L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Order type IOC requires positive price");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when IOC order has negative price")
        void validateIOCOrderWithNegativePrice() {
            Order order = new Order("order123", null, "user123", null, OrderSide.SELL,
                OrderType.IOC, -50L, 10L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Order type IOC requires positive price");
        }
    }

    @Nested
    @DisplayName("FOK Order Validation")
    class FOKOrderValidation {

        @Test
        @DisplayName("Should pass with valid FOK order")
        void validateValidFOKOrder() {
            Order order = OrderFactory.createFOKOrder("order123", "user123", OrderSide.BUY, 150L,
                20L);
            assertDoesNotThrow(() -> validator.validateInvariants(order));
        }

        @Test
        @DisplayName("Should throw IllegalStateException when FOK order has null price")
        void validateFOKOrderWithNullPrice() {
            Order order = new Order("order123", null, "user123", null, OrderSide.BUY,
                OrderType.FOK, 0L, 10L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Order type FOK requires positive price");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when FOK order has zero price")
        void validateFOKOrderWithZeroPrice() {
            Order order = new Order("order123", null, "user123", null, OrderSide.BUY,
                OrderType.FOK, 0L, 10L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Order type FOK requires positive price");
        }

        @Test
        @DisplayName("Should throw IllegalStateException when FOK order has negative price")
        void validateFOKOrderWithNegativePrice() {
            Order order = new Order("order123", null, "user123", null, OrderSide.BUY,
                OrderType.FOK, -75L, 10L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Order type FOK requires positive price");
        }
    }

    @Nested
    @DisplayName("MARKET Order Validation")
    class MarketOrderValidation {

        @Test
        @DisplayName("Should pass with valid MARKET order without price")
        void validateValidMarketOrder() {
            Order order = OrderFactory.createMarketOrder("order123", "user123", OrderSide.BUY, 10L);
            assertDoesNotThrow(() -> validator.validateInvariants(order));
        }

        @Test
        @DisplayName("Should throw IllegalStateException when MARKET order has price")
        void validateMarketOrderWithPrice() {
            Order order = new Order("order123", null, "user123", null, OrderSide.BUY,
                OrderType.MARKET, 100L, 10L, System.currentTimeMillis());

            assertThrows(IllegalStateException.class, () -> validator.validateInvariants(order),
                "Market orders should not have price");
        }
    }
}
