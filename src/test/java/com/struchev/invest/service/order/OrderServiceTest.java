package com.struchev.invest.service.order;

import com.struchev.invest.entity.CandleDomainEntity;
import com.struchev.invest.entity.OrderDomainEntity;
import com.struchev.invest.repository.OrderRepository;
import com.struchev.invest.service.dictionary.InstrumentService;
import com.struchev.invest.service.price.PricesService;
import com.struchev.invest.service.tinkoff.ITinkoffOrderAPI;
import com.struchev.invest.strategy.AStrategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderServiceTest {

    private OrderRepository orderRepository;
    private InstrumentService instrumentService;
    private ITinkoffOrderAPI tinkoffOrderAPI;
    private PricesService pricesService;

    private OrderService orderService;
    private AStrategy strategy;
    private CandleDomainEntity candle;
    private OrderDomainEntity activeOrder;

    private static final String FIGI = "TEST_FIGI";
    private static final String STRATEGY = "TEST_STRATEGY";

    private static final OffsetDateTime HISTORICAL_TIME =
            OffsetDateTime.parse("2026-01-15T12:00:00Z");

    @BeforeEach
    void setUp() {

        orderRepository = mock(OrderRepository.class);
        instrumentService = mock(InstrumentService.class);
        tinkoffOrderAPI = mock(ITinkoffOrderAPI.class);
        pricesService = mock(PricesService.class);

        orderService = new OrderService(
                orderRepository,
                instrumentService,
                tinkoffOrderAPI,
                pricesService
        );

        strategy = mock(AStrategy.class);
        candle = mock(CandleDomainEntity.class);

        when(strategy.getName()).thenReturn(STRATEGY);

        when(candle.getFigi()).thenReturn(FIGI);
        when(candle.getDateTime()).thenReturn(HISTORICAL_TIME);
        when(candle.getClosingPrice())
                .thenReturn(new BigDecimal("110.00"));

        activeOrder = OrderDomainEntity.builder()
                .id(1L)
                .figi(FIGI)
                .figiTitle("Test instrument")
                .currency("rub")
                .strategy(STRATEGY)
                .purchasePrice(new BigDecimal("100.00"))
                .purchaseCommission(new BigDecimal("0.50"))
                .purchaseDateTime(HISTORICAL_TIME.minusDays(1))
                .lots(10)
                .build();

        when(orderRepository.findAll(any(Sort.class)))
                .thenReturn(List.of(activeOrder));

        when(orderRepository.save(any(OrderDomainEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var instrument = InstrumentService.Instrument.builder()
                .figi(FIGI)
                .currency("rub")
                .name("Test instrument")
                .lot(1)
                .build();

        when(instrumentService.getInstrument(FIGI))
                .thenReturn(instrument);

        var response = ITinkoffOrderAPI.OrderResult.builder()
                .price(new BigDecimal("110.00"))
                .commission(new BigDecimal("0.55"))
                .build();

        when(tinkoffOrderAPI.sell(
                instrument,
                new BigDecimal("110.00"),
                10
        )).thenReturn(response);

        orderService.loadOrdersFromDB();
    }

    @Test
    void shouldUseCandleTimeInEmulatorMode() {

        ReflectionTestUtils.setField(
                orderService, "emulator", true
        );

        var result = orderService.sell(candle, strategy);

        assertEquals(
                HISTORICAL_TIME,
                result.getSellDateTime()
        );
    }

    @Test
    void shouldUseCurrentTimeInRealMode() {

        ReflectionTestUtils.setField(
                orderService, "emulator", false
        );

        var before = OffsetDateTime.now();

        var result = orderService.sell(candle, strategy);

        var after = OffsetDateTime.now();

        assertNotNull(result.getSellDateTime());

        assertFalse(
                result.getSellDateTime().isBefore(before)
        );

        assertFalse(
                result.getSellDateTime().isAfter(after)
        );

        assertNotEquals(
                HISTORICAL_TIME,
                result.getSellDateTime()
        );
    }

    @Test
    void shouldSaveHistoricalSellTimeToRepository() {

        ReflectionTestUtils.setField(
                orderService, "emulator", true
        );

        orderService.sell(candle, strategy);

        verify(orderRepository).save(argThat(order ->
                HISTORICAL_TIME.equals(order.getSellDateTime())
        ));
    }
}
