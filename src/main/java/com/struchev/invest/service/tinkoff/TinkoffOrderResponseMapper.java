
package com.struchev.invest.service.tinkoff;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;
import com.struchev.invest.service.order.TradeOrderExecutionService.BrokerResult;

import org.springframework.stereotype.Component;

import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderExecutionReportStatus;
import ru.tinkoff.piapi.contract.v1.PostOrderResponse;

import java.math.BigDecimal;

@Component
public class TinkoffOrderResponseMapper {

    public BrokerResult mapPostOrderResponse(
            TradeOrderEntity order,
            PostOrderResponse response
    ) {

        if (order == null || response == null) {
            throw new IllegalArgumentException(
                    "Order and broker response are required"
            );
        }

        if (order.getEnvironment()
                != TradeOrderEntity.Environment.SANDBOX) {

            throw new IllegalStateException(
                    "Only sandbox orders are supported"
            );
        }

        validateIdentity(order, response);

        var status = mapStatus(
                response.getExecutionReportStatus()
        );

        long requestedLots = response.getLotsRequested();
        long executedLots = response.getLotsExecuted();

        if (requestedLots != order.getRequestedLots()) {

            throw new IllegalArgumentException(
                    "Requested lots mismatch"
            );
        }

        if (executedLots < 0
                || executedLots > requestedLots) {

            throw new IllegalArgumentException(
                    "Invalid executed lots"
            );
        }

        BigDecimal executedPrice = null;
        BigDecimal executedCommission = null;

        if (response.hasExecutedOrderPrice()) {

            executedPrice = convertMoney(
                    response.getExecutedOrderPrice(),
                    order.getCurrency()
            );
        }

        if (response.hasExecutedCommission()) {

            executedCommission = convertMoney(
                    response.getExecutedCommission(),
                    order.getCurrency()
            );
        }

        if (executedLots > 0) {

            if (executedPrice == null
                    || executedPrice.signum() <= 0) {

                throw new IllegalArgumentException(
                        "Executed price is missing or invalid"
                );
            }

            if (executedCommission == null
                    || executedCommission.signum() < 0) {

                throw new IllegalArgumentException(
                        "Executed commission is missing or invalid"
                );
            }

        } else {

            if (executedPrice != null
                    && executedPrice.signum() != 0) {

                throw new IllegalArgumentException(
                        "Unexpected executed price"
                );
            }

            if (executedCommission != null
                    && executedCommission.signum() != 0) {

                throw new IllegalArgumentException(
                        "Unexpected executed commission"
                );
            }

            executedPrice = null;
        }

        String brokerOrderId = response.getOrderId();

        if (brokerOrderId.isBlank()) {

            if (status != TradeOrderStatus.REJECTED) {

                throw new IllegalArgumentException(
                        "Broker order ID is missing"
                );
            }

            brokerOrderId = null;
        }

        if (order.getBrokerOrderId() != null
                && !order.getBrokerOrderId()
                        .equals(brokerOrderId)) {

            throw new IllegalArgumentException(
                    "Broker order ID mismatch"
            );
        }

        return new BrokerResult(
                response.getOrderRequestId(),
                brokerOrderId,
                status,
                requestedLots,
                executedLots,
                executedPrice,
                executedCommission,
                order.getCurrency()
        );
    }

    public TradeOrderStatus mapStatus(
            OrderExecutionReportStatus status
    ) {

        if (status == null) {

            throw new IllegalArgumentException(
                    "Broker status is required"
            );
        }

        return switch (status) {

            case EXECUTION_REPORT_STATUS_NEW ->
                    TradeOrderStatus.NEW;

            case EXECUTION_REPORT_STATUS_PARTIALLYFILL ->
                    TradeOrderStatus.PARTIALLY_FILLED;

            case EXECUTION_REPORT_STATUS_FILL ->
                    TradeOrderStatus.FILLED;

            case EXECUTION_REPORT_STATUS_CANCELLED ->
                    TradeOrderStatus.CANCELLED;

            case EXECUTION_REPORT_STATUS_REJECTED ->
                    TradeOrderStatus.REJECTED;

            default ->
                    throw new IllegalArgumentException(
                            "Unsupported broker status: " + status
                    );
        };
    }

    private void validateIdentity(
            TradeOrderEntity order,
            PostOrderResponse response
    ) {

        if (order.getClientOrderId() == null
                || order.getClientOrderId().isBlank()
                || !order.getClientOrderId().equals(
                        response.getOrderRequestId()
                )) {

            throw new IllegalArgumentException(
                    "Client order ID mismatch or missing"
            );
        }

        if (order.getFigi() == null
                || order.getFigi().isBlank()
                || !order.getFigi().equals(response.getFigi())) {

            throw new IllegalArgumentException(
                    "Instrument FIGI mismatch"
            );
        }

        if (order.getDirection() == null) {

            throw new IllegalArgumentException(
                    "Order direction is required"
            );
        }

        var expectedDirection = switch (
                order.getDirection()
        ) {

            case BUY -> OrderDirection.ORDER_DIRECTION_BUY;

            case SELL -> OrderDirection.ORDER_DIRECTION_SELL;
        };

        if (response.getDirection() != expectedDirection) {

            throw new IllegalArgumentException(
                    "Order direction mismatch"
            );
        }

        if (order.getCurrency() == null
                || order.getCurrency().isBlank()) {

            throw new IllegalArgumentException(
                    "Order currency is required"
            );
        }
    }

    private BigDecimal convertMoney(
            MoneyValue money,
            String expectedCurrency
    ) {

        long units = money.getUnits();
        int nano = money.getNano();

        if (nano < -999_999_999
                || nano > 999_999_999) {

            throw new IllegalArgumentException(
                    "Invalid MoneyValue nano"
            );
        }

        if ((units > 0 && nano < 0)
                || (units < 0 && nano > 0)) {

            throw new IllegalArgumentException(
                    "Inconsistent MoneyValue signs"
            );
        }

        var value = BigDecimal.valueOf(units)
                .add(BigDecimal.valueOf(nano, 9));

        if (!expectedCurrency.equalsIgnoreCase(
                money.getCurrency()
        )) {

            // Protobuf may contain an empty zero value.
            if (!(value.signum() == 0
                    && money.getCurrency().isBlank())) {

                throw new IllegalArgumentException(
                        "MoneyValue currency mismatch"
                );
            }
        }

        return value;
    }
}
