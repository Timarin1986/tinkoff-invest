
package com.struchev.invest.service.tinkoff;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;
import com.struchev.invest.service.candle.ConvertorUtils;
import com.struchev.invest.service.dictionary.InstrumentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderType;
import ru.tinkoff.piapi.contract.v1.PostOrderResponse;
import ru.tinkoff.piapi.contract.v1.Quotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
@Slf4j
@ConditionalOnProperty(
        name = "tinkoff.emulator",
        havingValue = "false"
)
@RequiredArgsConstructor
public class TinkoffGRPCAPI extends ATinkoffAPI
        implements ITinkoffSandboxOrderAPI {

    /**
     * Проверка заявки перед отправкой в Sandbox.
     */
    @Override
    public void validateSandboxOrder(TradeOrderEntity order) {

        if (order == null) {
            throw new IllegalArgumentException(
                    "Order is required"
            );
        }

        if (order.getEnvironment()
                != TradeOrderEntity.Environment.SANDBOX) {

            throw new IllegalStateException(
                    "Only SANDBOX orders are allowed"
            );
        }

        if (!getIsSandboxMode()) {

            throw new IllegalStateException(
                    "Sandbox API is not configured"
            );
        }

        if (getApi() == null) {

            throw new IllegalStateException(
                    "Tinkoff API is not initialized"
            );
        }

        if (getAccountId() == null
                || !getAccountId().equals(
                        order.getAccountId()
                )) {

            throw new IllegalStateException(
                    "Sandbox account mismatch"
            );
        }

        if (order.getClientOrderId() == null
                || order.getClientOrderId().isBlank()) {

            throw new IllegalArgumentException(
                    "Client order ID is required"
            );
        }

        if (order.getFigi() == null
                || order.getFigi().isBlank()) {

            throw new IllegalArgumentException(
                    "FIGI is required"
            );
        }

        if (order.getDirection() == null) {

            throw new IllegalArgumentException(
                    "Order direction is required"
            );
        }

        if (order.getRequestedLots() <= 0) {

            throw new IllegalArgumentException(
                    "Requested lots must be positive"
            );
        }

        if (order.getRequestedPrice() == null
                || order.getRequestedPrice().signum() <= 0) {

            throw new IllegalArgumentException(
                    "Invalid order price"
            );
        }
    }

    /**
     * Отправка заявки исключительно в Sandbox.
     *
     * Используется постоянный Client Order ID,
     * ранее сохранённый в базе данных.
     */
    @Override
    public PostOrderResponse submitSandboxOrder(
            TradeOrderEntity order
    ) {

        validateSandboxOrder(order);

        if (order.getStatus()
                != TradeOrderStatus.SUBMITTING) {

            throw new IllegalStateException(
                    "Order must be in SUBMITTING status"
            );
        }

        var direction = switch (order.getDirection()) {

            case BUY ->
                    OrderDirection.ORDER_DIRECTION_BUY;

            case SELL ->
                    OrderDirection.ORDER_DIRECTION_SELL;
        };

        var quotation = toQuotation(
                order.getRequestedPrice()
        );

        log.info(
                "Submitting SANDBOX order: clientId {}, FIGI {}, lots {}, direction {}",
                order.getClientOrderId(),
                order.getFigi(),
                order.getRequestedLots(),
                direction
        );

        return getApi()
                .getSandboxService()
                .postOrderSync(
                        order.getFigi(),
                        order.getRequestedLots(),
                        quotation,
                        direction,
                        order.getAccountId(),
                        OrderType.ORDER_TYPE_MARKET,
                        order.getClientOrderId()
                );
    }

    /**
     * Старый метод покупки.
     * Временно сохраняется для совместимости.
     */
    @Override
    public OrderResult buy(
            InstrumentService.Instrument instrument,
            BigDecimal price,
            Integer count
    ) {

        long quantity = count / instrument.getLot();

        var quotation = toQuotation(price);

        var uuid = UUID.randomUUID().toString();

        log.info(
                "Send postOrderSync with: figi {}, quantity {}, quotation {}, direction {}, acc {}, type {}, id {}",
                instrument.getFigi(),
                quantity,
                quotation,
                OrderDirection.ORDER_DIRECTION_BUY,
                getAccountId(),
                OrderType.ORDER_TYPE_MARKET,
                uuid
        );

        if (getIsSandboxMode()) {

            var result = getApi()
                    .getSandboxService()
                    .postOrderSync(
                            instrument.getFigi(),
                            quantity,
                            quotation,
                            OrderDirection.ORDER_DIRECTION_BUY,
                            getAccountId(),
                            OrderType.ORDER_TYPE_MARKET,
                            uuid
                    );

            return OrderResult.builder()
                    .commission(
                            ConvertorUtils.toBigDecimal(
                                    result.getInitialCommission(),
                                    8
                            )
                    )
                    .price(
                            getOrderPrice(
                                    result.getInitialOrderPricePt(),
                                    result.getExecutedOrderPrice(),
                                    price,
                                    count
                            )
                    )
                    .build();

        } else {

            var result = getApi()
                    .getOrdersService()
                    .postOrderSync(
                            instrument.getFigi(),
                            quantity,
                            quotation,
                            OrderDirection.ORDER_DIRECTION_BUY,
                            getAccountId(),
                            OrderType.ORDER_TYPE_MARKET,
                            uuid
                    );

            return OrderResult.builder()
                    .commission(
                            ConvertorUtils.toBigDecimal(
                                    result.getInitialCommission(),
                                    8
                            )
                    )
                    .price(
                            getOrderPrice(
                                    result.getInitialOrderPricePt(),
                                    result.getExecutedOrderPrice(),
                                    price,
                                    count
                            )
                    )
                    .build();
        }
    }

    /**
     * Старый метод продажи.
     * Временно сохраняется для совместимости.
     */
    @Override
    public OrderResult sell(
            InstrumentService.Instrument instrument,
            BigDecimal price,
            Integer count
    ) {

        long quantity = count / instrument.getLot();

        var quotation = toQuotation(price);

        var uuid = UUID.randomUUID().toString();

        log.info(
                "Send postOrderSync with: figi {}, quantity {}, quotation {}, direction {}, acc {}, type {}, id {}",
                instrument.getFigi(),
                quantity,
                quotation,
                OrderDirection.ORDER_DIRECTION_SELL,
                getAccountId(),
                OrderType.ORDER_TYPE_MARKET,
                uuid
        );

        if (getIsSandboxMode()) {

            var result = getApi()
                    .getSandboxService()
                    .postOrderSync(
                            instrument.getFigi(),
                            quantity,
                            quotation,
                            OrderDirection.ORDER_DIRECTION_SELL,
                            getAccountId(),
                            OrderType.ORDER_TYPE_MARKET,
                            uuid
                    );

            return OrderResult.builder()
                    .commission(
                            ConvertorUtils.toBigDecimal(
                                    result.getInitialCommission(),
                                    8
                            )
                    )
                    .price(
                            getOrderPrice(
                                    result.getInitialOrderPricePt(),
                                    result.getExecutedOrderPrice(),
                                    price,
                                    count
                            )
                    )
                    .build();

        } else {

            var result = getApi()
                    .getOrdersService()
                    .postOrderSync(
                            instrument.getFigi(),
                            quantity,
                            quotation,
                            OrderDirection.ORDER_DIRECTION_SELL,
                            getAccountId(),
                            OrderType.ORDER_TYPE_MARKET,
                            uuid
                    );

            return OrderResult.builder()
                    .commission(
                            ConvertorUtils.toBigDecimal(
                                    result.getInitialCommission(),
                                    8
                            )
                    )
                    .price(
                            getOrderPrice(
                                    result.getInitialOrderPricePt(),
                                    result.getExecutedOrderPrice(),
                                    price,
                                    count
                            )
                    )
                    .build();
        }
    }

    /**
     * Преобразование цены в формат Quotation.
     *
     * Units — целая часть цены.
     * Nano — дробная часть в миллиардных долях.
     */
    static Quotation toQuotation(BigDecimal price) {

        return Quotation.newBuilder()
                .setUnits(price.longValue())
                .setNano(
                        price.remainder(BigDecimal.ONE)
                                .movePointRight(9)
                                .intValue()
                )
                .build();
    }

    /**
     * Цена за один инструмент по результату заявки.
     *
     * Для фьючерсов используется стоимость в пунктах.
     * Для остальных инструментов — средняя цена исполнения.
     *
     * Старый механизм сохраняется для совместимости
     * до завершения интеграции нового торгового контура.
     */
    private BigDecimal getOrderPrice(
            Quotation initialOrderPricePt,
            MoneyValue executedOrderPrice,
            BigDecimal price,
            Integer count
    ) {

        var orderPricePt = ConvertorUtils.toBigDecimal(
                initialOrderPricePt,
                4
        );

        if (orderPricePt.compareTo(BigDecimal.ZERO) != 0) {

            return orderPricePt.divide(
                    BigDecimal.valueOf(count),
                    4,
                    RoundingMode.HALF_EVEN
            );
        }

        return ConvertorUtils.toBigDecimal(
                executedOrderPrice,
                4,
                price
        );
    }
}
