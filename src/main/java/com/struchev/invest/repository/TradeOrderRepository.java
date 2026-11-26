
package com.struchev.invest.repository;

import com.struchev.invest.entity.TradeOrderEntity;
import com.struchev.invest.entity.TradeOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TradeOrderRepository
        extends JpaRepository<TradeOrderEntity, Long> {

    Optional<TradeOrderEntity> findByClientOrderId(
        String clientOrderId
    );

    List<TradeOrderEntity>
        findByEnvironmentAndAccountIdAndStatusIn(
            TradeOrderEntity.Environment environment,
            String accountId,
            Collection<TradeOrderStatus> statuses
        );

    List<TradeOrderEntity>
        findByEnvironmentAndAccountIdAndReconciledFalse(
            TradeOrderEntity.Environment environment,
            String accountId
        );

    boolean
        existsByEnvironmentAndAccountIdAndFigiAndStrategyAndReconciledFalse(
            TradeOrderEntity.Environment environment,
            String accountId,
            String figi,
            String strategy
        );
}
