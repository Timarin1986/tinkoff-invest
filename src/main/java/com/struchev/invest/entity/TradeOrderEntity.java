
package com.struchev.invest.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "trade_order",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_trade_order_client_id",
            columnNames = "client_order_id"
        )
    },
    indexes = {
        @Index(
            name = "idx_trade_order_status",
            columnList = "status"
        ),
        @Index(
            name = "idx_trade_order_figi",
            columnList = "figi"
        ),
        @Index(
            name = "idx_trade_order_account_strategy",
            columnList = "account_id,strategy"
        )
    }
)
public class TradeOrderEntity {

    public enum Direction {
        BUY,
        SELL
    }

    public enum Environment {
        EMULATOR,
        SANDBOX,
        LIVE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "client_order_id",
        nullable = false,
        updatable = false,
        length = 36
    )
    private String clientOrderId;

    @Column(name = "broker_order_id")
    private String brokerOrderId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Environment environment;

    @Column(nullable = false)
    private String figi;

    @Column(nullable = false)
    private String strategy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TradeOrderStatus status =
        TradeOrderStatus.CREATED;

    @Column(nullable = false)
    private long requestedLots;

    private Long executedLots;

    @Column(nullable = false)
    private int lotSize;

    @Column(precision = 28, scale = 9)
    private BigDecimal requestedPrice;

    @Column(precision = 28, scale = 9)
    private BigDecimal averageExecutedPrice;

    @Column(precision = 28, scale = 9)
    private BigDecimal executedCommission;

    private String currency;

    private Long positionId;

    @Column(nullable = false)
    @Builder.Default
    private boolean reconciled = false;

    @Column(length = 2000)
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    @Version
    private Long version;
}
