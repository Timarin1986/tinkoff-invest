
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:14.1-alpine:///trade_order_protection_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ActiveProfiles("test")
@Import(TradeOrderService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TradeOrderProtectionTest {

    private static final String INDEX_NAME =
            "uk_trade_order_unreconciled_instrument";

    @Autowired
    private TradeOrderService service;

    @Autowired
    private TradeOrderRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    private TradeOrderEntity registerBuy(
            TradeOrderEntity.Environment environment,
            String accountId,
            String figi,
            String strategy
    ) {

        return service.registerOrder(
                environment,
                accountId,
                figi,
                strategy,
                TradeOrderEntity.Direction.BUY,
                5L,
                10,
                new BigDecimal("100.50"),
                "rub",
                null
        );
    }

    private TradeOrderEntity registerBuy() {

        return registerBuy(
                TradeOrderEntity.Environment.SANDBOX,
                "TEST_ACCOUNT",
                "TEST_FIGI",
                "TEST_STRATEGY"
        );
    }

    private void assertProtectionViolation(
            DataIntegrityViolationException exception
    ) {

        var cause = exception.getMostSpecificCause();

        assertInstanceOf(SQLException.class, cause);

        var sqlException = (SQLException) cause;

        assertEquals(
                "23505",
                sqlException.getSQLState()
        );

        assertTrue(
                sqlException.getMessage()
                        .contains(INDEX_NAME)
        );
    }

    @Test
    void shouldCreateProtectionIndex() {

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'trade_order'
                  AND indexname = ?
                """,
                Integer.class,
                INDEX_NAME
        );

        assertEquals(1, count.intValue());
    }

    @ParameterizedTest
    @EnumSource(TradeOrderStatus.class)
    void shouldBlockEveryUnreconciledStatus(
            TradeOrderStatus status
    ) {

        var first = registerBuy();

        first.setStatus(status);

        repository.saveAndFlush(first);

        var exception = assertThrows(
                DataIntegrityViolationException.class,
                this::registerBuy
        );

        assertProtectionViolation(exception);

        assertEquals(1L, repository.count());
    }

    @Test
    void shouldBlockConflictingSellFromAnotherStrategy() {

        registerBuy();

        var exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.registerOrder(
                        TradeOrderEntity.Environment.SANDBOX,
                        "TEST_ACCOUNT",
                        "TEST_FIGI",
                        "ANOTHER_STRATEGY",
                        TradeOrderEntity.Direction.SELL,
                        5L,
                        10,
                        new BigDecimal("105.00"),
                        "rub",
                        123L
                )
        );

        assertProtectionViolation(exception);

        assertEquals(1L, repository.count());
    }

    @Test
    void shouldAllowDifferentInstruments() {

        registerBuy();

        registerBuy(
                TradeOrderEntity.Environment.SANDBOX,
                "TEST_ACCOUNT",
                "ANOTHER_FIGI",
                "TEST_STRATEGY"
        );

        assertEquals(2L, repository.count());
    }

    @Test
    void shouldAllowDifferentAccounts() {

        registerBuy();

        registerBuy(
                TradeOrderEntity.Environment.SANDBOX,
                "ANOTHER_ACCOUNT",
                "TEST_FIGI",
                "TEST_STRATEGY"
        );

        assertEquals(2L, repository.count());
    }

    @Test
    void shouldSeparateTradingEnvironments() {

        registerBuy();

        registerBuy(
                TradeOrderEntity.Environment.EMULATOR,
                "TEST_ACCOUNT",
                "TEST_FIGI",
                "TEST_STRATEGY"
        );

        assertEquals(2L, repository.count());
    }

    @Test
    void shouldAllowNewOrderAfterReconciliation() {

        var first = registerBuy();

        first.setStatus(TradeOrderStatus.FILLED);
        first.setExecutedLots(5L);
        first.setAverageExecutedPrice(
                new BigDecimal("100.50")
        );
        first.setExecutedCommission(
                new BigDecimal("2.50")
        );
        first.setReconciled(true);

        repository.saveAndFlush(first);

        var second = registerBuy();

        assertNotEquals(
                first.getClientOrderId(),
                second.getClientOrderId()
        );

        assertEquals(2L, repository.count());
    }

    @Test
    void shouldAllowOnlyOneConcurrentOrder()
            throws Exception {

        var executor = Executors.newFixedThreadPool(2);

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try {

            var first = executor.submit(() ->
                    attemptConcurrentOrder(
                            ready,
                            start,
                            "STRATEGY_ONE"
                    )
            );

            var second = executor.submit(() ->
                    attemptConcurrentOrder(
                            ready,
                            start,
                            "STRATEGY_TWO"
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

    private boolean attemptConcurrentOrder(
            CountDownLatch ready,
            CountDownLatch start,
            String strategy
    ) throws InterruptedException {

        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {

            throw new IllegalStateException(
                    "Concurrent test start timeout"
            );
        }

        try {

            registerBuy(
                    TradeOrderEntity.Environment.SANDBOX,
                    "TEST_ACCOUNT",
                    "TEST_FIGI",
                    strategy
            );

            return true;

        } catch (DataIntegrityViolationException exception) {

            assertProtectionViolation(exception);

            return false;
        }
    }
}
