
package com.struchev.invest.service.order;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;
import com.struchev.invest.repository.TradeOrderRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class TradeOrderExecutionService {

    private final TradeOrderRepository repository;

    public record BrokerResult(
            String clientOrderId,
            String brokerOrderId,
            TradeOrderStatus status,
            long requestedLots,
            long executedLots,
            BigDecimal averageExecutedPrice,
            BigDecimal executedCommission,
            String currency
    ) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TradeOrderEntity applyBrokerResult(
            Long orderId,
            BrokerResult result
    ) {

        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException(
                    "Valid order ID is required"
            );
        }

        if (result == null) {
            throw new IllegalArgumentException(
                    "Broker result is required"
            );
        }

        var order = repository.findById(orderId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Trade order not found: " + orderId
                        )
                );

        validateOrder(order);
        validateResult(order, result);

        if (result.brokerOrderId() != null
                && !result.brokerOrderId().isBlank()) {

            order.setBrokerOrderId(
                    result.brokerOrderId()
            );
        }

        order.setStatus(result.status());
        order.setExecutedLots(result.executedLots());

        order.setAverageExecutedPrice(
                result.averageExecutedPrice()
        );

        order.setExecutedCommission(
                result.executedCommission()
        );

        order.setErrorMessage(null);

        // Execution confirmation is not reconciliation.
        order.setReconciled(false);

        return repository.saveAndFlush(order);
    }

    private void validateOrder(TradeOrderEntity order) {

        if (order.getEnvironment()
                == TradeOrderEntity.Environment.LIVE) {

            throw new IllegalStateException(
                    "Live trading is disabled"
            );
        }

        if (order.isReconciled()) {

            throw new IllegalStateException(
                    "Reconciled order cannot be modified"
            );
        }

        switch (order.getStatus()) {

            case SUBMITTING:
            case NEW:
            case PARTIALLY_FILLED:
            case UNKNOWN:
                break;

            default:
                throw new IllegalStateException(
                        "Cannot apply broker result from status: "
                                + order.getStatus()
                );
        }
    }

    private void validateResult(
            TradeOrderEntity order,
            BrokerResult result
    ) {

        if (!order.getClientOrderId().equals(
                result.clientOrderId()
        )) {

            throw new IllegalArgumentException(
                    "Client order ID mismatch"
            );
        }

        if (result.status() == null) {

            throw new IllegalArgumentException(
                    "Broker order status is required"
            );
        }

        if (result.requestedLots()
                != order.getRequestedLots()) {

            throw new IllegalArgumentException(
                    "Requested lots mismatch"
            );
        }

        if (result.executedLots() < 0
                || result.executedLots()
                > order.getRequestedLots()) {

            throw new IllegalArgumentException(
                    "Invalid executed lots"
            );
        }

        if (order.getExecutedLots() != null
                && result.executedLots()
                < order.getExecutedLots()) {

            throw new IllegalArgumentException(
                    "Executed lots cannot decrease"
            );
        }

        if (result.currency() == null
                || !result.currency().equalsIgnoreCase(
                        order.getCurrency()
                )) {

            throw new IllegalArgumentException(
                    "Execution currency mismatch"
            );
        }

        boolean hasBrokerOrderId =
                result.brokerOrderId() != null
                        && !result.brokerOrderId().isBlank();

        if (!hasBrokerOrderId
                && result.status() != TradeOrderStatus.REJECTED) {

            throw new IllegalArgumentException(
                    "Broker order ID is required"
            );
        }

        if (order.getBrokerOrderId() != null
                && !order.getBrokerOrderId().equals(
                        result.brokerOrderId()
                )) {

            throw new IllegalArgumentException(
                    "Broker order ID mismatch"
            );
        }

        validateExecutionStatus(result);
        validateExecutionAmounts(result);
    }

    private void validateExecutionStatus(
            BrokerResult result
    ) {

        long executed = result.executedLots();
        long requested = result.requestedLots();

        switch (result.status()) {

            case NEW:

                if (executed != 0) {
                    throw new IllegalArgumentException(
                            "New order cannot have executed lots"
                    );
                }

                break;

            case PARTIALLY_FILLED:

                if (executed <= 0 || executed >= requested) {
                    throw new IllegalArgumentException(
                            "Invalid partial execution"
                    );
                }

                break;

            case FILLED:

                if (executed != requested) {
                    throw new IllegalArgumentException(
                            "Filled order must be fully executed"
                    );
                }

                break;

            case CANCELLED:

                if (executed >= requested) {
                    throw new IllegalArgumentException(
                            "Cancelled order cannot be fully executed"
                    );
                }

                break;

            case REJECTED:

                if (executed != 0) {
                    throw new IllegalArgumentException(
                            "Rejected order cannot have executed lots"
                    );
                }

                break;

            default:

                throw new IllegalArgumentException(
                        "Unsupported broker result status: "
                                + result.status()
                );
        }
    }

    private void validateExecutionAmounts(
            BrokerResult result
    ) {

        var price = result.averageExecutedPrice();
        var commission = result.executedCommission();

        if (commission != null
                && commission.signum() < 0) {

            throw new IllegalArgumentException(
                    "Execution commission cannot be negative"
            );
        }

        if (result.executedLots() > 0) {

            if (price == null || price.signum() <= 0) {

                throw new IllegalArgumentException(
                        "Valid executed price is required"
                );
            }

            if (commission == null) {

                throw new IllegalArgumentException(
                        "Executed commission is required"
                );
            }

        } else {

            if (price != null) {

                throw new IllegalArgumentException(
                        "Unexecuted order cannot have an executed price"
                );
            }

            if (commission != null
                    && commission.signum() != 0) {

                throw new IllegalArgumentException(
                        "Unexecuted order cannot have an execution commission"
                );
            }
        }
    }
}
