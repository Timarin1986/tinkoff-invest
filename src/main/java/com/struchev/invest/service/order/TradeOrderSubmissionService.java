
package com.struchev.invest.service.order;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.service.tinkoff.ITinkoffSandboxOrderAPI;
import com.struchev.invest.service.tinkoff.TinkoffOrderResponseMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "tinkoff.emulator",
        havingValue = "false"
)
public class TradeOrderSubmissionService {

    private final TradeOrderService tradeOrderService;

    private final TradeOrderLifecycleService lifecycleService;

    private final TradeOrderExecutionService executionService;

    private final ITinkoffSandboxOrderAPI sandboxAPI;

    private final TinkoffOrderResponseMapper responseMapper;

    /**
     * Регистрация и отправка новой заявки.
     *
     * Поддерживается исключительно SANDBOX.
     *
     * requestedLots - количество биржевых лотов.
     * lotSize - количество инструментов в одном лоте.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TradeOrderEntity registerAndSubmit(
            String accountId,
            String figi,
            String strategy,
            TradeOrderEntity.Direction direction,
            long requestedLots,
            int lotSize,
            BigDecimal requestedPrice,
            String currency,
            Long positionId
    ) {

        // 1. Регистрируем заявку в отдельной транзакции.
        var order = tradeOrderService.registerOrder(
                TradeOrderEntity.Environment.SANDBOX,
                accountId,
                figi,
                strategy,
                direction,
                requestedLots,
                lotSize,
                requestedPrice,
                currency,
                positionId
        );

        // 2. Проверяем параметры подключения к Sandbox.
        sandboxAPI.validateSandboxOrder(order);

        // 3. Фиксируем начало отправки в отдельной транзакции.
        var submittingOrder = lifecycleService.markSubmitting(
                order.getId()
        );

        try {

            // 4. Отправляем заявку с сохраненным Client Order ID.
            var response = sandboxAPI.submitSandboxOrder(
                    submittingOrder
            );

            // 5. Проверяем и преобразуем ответ брокера.
            var brokerResult =
                    responseMapper.mapPostOrderResponse(
                            submittingOrder,
                            response
                    );

            // 6. Сохраняем подтвержденный результат.
            return executionService.applyBrokerResult(
                    submittingOrder.getId(),
                    brokerResult
            );

        } catch (RuntimeException exception) {

            // Результат отправки неизвестен.
            // Повторная отправка запрещена до сверки.

            var failure = new IllegalStateException(
                    "Order outcome is uncertain. "
                            + "Reconciliation required. "
                            + "Client order ID: "
                            + submittingOrder.getClientOrderId(),
                    exception
            );

            try {

                lifecycleService.markUnknown(
                        submittingOrder.getId()
                );

            } catch (RuntimeException recoveryException) {

                // Если БД недоступна или статус уже изменился,
                // не пытаемся повторно отправить заявку.
                failure.addSuppressed(recoveryException);
            }

            throw failure;
        }
    }
}
