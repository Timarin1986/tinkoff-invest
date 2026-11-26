
package com.struchev.invest.service.order;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;
import com.struchev.invest.repository.TradeOrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:14.1-alpine:///trade_order_lifecycle_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ActiveProfiles("test")
@Import({
        TradeOrderService.class,
        TradeOrderLifecycleService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TradeOrderLifecycleServiceTest {

    @Autowired
    private TradeOrderService registrationService;

    @Autowired
    private TradeOrderLifecycleService lifecycleService;

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

    private TradeOrderEntity createOrderWithStatus(
            TradeOrderStatus status
    ) {

        var order = createOrder();

        order.setStatus(status);

        return repository.saveAndFlush(order);
    }

    private TradeOrderEntity reload(Long orderId) {

        return repository.findById(orderId)
                .orElseThrow();
    }

    @Test
    void shouldMarkCreatedOrderAsSubmitting() {

        var order = createOrder();

        lifecycleService.markSubmitting(order.getId());

        var saved = reload(order.getId());

        assertEquals(
                TradeOrderStatus.SUBMITTING,
                saved.getStatus()
        );

        assertFalse(saved.isReconciled());
        assertNull(saved.getErrorMessage());

        assertEquals(
                order.getClientOrderId(),
                saved.getClientOrderId()
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = TradeOrderStatus.class,
            names = "CREATED",
            mode = EnumSource.Mode.EXCLUDE
    )
    void shouldRejectSubmittingFromInvalidStatus(
            TradeOrderStatus status
    ) {

        var order = createOrderWithStatus(status);

        assertThrows(
                IllegalStateException.class,
                () -> lifecycleService.markSubmitting(
                        order.getId()
                )
        );

        assertEquals(
                status,
                reload(order.getId()).getStatus()
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = TradeOrderStatus.class,
            names = {
                    "SUBMITTING",
                    "NEW",
                    "PARTIALLY_FILLED",
                    "UNKNOWN"
            }
    )
    void shouldMarkUncertainOrderAsUnknown(
            TradeOrderStatus status
    ) {

        var order = createOrderWithStatus(status);

        lifecycleService.markUnknown(order.getId());

        var saved = reload(order.getId());

        assertEquals(
                TradeOrderStatus.UNKNOWN,
                saved.getStatus()
        );

        assertFalse(saved.isReconciled());
        assertNotNull(saved.getErrorMessage());

        assertTrue(
                saved.getErrorMessage()
                        .contains("Reconciliation is required")
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = TradeOrderStatus.class,
            names = {
                    "CREATED",
                    "FILLED",
                    "CANCELLED",
                    "REJECTED"
            }
    )
    void shouldRejectInvalidUnknownTransition(
            TradeOrderStatus status
    ) {

        var order = createOrderWithStatus(status);

        assertThrows(
                IllegalStateException.class,
                () -> lifecycleService.markUnknown(
                        order.getId()
                )
        );

        assertEquals(
                status,
                reload(order.getId()).getStatus()
        );
    }

    @Test
    void shouldPreservePartialExecutionDetails() {

        var order = createOrderWithStatus(
                TradeOrderStatus.PARTIALLY_FILLED
        );

        order.setBrokerOrderId("BROKER_ORDER_123");
        order.setExecutedLots(2L);

        order.setAverageExecutedPrice(
                new BigDecimal("101.25")
        );

        order.setExecutedCommission(
                new BigDecimal("1.50")
        );

        repository.saveAndFlush(order);

        lifecycleService.markUnknown(order.getId());

        var saved = reload(order.getId());

        assertEquals(
                TradeOrderStatus.UNKNOWN,
                saved.getStatus()
        );

        assertEquals(
                "BROKER_ORDER_123",
                saved.getBrokerOrderId()
        );

        assertEquals(
                2L,
                saved.getExecutedLots().longValue()
        );

        assertEquals(
                0,
                new BigDecimal("101.25").compareTo(
                        saved.getAverageExecutedPrice()
                )
        );

        assertEquals(
                0,
                new BigDecimal("1.50").compareTo(
                        saved.getExecutedCommission()
                )
        );

        assertFalse(saved.isReconciled());
    }

    @Test
    void shouldRejectModificationOfReconciledOrder() {

        var order = createOrderWithStatus(
                TradeOrderStatus.FILLED
        );

        order.setExecutedLots(5L);
        order.setReconciled(true);

        repository.saveAndFlush(order);

        assertThrows(
                IllegalStateException.class,
                () -> lifecycleService.markSubmitting(
                        order.getId()
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> lifecycleService.markUnknown(
                        order.getId()
                )
        );

        var saved = reload(order.getId());

        assertTrue(saved.isReconciled());

        assertEquals(
                TradeOrderStatus.FILLED,
                saved.getStatus()
        );
    }

    @Test
    void shouldRejectLiveOrderModification() {

        var order = createOrder();

        order.setEnvironment(
                TradeOrderEntity.Environment.LIVE
        );

        repository.saveAndFlush(order);

        assertThrows(
                IllegalStateException.class,
                () -> lifecycleService.markSubmitting(
                        order.getId()
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> lifecycleService.markUnknown(
                        order.getId()
                )
        );

        assertEquals(
                TradeOrderStatus.CREATED,
                reload(order.getId()).getStatus()
        );
    }

    @Test
    void shouldRejectInvalidOrderId() {

        assertThrows(
                IllegalArgumentException.class,
                () -> lifecycleService.markSubmitting(null)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> lifecycleService.markSubmitting(0L)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> lifecycleService.markUnknown(-1L)
        );
    }

    @Test
    void shouldRejectMissingOrder() {

        assertThrows(
                IllegalArgumentException.class,
                () -> lifecycleService.markSubmitting(
                        Long.MAX_VALUE
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> lifecycleService.markUnknown(
                        Long.MAX_VALUE
                )
        );
    }

    @Test
    void shouldPreserveSubmittingAfterOuterRollback() {

        var order = createOrder();

        var transaction = new TransactionTemplate(
                transactionManager
        );

        transaction.executeWithoutResult(status -> {

            lifecycleService.markSubmitting(
                    order.getId()
            );

            status.setRollbackOnly();
        });

        assertEquals(
                TradeOrderStatus.SUBMITTING,
                reload(order.getId()).getStatus()
        );
    }

    @Test
    void shouldPreserveUnknownAfterOuterRollback() {

        var order = createOrder();

        lifecycleService.markSubmitting(order.getId());

        var transaction = new TransactionTemplate(
                transactionManager
        );

        transaction.executeWithoutResult(status -> {

            lifecycleService.markUnknown(
                    order.getId()
            );

            status.setRollbackOnly();
        });

        assertEquals(
                TradeOrderStatus.UNKNOWN,
                reload(order.getId()).getStatus()
        );
    }

    @Test
    void shouldRejectStaleOrderVersion() {

        var order = createOrder();

        var staleOrder = reload(order.getId());

        lifecycleService.markSubmitting(order.getId());

        staleOrder.setStatus(TradeOrderStatus.UNKNOWN);

        assertThrows(
                OptimisticLockingFailureException.class,
                () -> repository.saveAndFlush(staleOrder)
        );

        assertEquals(
                TradeOrderStatus.SUBMITTING,
                reload(order.getId()).getStatus()
        );
    }

    @Test
    void shouldAllowOnlyOneConcurrentSubmission()
            throws Exception {

        var order = createOrder();

        var executor = Executors.newFixedThreadPool(2);

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try {

            var first = executor.submit(() ->
                    attemptSubmission(
                            order.getId(),
                            ready,
                            start
                    )
            );

            var second = executor.submit(() ->
                    attemptSubmission(
                            order.getId(),
                            ready,
                            start
                    )
            );

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS)
            );

            start.countDown();

            boolean firstResult =
                    first.get(20, TimeUnit.SECONDS);

            boolean secondResult =
                    second.get(20, TimeUnit.SECONDS);

            assertNotEquals(
                    firstResult,
                    secondResult
            );

            assertEquals(
                    TradeOrderStatus.SUBMITTING,
                    reload(order.getId()).getStatus()
            );

            assertEquals(1L, repository.count());

        } finally {

            start.countDown();

            executor.shutdownNow();

            executor.awaitTermination(
                    10,
                    TimeUnit.SECONDS
            );
        }
    }

    private boolean attemptSubmission(
            Long orderId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {

        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {

            throw new IllegalStateException(
                    "Concurrent test start timeout"
            );
        }

        try {

            lifecycleService.markSubmitting(orderId);

            return true;

        } catch (OptimisticLockingFailureException exception) {

            return false;

        } catch (IllegalStateException exception) {

            assertTrue(
                    exception.getMessage().contains(
                            "Cannot submit order from status:"
                    )
            );

            return false;
        }
    }
}
