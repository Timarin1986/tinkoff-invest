
package com.struchev.invest.service.processor;

import com.struchev.invest.entity.CandleDomainEntity;
import com.struchev.invest.entity.OrderDomainEntity;
import com.struchev.invest.service.notification.NotificationService;
import com.struchev.invest.service.order.OrderService;
import com.struchev.invest.service.price.PricesService;
import com.struchev.invest.strategy.AStrategy;
import com.struchev.invest.strategy.StrategySelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.*;

class PurchaseServiceTest {

    private OrderService orderService;
    private StrategySelector strategySelector;
    private NotificationService notificationService;
    private CalculatorFacade calculator;
    private PricesService pricesService;

    private PurchaseService purchaseService;
    private AStrategy strategy;
    private CandleDomainEntity candle;

    private static final String FIGI = "TEST_FIGI";

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-01-15T12:00:00Z");

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        strategySelector = mock(StrategySelector.class);
        notificationService = mock(NotificationService.class);
        calculator = mock(CalculatorFacade.class);
        pricesService = mock(PricesService.class);

        purchaseService = new PurchaseService(
                orderService,
                strategySelector,
                notificationService,
                calculator,
                pricesService
        );

        strategy = mock(AStrategy.class);
        candle = mock(CandleDomainEntity.class);

        when(candle.getFigi()).thenReturn(FIGI);
        when(candle.getDateTime()).thenReturn(NOW);
        when(candle.getClosingPrice())
                .thenReturn(BigDecimal.valueOf(100));

        when(strategy.getType())
                .thenReturn(AStrategy.Type.instrumentByFiat);

        when(strategy.getDelayBySL())
                .thenReturn(Duration.ofMinutes(60));

        when(strategy.isOnlySell()).thenReturn(false);

        when(strategySelector.suitableByFigi(FIGI, null))
                .thenReturn(List.of(strategy));

        when(orderService.findActiveByFigiAndStrategy(
                FIGI, strategy)).thenReturn(null);

        when(calculator.isShouldBuy(strategy, candle))
                .thenReturn(true);

        OrderDomainEntity newOrder =
                mock(OrderDomainEntity.class);

        when(newOrder.getPurchasePrice())
                .thenReturn(BigDecimal.valueOf(100));

        when(orderService.buy(candle, strategy))
                .thenReturn(newOrder);
    }

    @Test
    void shouldBuyWhenNoClosedOrders() {
        when(orderService.findClosedByFigiAndStrategy(
                FIGI, strategy)).thenReturn(List.of());

        purchaseService.observeCandleNoThrow(candle);

        verify(orderService).buy(candle, strategy);
        verify(notificationService, times(1))
                .sendMessage(startsWith("Buy "));
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void shouldNotBuyBeforeDelayExpires() {
        OrderDomainEntity lastOrder =
                mock(OrderDomainEntity.class);

        when(lastOrder.getSellDateTime())
                .thenReturn(NOW.minusMinutes(30));

        when(orderService.findClosedByFigiAndStrategy(
                FIGI, strategy)).thenReturn(List.of(lastOrder));

        purchaseService.observeCandleNoThrow(candle);

        verify(orderService, never()).buy(candle, strategy);
        verifyNoInteractions(notificationService);
    }

    @Test
    void shouldBuyAfterDelayExpires() {
        OrderDomainEntity lastOrder =
                mock(OrderDomainEntity.class);

        when(lastOrder.getSellDateTime())
                .thenReturn(NOW.minusMinutes(90));

        when(orderService.findClosedByFigiAndStrategy(
                FIGI, strategy)).thenReturn(List.of(lastOrder));

        purchaseService.observeCandleNoThrow(candle);

        verify(orderService).buy(candle, strategy);
        verify(notificationService, times(1))
                .sendMessage(startsWith("Buy "));
        verifyNoMoreInteractions(notificationService);
    }
}
