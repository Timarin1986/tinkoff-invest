
package com.struchev.invest.service.order;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;
import com.struchev.invest.repository.TradeOrderRepository;
import com.struchev.invest.service.order.TradeOrderExecutionService.BrokerResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:14.1-alpine:///trade_order_execution_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ActiveProfiles("test")
@Import({
        TradeOrderService.class,
        TradeOrderLifecycleService.class,
        TradeOrderExecutionService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TradeOrderExecutionServiceTest {

    private static final BigDecimal PRICE =
            new BigDecimal("101.25");

    private static final BigDecimal COMMISSION =
            new BigDecimal("1.50");

    @Autowired
    private TradeOrderService registrationService;

    @Autowired
    private TradeOrderLifecycleService lifecycleService;

    @Autowired
    private TradeOrderExecutionService executionService;

    @Autowired
    private TradeOrderRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    private TradeOrderEntity createOrder() {

        return registrationService.registerOrder(
                TradeOrderEntity.Environment.SANDBOX,
                "TEST_ACCOUNT",
                "TEST_FIGI",
                "TEST_STRATEGY",
                TradeOrderEntity.Direction.BUY,
                5L,
                10,
                new BigDecimal("100.50"),
                "rub",
                null
        );
    }

    private TradeOrderEntity createSubmittingOrder() {

        var order = createOrder();

        return lifecycleService.markSubmitting(
                order.getId()
        );
    }

    private TradeOrderEntity reload(Long orderId) {

        return repository.findById(orderId)
                .orElseThrow();
    }

    private BrokerResult result(
            TradeOrderEntity order,
            TradeOrderStatus status,
            long executedLots
    ) {

        return new BrokerResult(
                order.getClientOrderId(),
                "BROKER_ORDER_123",
                status,
                5L,
                executedLots,
                executedLots > 0 ? PRICE : null,
                executedLots > 0
                        ? COMMISSION
                        : BigDecimal.ZERO,
                "rub"
        );
    }

    @ParameterizedTest
    @CsvSource({
            "NEW, 0",
            "PARTIALLY_FILLED, 2",
            "FILLED, 5",
            "CANCELLED, 0",
            "CANCELLED, 2",
            "REJECTED, 0"
    })
    void shouldApplyValidBrokerResult(
            TradeOrderStatus status,
            long executedLots
    ) {

        var order = createSubmittingOrder();

        executionService.applyBrokerResult(
                order.getId(),
                result(order, status, executedLots)
        );

        var saved = reload(order.getId());

        assertEquals(status, saved.getStatus());

        assertEquals(
                executedLots,
                saved.getExecutedLots().longValue()
        );

        assertEquals(5L, saved.getRequestedLots());

        assertEquals(
                "BROKER_ORDER_123",
                saved.getBrokerOrderId()
        );

        assertFalse(saved.isReconciled());
        assertNull(saved.getErrorMessage());

        if (executedLots > 0) {

            assertEquals(
                    0,
                    PRICE.compareTo(
                            saved.getAverageExecutedPrice()
                    )
            );

            assertEquals(
                    0,
                    COMMISSION.compareTo(
                            saved.getExecutedCommission()
                    )
            );

        } else {

            assertNull(saved.getAverageExecutedPrice());

            assertEquals(
                    0,
                    saved.getExecutedCommission().signum()
            );
        }
    }

    @ParameterizedTest
    @CsvSource({
            "NEW, 1",
            "NEW, -1",
            "PARTIALLY_FILLED, 0",
            "PARTIALLY_FILLED, 5",
            "FILLED, 4",
            "FILLED, 6",
            "CANCELLED, 5",
            "REJECTED, 1",
            "CREATED, 0",
            "SUBMITTING, 0",
            "UNKNOWN, 0"
    })
    void shouldRejectInvalidExecutionStatus(
            TradeOrderStatus status,
            long executedLots
    ) {

        var order = createSubmittingOrder();

        assertThrows(
                IllegalArgumentException.class,
                () -> executionService.applyBrokerResult(
                        order.getId(),
                        result(order, status, executedLots)
                )
        );

        assertUnchanged(order.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CLIENT_ID",
            "BROKER_ID",
            "REQUESTED_LOTS",
            "CURRENCY",
            "MISSING_PRICE",
            "NEGATIVE_PRICE",
            "MISSING_COMMISSION",
            "NEGATIVE_COMMISSION",
            "UNEXECUTED_PRICE",
            "UNEXECUTED_COMMISSION"
    })
    void shouldRejectInvalidBrokerData(String problem) {

        var order = createSubmittingOrder();

        String clientId = order.getClientOrderId();
        String brokerId = "BROKER_ORDER_123";
        String currency = "rub";

        long requestedLots = 5L;
        long executedLots = 2L;

        var status = TradeOrderStatus.PARTIALLY_FILLED;

        BigDecimal price = PRICE;
        BigDecimal commission = COMMISSION;

        switch (problem) {

            case "CLIENT_ID" ->
                    clientId = "WRONG_CLIENT_ID";

            case "BROKER_ID" ->
                    brokerId = null;

            case "REQUESTED_LOTS" ->
                    requestedLots = 6L;

            case "CURRENCY" ->
                    currency = "usd";

            case "MISSING_PRICE" ->
                    price = null;

            case "NEGATIVE_PRICE" ->
                    price = new BigDecimal("-100");

            case "MISSING_COMMISSION" ->
                    commission = null;

            case "NEGATIVE_COMMISSION" ->
                    commission = new BigDecimal("-1");

            case "UNEXECUTED_PRICE" -> {

                status = TradeOrderStatus.NEW;
                executedLots = 0L;
                commission = BigDecimal.ZERO;
            }

            case "UNEXECUTED_COMMISSION" -> {

                status = TradeOrderStatus.NEW;
                executedLots = 0L;
                price = null;
            }

            default ->
                    throw new IllegalArgumentException(problem);
        }

        var invalidResult = new BrokerResult(
                clientId,
                brokerId,
                status,
                requestedLots,
                executedLots,
                price,
                commission,
                currency
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> executionService.applyBrokerResult(
                        order.getId(),
                        invalidResult
                )
        );

        assertUnchanged(order.getId());
    }

    private void assertUnchanged(Long orderId) {

        var saved = reload(orderId);

        assertEquals(
                TradeOrderStatus.SUBMITTING,
                saved.getStatus()
        );

        assertNull(saved.getExecutedLots());
        assertNull(saved.getBrokerOrderId());
        assertNull(saved.getAverageExecutedPrice());
        assertNull(saved.getExecutedCommission());
        assertFalse(saved.isReconciled());
    }

    @Test
    void shouldAllowRejectedOrderWithoutBrokerId() {

        var order = createSubmittingOrder();

        var rejected = new BrokerResult(
                order.getClientOrderId(),
                null,
                TradeOrderStatus.REJECTED,
                5L,
                0L,
                null,
                null,
                "rub"
        );

        executionService.applyBrokerResult(
                order.getId(),
                rejected
        );

        var saved = reload(order.getId());

        assertEquals(
                TradeOrderStatus.REJECTED,
                saved.getStatus()
        );

        assertNull(saved.getBrokerOrderId());
        assertFalse(saved.isReconciled());
    }

    @Test
    void shouldAllowZeroExecutionCommission() {

        var order = createSubmittingOrder();

        var filled = new BrokerResult(
                order.getClientOrderId(),
                "BROKER_ORDER_123",
                TradeOrderStatus.FILLED,
                5L,
                5L,
                PRICE,
                BigDecimal.ZERO,
                "rub"
        );

        executionService.applyBrokerResult(
                order.getId(),
                filled
        );

        var saved = reload(order.getId());

        assertEquals(
                TradeOrderStatus.FILLED,
                saved.getStatus()
        );

        assertEquals(
                0,
                saved.getExecutedCommission().signum()
        );
    }

    @Test
    void shouldRejectChangedBrokerOrderId() {

        var order = createSubmittingOrder();

        executionService.applyBrokerResult(
                order.getId(),
                result(order, TradeOrderStatus.NEW, 0L)
        );

        var wrongResult = new BrokerResult(
                order.getClientOrderId(),
                "ANOTHER_BROKER_ORDER",
                TradeOrderStatus.FILLED,
                5L,
                5L,
                PRICE,
                COMMISSION,
                "rub"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> executionService.applyBrokerResult(
                        order.getId(),
                        wrongResult
                )
        );

        var saved = reload(order.getId());

        assertEquals(
                "BROKER_ORDER_123",
                saved.getBrokerOrderId()
        );

        assertEquals(
                TradeOrderStatus.NEW,
                saved.getStatus()
        );
    }

    @Test
    void shouldRejectDecreasingExecutedLots() {

        var order = createSubmittingOrder();

        executionService.applyBrokerResult(
                order.getId(),
                result(
                        order,
                        TradeOrderStatus.PARTIALLY_FILLED,
                        3L
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> executionService.applyBrokerResult(
                        order.getId(),
                        result(
                                order,
                                TradeOrderStatus.PARTIALLY_FILLED,
                                2L
                        )
                )
        );

        var saved = reload(order.getId());

        assertEquals(
                3L,
                saved.getExecutedLots().longValue()
        );

        assertEquals(
                TradeOrderStatus.PARTIALLY_FILLED,
                saved.getStatus()
        );
    }

    @Test
    void shouldRecoverExecutionAfterUnknownStatus() {

        var order = createSubmittingOrder();

        executionService.applyBrokerResult(
                order.getId(),
                result(
                        order,
                        TradeOrderStatus.PARTIALLY_FILLED,
                        2L
                )
        );

        lifecycleService.markUnknown(order.getId());

        assertEquals(
                TradeOrderStatus.UNKNOWN,
                reload(order.getId()).getStatus()
        );

        executionService.applyBrokerResult(
                order.getId(),
                result(
                        order,
                        TradeOrderStatus.FILLED,
                        5L
                )
        );

        var saved = reload(order.getId());

        assertEquals(
                TradeOrderStatus.FILLED,
                saved.getStatus()
        );

        assertEquals(
                5L,
                saved.getExecutedLots().longValue()
        );

        assertNull(saved.getErrorMessage());
        assertFalse(saved.isReconciled());
    }

    @ParameterizedTest
    @EnumSource(
            value = TradeOrderStatus.class,
            names = {
                    "FILLED",
                    "CANCELLED",
                    "REJECTED"
            }
    )
    void shouldRejectModificationOfTerminalStatus(
            TradeOrderStatus status
    ) {

        var order = createSubmittingOrder();

        long executedLots =
                status == TradeOrderStatus.FILLED
                        ? 5L
                        : 0L;

        executionService.applyBrokerResult(
                order.getId(),
                result(order, status, executedLots)
        );

        assertThrows(
                IllegalStateException.class,
                () -> executionService.applyBrokerResult(
                        order.getId(),
                        result(
                                order,
                                TradeOrderStatus.NEW,
                                0L
                        )
                )
        );

        assertEquals(
                status,
                reload(order.getId()).getStatus()
        );
    }

    @Test
    void shouldRejectResultBeforeSubmission() {

        var order = createOrder();

        assertThrows(
                IllegalStateException.class,
                () -> executionService.applyBrokerResult(
                        order.getId(),
                        result(
                                order,
                                TradeOrderStatus.FILLED,
                                5L
                        )
                )
        );

        assertEquals(
                TradeOrderStatus.CREATED,
                reload(order.getId()).getStatus()
        );
    }

    @Test
    void shouldPreserveExecutionAfterOuterRollback() {

        var order = createSubmittingOrder();

        var transaction = new TransactionTemplate(
                transactionManager
        );

        transaction.executeWithoutResult(status -> {

            executionService.applyBrokerResult(
                    order.getId(),
                    result(
                            order,
                            TradeOrderStatus.FILLED,
                            5L
                    )
            );

            status.setRollbackOnly();
        });

        var saved = reload(order.getId());

        assertEquals(
                TradeOrderStatus.FILLED,
                saved.getStatus()
        );

        assertEquals(
                5L,
                saved.getExecutedLots().longValue()
        );

        assertFalse(saved.isReconciled());
    }

    @Test
    void shouldRejectModificationOfReconciledOrder() {

        var order = createSubmittingOrder();

        executionService.applyBrokerResult(
                order.getId(),
                result(
                        order,
                        TradeOrderStatus.FILLED,
                        5L
                )
        );

        var saved = reload(order.getId());

        saved.setReconciled(true);

        repository.saveAndFlush(saved);

        assertThrows(
                IllegalStateException.class,
                () -> executionService.applyBrokerResult(
                        order.getId(),
                        result(
                                order,
                                TradeOrderStatus.FILLED,
                                5L
                        )
                )
        );

        assertTrue(
                reload(order.getId()).isReconciled()
        );
    }
}
