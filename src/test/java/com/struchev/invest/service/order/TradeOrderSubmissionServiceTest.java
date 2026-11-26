
package com.struchev.invest.service.order;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;
import com.struchev.invest.repository.TradeOrderRepository;
import com.struchev.invest.service.tinkoff.ITinkoffSandboxOrderAPI;
import com.struchev.invest.service.tinkoff.TinkoffOrderResponseMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderExecutionReportStatus;
import ru.tinkoff.piapi.contract.v1.PostOrderResponse;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:14.1-alpine:///trade_order_submission_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "tinkoff.emulator=false",
        "tinkoff.is-token-sandbox=true"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ActiveProfiles("test")
@Import({
        TradeOrderSubmissionService.class,
        TradeOrderService.class,
        TradeOrderLifecycleService.class,
        TradeOrderExecutionService.class,
        TinkoffOrderResponseMapper.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TradeOrderSubmissionServiceTest {

    private static final String ACCOUNT_ID = "TEST_ACCOUNT";

    private static final String FIGI = "TEST_FIGI";

    private static final String STRATEGY = "TEST_STRATEGY";

    @Autowired
    private TradeOrderSubmissionService submissionService;

    @Autowired
    private TradeOrderRepository repository;

    @MockBean
    private ITinkoffSandboxOrderAPI sandboxAPI;

    @BeforeEach
    void setUp() {

        repository.deleteAll();

        reset(sandboxAPI);
    }

    private TradeOrderEntity submit(
            TradeOrderEntity.Direction direction
    ) {

        return submissionService.registerAndSubmit(
                ACCOUNT_ID,
                FIGI,
                STRATEGY,
                direction,
                5L,
                10,
                new BigDecimal("100.50"),
                "rub",
                direction == TradeOrderEntity.Direction.SELL
                        ? 100L
                        : null
        );
    }

    private TradeOrderEntity submitBuy() {

        return submit(
                TradeOrderEntity.Direction.BUY
        );
    }

    private TradeOrderEntity getOnlyOrder() {

        var orders = repository.findAll();

        assertEquals(1, orders.size());

        return orders.getFirst();
    }

    private PostOrderResponse createResponse(
            TradeOrderEntity order,
            OrderExecutionReportStatus status,
            long executedLots
    ) {

        var direction = switch (order.getDirection()) {

            case BUY ->
                    OrderDirection.ORDER_DIRECTION_BUY;

            case SELL ->
                    OrderDirection.ORDER_DIRECTION_SELL;
        };

        var response = PostOrderResponse.newBuilder()
                .setOrderId("BROKER_ORDER_ID")
                .setOrderRequestId(order.getClientOrderId())
                .setFigi(order.getFigi())
                .setDirection(direction)
                .setExecutionReportStatus(status)
                .setLotsRequested(order.getRequestedLots())
                .setLotsExecuted(executedLots);

        if (executedLots > 0) {

            response.setExecutedOrderPrice(
                    MoneyValue.newBuilder()
                            .setCurrency("rub")
                            .setUnits(100L)
                            .setNano(500_000_000)
                            .build()
            );

            response.setExecutedCommission(
                    MoneyValue.newBuilder()
                            .setCurrency("rub")
                            .setUnits(2L)
                            .setNano(500_000_000)
                            .build()
            );
        }

        return response.build();
    }

    private void mockSuccessfulExecution() {

        when(sandboxAPI.submitSandboxOrder(any()))
                .thenAnswer(invocation -> {

                    TradeOrderEntity order =
                            invocation.getArgument(0);

                    return createResponse(
                            order,
                            OrderExecutionReportStatus
                                    .EXECUTION_REPORT_STATUS_FILL,
                            order.getRequestedLots()
                    );
                });
    }

    @ParameterizedTest
    @EnumSource(TradeOrderEntity.Direction.class)
    void shouldPersistOrderBeforeBrokerCall(
            TradeOrderEntity.Direction direction
    ) {

        when(sandboxAPI.submitSandboxOrder(any()))
                .thenAnswer(invocation -> {

                    TradeOrderEntity order =
                            invocation.getArgument(0);

                    var persisted = repository
                            .findById(order.getId())
                            .orElseThrow();

                    assertEquals(
                            TradeOrderStatus.SUBMITTING,
                            persisted.getStatus()
                    );

                    assertEquals(
                            order.getClientOrderId(),
                            persisted.getClientOrderId()
                    );

                    assertEquals(
                            TradeOrderEntity.Environment.SANDBOX,
                            persisted.getEnvironment()
                    );

                    assertEquals(
                            direction,
                            persisted.getDirection()
                    );

                    assertEquals(
                            5L,
                            persisted.getRequestedLots()
                    );

                    assertFalse(persisted.isReconciled());

                    return createResponse(
                            order,
                            OrderExecutionReportStatus
                                    .EXECUTION_REPORT_STATUS_FILL,
                            5L
                    );
                });

        var result = submit(direction);

        assertEquals(
                TradeOrderStatus.FILLED,
                result.getStatus()
        );

        assertEquals(
                5L,
                result.getExecutedLots().longValue()
        );

        assertFalse(result.isReconciled());

        var persisted = getOnlyOrder();

        assertEquals(
                TradeOrderStatus.FILLED,
                persisted.getStatus()
        );

        assertEquals(
                "BROKER_ORDER_ID",
                persisted.getBrokerOrderId()
        );

        assertEquals(
                0,
                new BigDecimal("100.50").compareTo(
                        persisted.getAverageExecutedPrice()
                )
        );

        assertEquals(
                0,
                new BigDecimal("2.50").compareTo(
                        persisted.getExecutedCommission()
                )
        );

        verify(sandboxAPI, times(1))
                .submitSandboxOrder(any());
    }

    @Test
    void shouldSavePartialExecution() {

        when(sandboxAPI.submitSandboxOrder(any()))
                .thenAnswer(invocation -> {

                    TradeOrderEntity order =
                            invocation.getArgument(0);

                    return createResponse(
                            order,
                            OrderExecutionReportStatus
                                    .EXECUTION_REPORT_STATUS_PARTIALLYFILL,
                            2L
                    );
                });

        var result = submitBuy();

        assertEquals(
                TradeOrderStatus.PARTIALLY_FILLED,
                result.getStatus()
        );

        var persisted = getOnlyOrder();

        assertEquals(
                2L,
                persisted.getExecutedLots().longValue()
        );

        assertEquals(
                5L,
                persisted.getRequestedLots()
        );

        assertFalse(persisted.isReconciled());
    }

    @Test
    void shouldSaveRejectedOrder() {

        when(sandboxAPI.submitSandboxOrder(any()))
                .thenAnswer(invocation -> {

                    TradeOrderEntity order =
                            invocation.getArgument(0);

                    return createResponse(
                            order,
                            OrderExecutionReportStatus
                                    .EXECUTION_REPORT_STATUS_REJECTED,
                            0L
                    );
                });

        var result = submitBuy();

        assertEquals(
                TradeOrderStatus.REJECTED,
                result.getStatus()
        );

        var persisted = getOnlyOrder();

        assertEquals(
                0L,
                persisted.getExecutedLots().longValue()
        );

        assertNull(
                persisted.getAverageExecutedPrice()
        );

        assertFalse(persisted.isReconciled());
    }

    @Test
    void shouldMarkUnknownWhenBrokerFails() {

        when(sandboxAPI.submitSandboxOrder(any()))
                .thenThrow(
                        new RuntimeException("Connection lost")
                );

        assertThrows(
                IllegalStateException.class,
                this::submitBuy
        );

        var persisted = getOnlyOrder();

        assertEquals(
                TradeOrderStatus.UNKNOWN,
                persisted.getStatus()
        );

        assertNotNull(
                persisted.getErrorMessage()
        );

        assertFalse(persisted.isReconciled());

        verify(sandboxAPI, times(1))
                .submitSandboxOrder(any());
    }

    @Test
    void shouldMarkUnknownWhenBrokerResponseIsInvalid() {

        when(sandboxAPI.submitSandboxOrder(any()))
                .thenReturn(
                        PostOrderResponse.newBuilder()
                                .setOrderRequestId("WRONG_ID")
                                .build()
                );

        assertThrows(
                IllegalStateException.class,
                this::submitBuy
        );

        assertEquals(
                TradeOrderStatus.UNKNOWN,
                getOnlyOrder().getStatus()
        );

        verify(sandboxAPI, times(1))
                .submitSandboxOrder(any());
    }

    @Test
    void shouldBlockDuplicateOrderBeforeBrokerCall() {

        mockSuccessfulExecution();

        submitBuy();

        assertThrows(
                DataIntegrityViolationException.class,
                this::submitBuy
        );

        assertEquals(1L, repository.count());

        verify(sandboxAPI, times(1))
                .submitSandboxOrder(any());
    }

    @Test
    void shouldNotSendAgainAfterConnectionLost() {

        when(sandboxAPI.submitSandboxOrder(any()))
                .thenThrow(
                        new RuntimeException("Connection lost")
                );

        assertThrows(
                IllegalStateException.class,
                this::submitBuy
        );

        assertThrows(
                DataIntegrityViolationException.class,
                this::submitBuy
        );

        assertEquals(
                TradeOrderStatus.UNKNOWN,
                getOnlyOrder().getStatus()
        );

        verify(sandboxAPI, times(1))
                .submitSandboxOrder(any());
    }

    @Test
    void shouldNotSubmitWhenSandboxValidationFails() {

        doThrow(
                new IllegalStateException(
                        "Sandbox account mismatch"
                )
        ).when(sandboxAPI)
                .validateSandboxOrder(any());

        assertThrows(
                IllegalStateException.class,
                this::submitBuy
        );

        assertEquals(
                TradeOrderStatus.CREATED,
                getOnlyOrder().getStatus()
        );

        verify(sandboxAPI, never())
                .submitSandboxOrder(any());
    }

    @Test
    void shouldNotCallBrokerWhenRegistrationFails() {

        assertThrows(
                IllegalArgumentException.class,
                () -> submissionService.registerAndSubmit(
                        ACCOUNT_ID,
                        FIGI,
                        STRATEGY,
                        TradeOrderEntity.Direction.BUY,
                        0L,
                        10,
                        new BigDecimal("100.50"),
                        "rub",
                        null
                )
        );

        assertEquals(0L, repository.count());

        verifyNoInteractions(sandboxAPI);
    }
}
