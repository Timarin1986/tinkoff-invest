
package com.struchev.invest.service.order;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;
import com.struchev.invest.repository.TradeOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TradeOrderService {

    private final TradeOrderRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TradeOrderEntity registerOrder(
            TradeOrderEntity.Environment environment,
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

        if (environment == null) {
            throw new IllegalArgumentException(
                    "Trading environment is required"
            );
        }

        if (environment == TradeOrderEntity.Environment.LIVE) {
            throw new IllegalStateException(
                    "Live trading is disabled"
            );
        }

        validateText(accountId, "Account ID");
        validateText(figi, "FIGI");
        validateText(strategy, "Strategy");
        validateText(currency, "Currency");

        if (direction == null) {
            throw new IllegalArgumentException(
                    "Order direction is required"
            );
        }

        if (requestedLots <= 0) {
            throw new IllegalArgumentException(
                    "Requested lots must be positive"
            );
        }

        if (lotSize <= 0) {
            throw new IllegalArgumentException(
                    "Lot size must be positive"
            );
        }

        if (requestedPrice == null
                || requestedPrice.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Requested price must be positive"
            );
        }

        if (direction == TradeOrderEntity.Direction.SELL
                && (positionId == null || positionId <= 0)) {
            throw new IllegalArgumentException(
                    "Sell order requires a position ID"
            );
        }

        var order = TradeOrderEntity.builder()
                .clientOrderId(UUID.randomUUID().toString())
                .environment(environment)
                .accountId(accountId)
                .figi(figi)
                .strategy(strategy)
                .direction(direction)
                .status(TradeOrderStatus.CREATED)
                .requestedLots(requestedLots)
                .lotSize(lotSize)
                .requestedPrice(requestedPrice)
                .currency(currency)
                .positionId(positionId)
                .reconciled(false)
                .build();

        return repository.saveAndFlush(order);
    }

    private void validateText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be empty"
            );
        }
    }
}
