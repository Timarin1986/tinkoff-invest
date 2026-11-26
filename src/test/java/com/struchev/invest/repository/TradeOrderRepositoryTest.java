
package com.struchev.invest.repository;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:14.1-alpine:///trade_order_test",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@ActiveProfiles("test")
class TradeOrderRepositoryTest {

    @Autowired
    private TradeOrderRepository repository;

    @Autowired
    private EntityManager entityManager;

    private TradeOrderEntity createOrder() {
        return TradeOrderEntity.builder()
            .clientOrderId(UUID.randomUUID().toString())
            .accountId("TEST_ACCOUNT")
            .environment(TradeOrderEntity.Environment.SANDBOX)
            .figi("TEST_FIGI")
            .strategy("TEST_STRATEGY")
            .direction(TradeOrderEntity.Direction.BUY)
            .requestedLots(5L)
            .lotSize(10)
            .requestedPrice(new BigDecimal("100.50"))
            .currency("rub")
            .build();
    }

    @Test
    void shouldSaveAndFindOrder() {
        var order = createOrder();

        repository.saveAndFlush(order);
        entityManager.clear();

        var saved = repository
            .findByClientOrderId(order.getClientOrderId())
            .orElseThrow();

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertEquals(TradeOrderStatus.CREATED, saved.getStatus());
        assertEquals(5L, saved.getRequestedLots());
        assertEquals(10, saved.getLotSize());
        assertNull(saved.getExecutedLots());
        assertFalse(saved.isReconciled());
    }

    @Test
    void shouldRejectDuplicateClientOrderId() {
        var first = createOrder();
        repository.saveAndFlush(first);

        var duplicate = createOrder();
        duplicate.setClientOrderId(first.getClientOrderId());

        assertThrows(
            DataIntegrityViolationException.class,
            () -> repository.saveAndFlush(duplicate)
        );
    }

    @Test
    void shouldSavePartialExecution() {
        var order = createOrder();

        repository.saveAndFlush(order);

        order.setStatus(TradeOrderStatus.PARTIALLY_FILLED);
        order.setExecutedLots(2L);
        order.setBrokerOrderId("BROKER_ORDER_123");
        order.setAverageExecutedPrice(new BigDecimal("101.25"));
        order.setExecutedCommission(new BigDecimal("1.50"));

        repository.saveAndFlush(order);
        entityManager.clear();

        var saved = repository.findById(order.getId())
            .orElseThrow();

        assertEquals(
            TradeOrderStatus.PARTIALLY_FILLED,
            saved.getStatus()
        );

        assertEquals(2L, saved.getExecutedLots().longValue());
        assertEquals(5L, saved.getRequestedLots());

        assertEquals(
            0,
            new BigDecimal("101.25")
                .compareTo(saved.getAverageExecutedPrice())
        );

        assertEquals(
            0,
            new BigDecimal("1.50")
                .compareTo(saved.getExecutedCommission())
        );

        assertFalse(saved.isReconciled());
    }

    @Test
    void shouldFindUnreconciledOrders() {
        var order = createOrder();
        order.setStatus(TradeOrderStatus.UNKNOWN);

        repository.saveAndFlush(order);

        var result = repository
            .findByEnvironmentAndAccountIdAndReconciledFalse(
                TradeOrderEntity.Environment.SANDBOX,
                "TEST_ACCOUNT"
            );

        assertEquals(1, result.size());
        assertEquals(order.getId(), result.getFirst().getId());
    }

    @Test
    void shouldFindOrdersByStatus() {
        var order = createOrder();
        order.setStatus(TradeOrderStatus.NEW);

        repository.saveAndFlush(order);

        var result = repository
            .findByEnvironmentAndAccountIdAndStatusIn(
                TradeOrderEntity.Environment.SANDBOX,
                "TEST_ACCOUNT",
                List.of(
                    TradeOrderStatus.NEW,
                    TradeOrderStatus.PARTIALLY_FILLED
                )
            );

        assertEquals(1, result.size());
        assertEquals(order.getId(), result.getFirst().getId());
    }

    @Test
    void shouldDetectUnreconciledOrder() {
        repository.saveAndFlush(createOrder());

        assertTrue(
            repository
                .existsByEnvironmentAndAccountIdAndFigiAndStrategyAndReconciledFalse(
                    TradeOrderEntity.Environment.SANDBOX,
                    "TEST_ACCOUNT",
                    "TEST_FIGI",
                    "TEST_STRATEGY"
                )
        );
    }

    @Test
    void shouldNotBlockAfterReconciliation() {
        var order = createOrder();

        order.setStatus(TradeOrderStatus.FILLED);
        order.setExecutedLots(5L);
        order.setReconciled(true);

        repository.saveAndFlush(order);

        assertFalse(
            repository
                .existsByEnvironmentAndAccountIdAndFigiAndStrategyAndReconciledFalse(
                    TradeOrderEntity.Environment.SANDBOX,
                    "TEST_ACCOUNT",
                    "TEST_FIGI",
                    "TEST_STRATEGY"
                )
        );
    }

    @Test
    void shouldSeparateTradingEnvironments() {
        repository.saveAndFlush(createOrder());

        var result = repository
            .findByEnvironmentAndAccountIdAndReconciledFalse(
                TradeOrderEntity.Environment.LIVE,
                "TEST_ACCOUNT"
            );

        assertTrue(result.isEmpty());
    }
}
