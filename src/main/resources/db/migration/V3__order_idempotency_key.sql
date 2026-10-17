-- Idempotency-Key sent with POST /api/orders. Unique so a retried request can't create a second order;
-- existing orders have none (NULL), and a unique constraint allows any number of NULLs.

ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(100);
ALTER TABLE orders ADD CONSTRAINT uk_orders_idempotency_key UNIQUE (idempotency_key);
