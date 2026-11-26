
-- Prevent conflicting unreconciled trade orders.
CREATE UNIQUE INDEX IF NOT EXISTS
    uk_trade_order_unreconciled_instrument
ON trade_order (
    environment,
    account_id,
    figi
)
WHERE reconciled = false;
