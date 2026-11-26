
package com.struchev.invest.service.tinkoff;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderExecutionReportStatus;
import ru.tinkoff.piapi.contract.v1.PostOrderResponse;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class TinkoffOrderResponseMapperTest {

    private static final String CLIENT_ID =
            "550e8400-e29b-41d4-a716-446655440000";

    private static final String BROKER_ID =
            "BROKER_ORDER_123";

    private static final String FIGI =
            "TEST_FIGI";

    private final TinkoffOrderResponseMapper mapper =
            new TinkoffOrderResponseMapper();

    private TradeOrderEntity createOrder() {

        return TradeOrderEntity.builder()
                .clientOrderId(CLIENT_ID)
                .accountId("TEST_ACCOUNT")
                .environment(TradeOrderEntity.Environment.SANDBOX)
                .figi(FIGI)
                .strategy("TEST_STRATEGY")
                .direction(TradeOrderEntity.Direction.BUY)
                .status(TradeOrderStatus.SUBMITTING)
                .requestedLots(10L)
                .lotSize(10)
                .requestedPrice(new BigDecimal("250.00"))
                .currency("rub")
                .reconciled(false)
                .build();
    }

    private MoneyValue money(
            String currency,
            long units,
            int nano
    ) {

        return MoneyValue.newBuilder()
                .setCurrency(currency)
                .setUnits(units)
                .setNano(nano)
                .build();
    }

    private PostOrderResponse.Builder responseBuilder() {

        return PostOrderResponse.newBuilder()
                .setOrderId(BROKER_ID)
                .setOrderRequestId(CLIENT_ID)
                .setFigi(FIGI)
                .setDirection(
                        OrderDirection.ORDER_DIRECTION_BUY
                )
                .setExecutionReportStatus(
                        OrderExecutionReportStatus
                                .EXECUTION_REPORT_STATUS_PARTIALLYFILL
                )
                .setLotsRequested(10L)
                .setLotsExecuted(4L)
                .setExecutedOrderPrice(
                        money("rub", 250, 750_000_000)
                )
                .setExecutedCommission(
                        money("rub", 5, 20_000_000)
                )
                .setInitialCommission(
                        money("rub", 99, 0)
                );
    }

    @ParameterizedTest
    @CsvSource({
            "EXECUTION_REPORT_STATUS_NEW, NEW",
            "EXECUTION_REPORT_STATUS_PARTIALLYFILL, PARTIALLY_FILLED",
            "EXECUTION_REPORT_STATUS_FILL, FILLED",
            "EXECUTION_REPORT_STATUS_CANCELLED, CANCELLED",
            "EXECUTION_REPORT_STATUS_REJECTED, REJECTED"
    })
    void shouldMapBrokerStatus(
            OrderExecutionReportStatus brokerStatus,
            TradeOrderStatus expectedStatus
    ) {

        assertEquals(
                expectedStatus,
                mapper.mapStatus(brokerStatus)
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = OrderExecutionReportStatus.class,
            names = {
                    "EXECUTION_REPORT_STATUS_UNSPECIFIED",
                    "UNRECOGNIZED"
            }
    )
    void shouldRejectUnsupportedStatus(
            OrderExecutionReportStatus status
    ) {

        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.mapStatus(status)
        );
    }

    @Test
    void shouldRejectNullStatus() {

        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.mapStatus(null)
        );
    }

    @ParameterizedTest
    @CsvSource({
            "EXECUTION_REPORT_STATUS_PARTIALLYFILL, PARTIALLY_FILLED, 4",
            "EXECUTION_REPORT_STATUS_FILL, FILLED, 10",
            "EXECUTION_REPORT_STATUS_CANCELLED, CANCELLED, 4"
    })
    void shouldMapExecutedOrder(
            OrderExecutionReportStatus brokerStatus,
            TradeOrderStatus expectedStatus,
            long executedLots
    ) {

        var order = createOrder();

        var response = responseBuilder()
                .setExecutionReportStatus(brokerStatus)
                .setLotsExecuted(executedLots)
                .build();

        var result = mapper.mapPostOrderResponse(
                order,
                response
        );

        assertEquals(CLIENT_ID, result.clientOrderId());
        assertEquals(BROKER_ID, result.brokerOrderId());
        assertEquals(expectedStatus, result.status());
        assertEquals(10L, result.requestedLots());
        assertEquals(executedLots, result.executedLots());
        assertEquals("rub", result.currency());

        assertEquals(
                0,
                new BigDecimal("250.75").compareTo(
                        result.averageExecutedPrice()
                )
        );

        assertEquals(
                0,
                new BigDecimal("5.02").compareTo(
                        result.executedCommission()
                )
        );

        // Initial commission must not replace executed commission.
        assertNotEquals(
                0,
                new BigDecimal("99").compareTo(
                        result.executedCommission()
                )
        );

        // Mapper must not modify the persistent order.
        assertEquals(
                TradeOrderStatus.SUBMITTING,
                order.getStatus()
        );

        assertNull(order.getExecutedLots());
        assertNull(order.getBrokerOrderId());
        assertFalse(order.isReconciled());
    }

    @ParameterizedTest
    @CsvSource({
            "EXECUTION_REPORT_STATUS_NEW, NEW",
            "EXECUTION_REPORT_STATUS_CANCELLED, CANCELLED",
            "EXECUTION_REPORT_STATUS_REJECTED, REJECTED"
    })
    void shouldMapUnexecutedOrder(
            OrderExecutionReportStatus brokerStatus,
            TradeOrderStatus expectedStatus
    ) {

        var response = responseBuilder()
                .setExecutionReportStatus(brokerStatus)
                .setLotsExecuted(0L)
                .setExecutedOrderPrice(
                        money("", 0, 0)
                )
                .setExecutedCommission(
                        money("", 0, 0)
                )
                .build();

        var result = mapper.mapPostOrderResponse(
                createOrder(),
                response
        );

        assertEquals(expectedStatus, result.status());
        assertEquals(0L, result.executedLots());
        assertNull(result.averageExecutedPrice());

        assertEquals(
                0,
                result.executedCommission().signum()
        );
    }

    @Test
    void shouldAllowMissingMoneyFieldsForUnexecutedOrder() {

        var response = responseBuilder()
                .setExecutionReportStatus(
                        OrderExecutionReportStatus
                                .EXECUTION_REPORT_STATUS_NEW
                )
                .setLotsExecuted(0L)
                .clearExecutedOrderPrice()
                .clearExecutedCommission()
                .build();

        var result = mapper.mapPostOrderResponse(
                createOrder(),
                response
        );

        assertEquals(TradeOrderStatus.NEW, result.status());
        assertNull(result.averageExecutedPrice());
        assertNull(result.executedCommission());
    }

    @Test
    void shouldAllowRejectedOrderWithoutBrokerId() {

        var response = responseBuilder()
                .setExecutionReportStatus(
                        OrderExecutionReportStatus
                                .EXECUTION_REPORT_STATUS_REJECTED
                )
                .setLotsExecuted(0L)
                .clearOrderId()
                .clearExecutedOrderPrice()
                .clearExecutedCommission()
                .build();

        var result = mapper.mapPostOrderResponse(
                createOrder(),
                response
        );

        assertEquals(
                TradeOrderStatus.REJECTED,
                result.status()
        );

        assertNull(result.brokerOrderId());
    }

    @Test
    void shouldAllowZeroCommission() {

        var response = responseBuilder()
                .setExecutedCommission(
                        money("rub", 0, 0)
                )
                .build();

        var result = mapper.mapPostOrderResponse(
                createOrder(),
                response
        );

        assertEquals(
                0,
                result.executedCommission().signum()
        );
    }

    @Test
    void shouldMapSellOrder() {

        var order = createOrder();

        order.setDirection(
                TradeOrderEntity.Direction.SELL
        );

        var response = responseBuilder()
                .setDirection(
                        OrderDirection.ORDER_DIRECTION_SELL
                )
                .build();

        var result = mapper.mapPostOrderResponse(
                order,
                response
        );

        assertEquals(
                TradeOrderStatus.PARTIALLY_FILLED,
                result.status()
        );

        assertEquals(4L, result.executedLots());
    }

    @Test
    void shouldAcceptPreviouslyKnownBrokerId() {

        var order = createOrder();

        order.setBrokerOrderId(BROKER_ID);

        var result = mapper.mapPostOrderResponse(
                order,
                responseBuilder().build()
        );

        assertEquals(
                BROKER_ID,
                result.brokerOrderId()
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = TradeOrderEntity.Environment.class,
            names = {"LIVE", "EMULATOR"}
    )
    void shouldRejectUnsupportedEnvironment(
            TradeOrderEntity.Environment environment
    ) {

        var order = createOrder();

        order.setEnvironment(environment);

        assertThrows(
                IllegalStateException.class,
                () -> mapper.mapPostOrderResponse(
                        order,
                        responseBuilder().build()
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "WRONG_CLIENT_ID",
            "MISSING_CLIENT_ID",
            "WRONG_FIGI",
            "WRONG_DIRECTION",
            "WRONG_REQUESTED_LOTS",
            "TOO_MANY_EXECUTED_LOTS",
            "NEGATIVE_EXECUTED_LOTS",
            "MISSING_BROKER_ID",
            "CHANGED_BROKER_ID",
            "MISSING_PRICE",
            "ZERO_PRICE",
            "NEGATIVE_PRICE",
            "MISSING_COMMISSION",
            "NEGATIVE_COMMISSION",
            "WRONG_PRICE_CURRENCY",
            "WRONG_COMMISSION_CURRENCY",
            "INVALID_NANO",
            "INCONSISTENT_SIGNS",
            "UNEXECUTED_PRICE",
            "UNEXECUTED_COMMISSION"
    })
    void shouldRejectInvalidBrokerResponse(
            String problem
    ) {

        var order = createOrder();

        var builder = responseBuilder();

        switch (problem) {

            case "WRONG_CLIENT_ID" ->
                    builder.setOrderRequestId("WRONG_ID");

            case "MISSING_CLIENT_ID" ->
                    builder.clearOrderRequestId();

            case "WRONG_FIGI" ->
                    builder.setFigi("WRONG_FIGI");

            case "WRONG_DIRECTION" ->
                    builder.setDirection(
                            OrderDirection.ORDER_DIRECTION_SELL
                    );

            case "WRONG_REQUESTED_LOTS" ->
                    builder.setLotsRequested(11L);

            case "TOO_MANY_EXECUTED_LOTS" ->
                    builder.setLotsExecuted(11L);

            case "NEGATIVE_EXECUTED_LOTS" ->
                    builder.setLotsExecuted(-1L);

            case "MISSING_BROKER_ID" ->
                    builder.clearOrderId();

            case "CHANGED_BROKER_ID" ->
                    order.setBrokerOrderId("ANOTHER_ID");

            case "MISSING_PRICE" ->
                    builder.clearExecutedOrderPrice();

            case "ZERO_PRICE" ->
                    builder.setExecutedOrderPrice(
                            money("rub", 0, 0)
                    );

            case "NEGATIVE_PRICE" ->
                    builder.setExecutedOrderPrice(
                            money("rub", -250, 0)
                    );

            case "MISSING_COMMISSION" ->
                    builder.clearExecutedCommission();

            case "NEGATIVE_COMMISSION" ->
                    builder.setExecutedCommission(
                            money("rub", -5, 0)
                    );

            case "WRONG_PRICE_CURRENCY" ->
                    builder.setExecutedOrderPrice(
                            money("usd", 250, 0)
                    );

            case "WRONG_COMMISSION_CURRENCY" ->
                    builder.setExecutedCommission(
                            money("usd", 5, 0)
                    );

            case "INVALID_NANO" ->
                    builder.setExecutedOrderPrice(
                            money("rub", 250, 1_000_000_000)
                    );

            case "INCONSISTENT_SIGNS" ->
                    builder.setExecutedOrderPrice(
                            money("rub", 250, -750_000_000)
                    );

            case "UNEXECUTED_PRICE" -> {

                builder.setExecutionReportStatus(
                        OrderExecutionReportStatus
                                .EXECUTION_REPORT_STATUS_NEW
                );

                builder.setLotsExecuted(0L);
                builder.clearExecutedCommission();
            }

            case "UNEXECUTED_COMMISSION" -> {

                builder.setExecutionReportStatus(
                        OrderExecutionReportStatus
                                .EXECUTION_REPORT_STATUS_NEW
                );

                builder.setLotsExecuted(0L);
                builder.clearExecutedOrderPrice();
            }

            default ->
                    throw new IllegalArgumentException(problem);
        }

        var response = builder.build();

        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.mapPostOrderResponse(
                        order,
                        response
                )
        );

        assertEquals(
                TradeOrderStatus.SUBMITTING,
                order.getStatus()
        );

        assertNull(order.getExecutedLots());
        assertFalse(order.isReconciled());
    }

    @Test
    void shouldRejectMissingOrderOrResponse() {

        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.mapPostOrderResponse(
                        null,
                        responseBuilder().build()
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.mapPostOrderResponse(
                        createOrder(),
                        null
                )
        );
    }
}
