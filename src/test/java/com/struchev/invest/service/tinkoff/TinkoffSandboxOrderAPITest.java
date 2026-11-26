
package com.struchev.invest.service.tinkoff;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderType;
import ru.tinkoff.piapi.contract.v1.PostOrderResponse;
import ru.tinkoff.piapi.contract.v1.Quotation;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TinkoffSandboxOrderAPITest {

    private static final String ACCOUNT_ID =
            "TEST_ACCOUNT";

    private static final String FIGI =
            "TEST_FIGI";

    private static final String CLIENT_ORDER_ID =
            "12345678-1234-1234-1234-123456789012";

    private InvestApi api;

    private TinkoffGRPCAPI gateway;

    private TradeOrderEntity order;

    @BeforeEach
    void setUp() {

        api = mock(
                InvestApi.class,
                RETURNS_DEEP_STUBS
        );

        gateway = spy(new TinkoffGRPCAPI());

        doReturn(api)
                .when(gateway)
                .getApi();

        doReturn(true)
                .when(gateway)
                .getIsSandboxMode();

        doReturn(ACCOUNT_ID)
                .when(gateway)
                .getAccountId();

        order = TradeOrderEntity.builder()
                .id(1L)
                .clientOrderId(CLIENT_ORDER_ID)
                .environment(
                        TradeOrderEntity.Environment.SANDBOX
                )
                .accountId(ACCOUNT_ID)
                .figi(FIGI)
                .strategy("TEST_STRATEGY")
                .direction(
                        TradeOrderEntity.Direction.BUY
                )
                .status(TradeOrderStatus.SUBMITTING)
                .requestedLots(5L)
                .lotSize(10)
                .requestedPrice(
                        new BigDecimal("100.50")
                )
                .currency("rub")
                .reconciled(false)
                .build();
    }

    @ParameterizedTest
    @EnumSource(TradeOrderEntity.Direction.class)
    void shouldSubmitUsingStoredClientOrderId(
            TradeOrderEntity.Direction direction
    ) {

        order.setDirection(direction);

        if (direction == TradeOrderEntity.Direction.SELL) {
            order.setPositionId(100L);
        }

        var brokerDirection = switch (direction) {

            case BUY ->
                    OrderDirection.ORDER_DIRECTION_BUY;

            case SELL ->
                    OrderDirection.ORDER_DIRECTION_SELL;
        };

        var expectedQuotation = Quotation.newBuilder()
                .setUnits(100L)
                .setNano(500_000_000)
                .build();

        var expectedResponse =
                PostOrderResponse.newBuilder()
                        .setOrderId("BROKER_ORDER_ID")
                        .setOrderRequestId(CLIENT_ORDER_ID)
                        .build();

        var sandbox = api.getSandboxService();

        when(sandbox.postOrderSync(
                FIGI,
                5L,
                expectedQuotation,
                brokerDirection,
                ACCOUNT_ID,
                OrderType.ORDER_TYPE_MARKET,
                CLIENT_ORDER_ID
        )).thenReturn(expectedResponse);

        var actualResponse =
                gateway.submitSandboxOrder(order);

        assertSame(
                expectedResponse,
                actualResponse
        );

        verify(sandbox, times(1))
                .postOrderSync(
                        FIGI,
                        5L,
                        expectedQuotation,
                        brokerDirection,
                        ACCOUNT_ID,
                        OrderType.ORDER_TYPE_MARKET,
                        CLIENT_ORDER_ID
                );

        verify(api, never())
                .getOrdersService();
    }

    @ParameterizedTest
    @EnumSource(
            value = TradeOrderEntity.Environment.class,
            names = {"LIVE", "EMULATOR"}
    )
    void shouldRejectNonSandboxEnvironment(
            TradeOrderEntity.Environment environment
    ) {

        order.setEnvironment(environment);

        assertThrows(
                IllegalStateException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectLiveApiConfiguration() {

        doReturn(false)
                .when(gateway)
                .getIsSandboxMode();

        assertThrows(
                IllegalStateException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectUninitializedApi() {

        doReturn(null)
                .when(gateway)
                .getApi();

        assertThrows(
                IllegalStateException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectWrongAccount() {

        order.setAccountId("ANOTHER_ACCOUNT");

        assertThrows(
                IllegalStateException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectMissingClientOrderId() {

        order.setClientOrderId(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectEmptyClientOrderId() {

        order.setClientOrderId(" ");

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectMissingFigi() {

        order.setFigi(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectMissingDirection() {

        order.setDirection(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectZeroLots() {

        order.setRequestedLots(0L);

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectNegativeLots() {

        order.setRequestedLots(-1L);

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectMissingPrice() {

        order.setRequestedPrice(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectZeroPrice() {

        order.setRequestedPrice(BigDecimal.ZERO);

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectNegativePrice() {

        order.setRequestedPrice(
                new BigDecimal("-100")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @ParameterizedTest
    @EnumSource(
            value = TradeOrderStatus.class,
            names = {"SUBMITTING"},
            mode = EnumSource.Mode.EXCLUDE
    )
    void shouldRejectInvalidOrderStatus(
            TradeOrderStatus status
    ) {

        order.setStatus(status);

        assertThrows(
                IllegalStateException.class,
                () -> gateway.submitSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldRejectNullOrder() {

        assertThrows(
                IllegalArgumentException.class,
                () -> gateway.submitSandboxOrder(null)
        );

        verifyNoInteractions(api);
    }

    @Test
    void shouldValidateCreatedOrderBeforeSubmission() {

        order.setStatus(TradeOrderStatus.CREATED);

        assertDoesNotThrow(
                () -> gateway.validateSandboxOrder(order)
        );

        verifyNoInteractions(api);
    }
}
