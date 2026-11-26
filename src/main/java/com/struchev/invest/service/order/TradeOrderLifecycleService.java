
package com.struchev.invest.service.order;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;
import com.struchev.invest.repository.TradeOrderRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TradeOrderLifecycleService {

    private final TradeOrderRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TradeOrderEntity markSubmitting(Long orderId) {

        var order = findOrder(orderId);

        validateOrder(order);

        if (order.getStatus() != TradeOrderStatus.CREATED) {

            throw new IllegalStateException(
                    "Cannot submit order from status: "
                            + order.getStatus()
            );
        }

        order.setStatus(TradeOrderStatus.SUBMITTING);
        order.setErrorMessage(null);

        return repository.saveAndFlush(order);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TradeOrderEntity markUnknown(Long orderId) {

        var order = findOrder(orderId);

        validateOrder(order);

        switch (order.getStatus()) {

            case SUBMITTING:
            case NEW:
            case PARTIALLY_FILLED:
            case UNKNOWN:
                break;

            default:
                throw new IllegalStateException(
                        "Cannot mark order as unknown from status: "
                                + order.getStatus()
                );
        }

        order.setStatus(TradeOrderStatus.UNKNOWN);

        order.setErrorMessage(
                "Broker order outcome is unknown. "
                        + "Reconciliation is required."
        );

        return repository.saveAndFlush(order);
    }

    private TradeOrderEntity findOrder(Long orderId) {

        if (orderId == null || orderId <= 0) {

            throw new IllegalArgumentException(
                    "Valid order ID is required"
            );
        }

        return repository.findById(orderId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Trade order not found: " + orderId
                        )
                );
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
    }
}
