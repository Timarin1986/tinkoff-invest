
package com.struchev.invest.service.order;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;
import com.struchev.invest.repository.TradeOrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:14.1-alpine:///trade_order_service_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ActiveProfiles("test")
@Import(TradeOrderService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TradeOrderServiceTest {

    @Autowired
    private TradeOrderService service;

    @Autowired
    private TradeOrderRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    private TradeOrderEntity registerBuy() {
        return service.registerOrder(
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

    @Test
    void shouldRegisterBuyOrder() {

        var order = registerBuy();

        assertNotNull(order.getId());
        assertNotNull(order.getClientOrderId());

        assertDoesNotThrow(() ->
                UUID.fromString(order.getClientOrderId())
        );

        var saved = repository
                .findByClientOrderId(order.getClientOrderId())
                .orElseThrow();

        assertEquals(
                TradeOrderStatus.CREATED,
                saved.getStatus()
        );

        assertEquals(
                TradeOrderEntity.Direction.BUY,
                saved.getDirection()
        );

        assertEquals(
                TradeOrderEntity.Environment.SANDBOX,
                saved.getEnvironment()
        );

        assertEquals(5L, saved.getRequestedLots());
        assertEquals(10, saved.getLotSize());
        assertFalse(saved.isReconciled());
        assertNull(saved.getExecutedLots());
        assertNull(saved.getBrokerOrderId());
    }

    @Test
    void shouldRegisterSellOrder() {

        var order = service.registerOrder(
                TradeOrderEntity.Environment.SANDBOX,
                "TEST_ACCOUNT",
                "TEST_FIGI",
                "TEST_STRATEGY",
                TradeOrderEntity.Direction.SELL,
                5L,
                10,
                new BigDecimal("105.00"),
                "rub",
                123L
        );

        var saved = repository.findById(
                order.getId()
        ).orElseThrow();

        assertEquals(
                TradeOrderEntity.Direction.SELL,
                saved.getDirection()
        );

        assertEquals(
                123L,
                saved.getPositionId().longValue()
        );
    }

    @Test
    void shouldRejectLiveTrading() {

        assertThrows(
                IllegalStateException.class,
                () -> service.registerOrder(
                        TradeOrderEntity.Environment.LIVE,
                        "TEST_ACCOUNT",
                        "TEST_FIGI",
                        "TEST_STRATEGY",
                        TradeOrderEntity.Direction.BUY,
                        5L,
                        10,
                        new BigDecimal("100.50"),
                        "rub",
                        null
                )
        );

        assertEquals(0L, repository.count());
    }

    @Test
    void shouldRejectZeroLots() {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.registerOrder(
                        TradeOrderEntity.Environment.SANDBOX,
                        "TEST_ACCOUNT",
                        "TEST_FIGI",
                        "TEST_STRATEGY",
                        TradeOrderEntity.Direction.BUY,
                        0L,
                        10,
                        new BigDecimal("100.50"),
                        "rub",
                        null
                )
        );

        assertEquals(0L, repository.count());
    }

    @Test
    void shouldRejectInvalidLotSize() {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.registerOrder(
                        TradeOrderEntity.Environment.SANDBOX,
                        "TEST_ACCOUNT",
                        "TEST_FIGI",
                        "TEST_STRATEGY",
                        TradeOrderEntity.Direction.BUY,
                        5L,
                        0,
                        new BigDecimal("100.50"),
                        "rub",
                        null
                )
        );

        assertEquals(0L, repository.count());
    }

    @Test
    void shouldRejectNegativePrice() {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.registerOrder(
                        TradeOrderEntity.Environment.SANDBOX,
                        "TEST_ACCOUNT",
                        "TEST_FIGI",
                        "TEST_STRATEGY",
                        TradeOrderEntity.Direction.BUY,
                        5L,
                        10,
                        new BigDecimal("-100.50"),
                        "rub",
                        null
                )
        );

        assertEquals(0L, repository.count());
    }

    @Test
    void shouldRejectSellWithoutPosition() {

        assertThrows(
                IllegalArgumentException.class,
                () -> service.registerOrder(
                        TradeOrderEntity.Environment.SANDBOX,
                        "TEST_ACCOUNT",
                        "TEST_FIGI",
                        "TEST_STRATEGY",
                        TradeOrderEntity.Direction.SELL,
                        5L,
                        10,
                        new BigDecimal("100.50"),
                        "rub",
                        null
                )
        );

        assertEquals(0L, repository.count());
    }

    @Test
    void shouldPreserveOrderAfterOuterTransactionRollback() {

        var transaction = new TransactionTemplate(
                transactionManager
        );

        String clientOrderId = transaction.execute(status -> {

            var order = registerBuy();

            status.setRollbackOnly();

            return order.getClientOrderId();
        });

        assertNotNull(clientOrderId);

        var saved = repository
                .findByClientOrderId(clientOrderId)
                .orElseThrow();

        assertEquals(
                TradeOrderStatus.CREATED,
                saved.getStatus()
        );

        assertFalse(saved.isReconciled());
        assertEquals(1L, repository.count());
    }
}
