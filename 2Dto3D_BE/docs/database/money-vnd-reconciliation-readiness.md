# Integer VND Reconciliation Readiness

**Status:** `MIGRATION_NEXT_PHASE_BLOCKED`

**Scope:** Read-only inventory and disposable-fixture preflight design. No historical data has been read, backfilled, reconciled, or certified by this document.

## Current representation inventory

The legacy schema is defined in V1. V6 adds four nullable `BIGINT` columns without defaults or backfill. Java entities and DTOs still use the legacy representation; the VND columns are not currently mapped by these entities. The representation column describes the current model, not a claim that every database row is valid.

| Field | Current Java / SQL | Role and current read/write path | Planned representation |
|---|---|---|---|
| `price_estimations.material_cost` | `Double` / nullable `DOUBLE PRECISION` | Estimate cost component. No current backend estimate writer was found; retained estimate rows may be read by checkout-related code only indirectly through the estimate entity. | Keep as a decimal calculation component; not an independently payable amount. Define precision and provenance before any migration. |
| `price_estimations.labor_cost` | `Double` / nullable `DOUBLE PRECISION` | Estimate cost component. No current backend writer was found. | Decimal intermediate; not an independently payable amount. |
| `price_estimations.print_time_hours` | `Double` / nullable `DOUBLE PRECISION` | Duration measurement, not money. | Keep a suitable fractional time representation; do not convert to integer VND. |
| `price_estimations.total_price` | `Double` / nullable `DOUBLE PRECISION` | Legacy final quote/payable unit amount. `CartServiceImpl` reads and validates it; `OrderServiceImpl` reads it for order and item snapshots. A current backend estimate-generation writer was not found in `src/main/java`. | `total_price_vnd BIGINT NULL` is the V6 expand field. Future quote generation should compute using decimal arithmetic and round the completed quote once with `HALF_UP`; backfill remains unapproved. |
| `materials.price_per_gram` | `Double` / `DOUBLE PRECISION NOT NULL` | Monetary rate, not a payable total. Written from `MaterialRequest` by `MaterialServiceImpl`; exposed as `Double` by `MaterialResponse`. | Not covered by V6. Keep distinct from whole-dong payable amounts; agree rate precision/scale and API compatibility before changing it. |
| `materials.stock_quantity` | `Double` / `DOUBLE PRECISION NOT NULL DEFAULT 0` | Inventory measurement; written from `MaterialRequest`, exposed in `MaterialResponse`. | Measurement, not money; do not convert to integer VND. |
| `orders.total_amount` | `Double` / `DOUBLE PRECISION NOT NULL DEFAULT 0` | Order payable total. `OrderServiceImpl` writes the sum of legacy estimate total × integer quantity; `OrderResponse.totalAmount` is `Double`. `PaymentServiceImpl` reads it. | `total_amount_vnd BIGINT NULL` is the V6 expand field. Future order total is an exact sum of integer line snapshots × quantity. |
| `order_items.price_snapshot` | `Double` / `DOUBLE PRECISION NOT NULL` | Historical unit-price snapshot written by `OrderServiceImpl`; `OrderItemResponse.priceSnapshot` is `Double`. | `price_snapshot_vnd BIGINT NULL` is the V6 expand field. Preserve as an immutable whole-dong unit snapshot after an approved cutover. |
| `order_items.quantity` | `Integer` / `INTEGER NOT NULL DEFAULT 1` | Order line quantity; multiplied by the unit snapshot. `OrderServiceImpl` writes it from cart quantity. | Keep an integer count; validate positive and use overflow-checked multiplication/addition. |
| `cart_items.quantity` | `Integer` / `INTEGER NOT NULL DEFAULT 1` | Cart count. `CartServiceImpl` validates and writes it; it is not a monetary amount. | Keep integer count; it is transient input to checkout, not the settled snapshot. |
| `payments.amount` | `Double` / `DOUBLE PRECISION NOT NULL` | Payment amount copied from the order by `PaymentServiceImpl`; callback validation compares it with the order. There is no persisted provider transaction/event identifier in the current `Payment` entity. | `amount_vnd BIGINT NULL` is the V6 expand field. Future attempt records should retain exact integer amount and currency; payment history must not be rounded to force a match. |

V1 defines `materials.price_per_gram`, the estimate fields, order total, order-item snapshot, and payment amount as `DOUBLE PRECISION`; V1 also defines cart/order-item quantities as `INTEGER`. V6 adds only `price_estimations.total_price_vnd`, `orders.total_amount_vnd`, `order_items.price_snapshot_vnd`, and `payments.amount_vnd`, each nullable `BIGINT`, with no default or data update.

### Observed application and provider boundaries

- `OrderServiceImpl` sums `Double total_price * Integer quantity` using `mapToDouble(...).sum()` and copies legacy `total_price` into `price_snapshot`.
- `PaymentServiceImpl` copies `Order.totalAmount` into `Payment.amount` and passes a `Double` amount to both gateway adapters. It compares callback amounts against provider-specific transformations of the legacy `Double`.
- `VnPayService` currently truncates `(long) (amount * 100)`; the provider boundary expects integer VND multiplied by 100. A future adapter must use overflow-checked conversion from an already validated integer VND amount.
- `MomoService` currently applies `Math.round(amount)` to its `Double`. The future integer contract must pass the integer VND value directly and must not round it again.
- `PaymentGatewayService.createPaymentUrl` currently accepts `Double`. Changing this interface is outside this phase.
- `MaterialRequest.pricePerGram` and `MaterialResponse.pricePerGram` are rates and must not be confused with order payable amounts. Model dimensions, weight, print duration, stock, material-color quantities, and stock-log quantities are measurements/counts, not VND totals.
- No estimate-create request/response DTO or estimate-writing service was found in current backend Java sources. Identify the actual deployed quote producer and its version before planning a backfill.
- `docs/system-review-2026-11-05.md` specifies VND whole-dong payable amounts, one `HALF_UP` rounding after the final quote, integer order arithmetic, and separate expand/backfill/validate/switch/contract approvals. It does not authorize historical backfill in this phase.

## Read-only PostgreSQL preflight queries

Run only against an isolated QA clone or a disposable fixture after confirming the expected V1–V6 schema. These statements are `SELECT` only. They list evidence for human review; they do not approve a conversion rule, tolerance, or reconciliation action.

### 1. Row counts

```sql
SELECT 'price_estimations' AS table_name, COUNT(*) AS row_count FROM price_estimations
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'order_items', COUNT(*) FROM order_items
UNION ALL SELECT 'payments', COUNT(*) FROM payments;
```

### 2. Null, non-finite, zero, and negative legacy values

This classifies null and IEEE special values before sign/range checks. It includes payable fields, quote components, and the material rate separately.

```sql
WITH values_to_check AS (
    SELECT 'price_estimations' AS table_name, estimate_id::text AS row_id,
           'material_cost' AS field_name, material_cost AS amount FROM price_estimations
    UNION ALL SELECT 'price_estimations', estimate_id::text, 'labor_cost', labor_cost FROM price_estimations
    UNION ALL SELECT 'price_estimations', estimate_id::text, 'total_price', total_price FROM price_estimations
    UNION ALL SELECT 'materials', material_id::text, 'price_per_gram', price_per_gram FROM materials
    UNION ALL SELECT 'orders', order_id::text, 'total_amount', total_amount FROM orders
    UNION ALL SELECT 'order_items', order_item_id::text, 'price_snapshot', price_snapshot FROM order_items
    UNION ALL SELECT 'payments', payment_id::text, 'amount', amount FROM payments
), classified AS (
    SELECT table_name, row_id, field_name,
           CASE
               WHEN amount IS NULL THEN 'NULL'
               WHEN amount::text IN ('NaN', 'Infinity', '-Infinity') THEN 'NON_FINITE'
               WHEN amount = 0 THEN 'ZERO'
               WHEN amount < 0 THEN 'NEGATIVE'
               ELSE 'POSITIVE_FINITE'
           END AS value_class
    FROM values_to_check
)
SELECT table_name, field_name, value_class, COUNT(*) AS row_count
FROM classified
GROUP BY table_name, field_name, value_class
ORDER BY table_name, field_name, value_class;
```

### 3. Fractional payable legacy values

Only finite values enter the numeric conversion. Fractional unit rates and cost components are not listed as payable snapshots here.

```sql
WITH payable_values AS (
    SELECT 'price_estimations' AS table_name, estimate_id::text AS row_id,
           'total_price' AS field_name, total_price AS amount FROM price_estimations
    UNION ALL SELECT 'orders', order_id::text, 'total_amount', total_amount FROM orders
    UNION ALL SELECT 'order_items', order_item_id::text, 'price_snapshot', price_snapshot FROM order_items
    UNION ALL SELECT 'payments', payment_id::text, 'amount', amount FROM payments
), finite_values AS (
    SELECT table_name, row_id, field_name, amount::numeric AS amount_numeric
    FROM payable_values
    WHERE amount IS NOT NULL
      AND amount::text NOT IN ('NaN', 'Infinity', '-Infinity')
)
SELECT table_name, row_id, field_name, amount_numeric
FROM finite_values
WHERE amount_numeric <> trunc(amount_numeric)
ORDER BY table_name, row_id;
```

### 4. Values whose HALF_UP candidate is outside BIGINT range

The query returns candidates only; it never casts a legacy amount or rounded value to `BIGINT`. Range is checked against exact numeric BIGINT bounds after special-value classification.

```sql
WITH payable_values AS (
    SELECT 'price_estimations' AS table_name, estimate_id::text AS row_id,
           'total_price' AS field_name, total_price AS amount FROM price_estimations
    UNION ALL SELECT 'orders', order_id::text, 'total_amount', total_amount FROM orders
    UNION ALL SELECT 'order_items', order_item_id::text, 'price_snapshot', price_snapshot FROM order_items
    UNION ALL SELECT 'payments', payment_id::text, 'amount', amount FROM payments
), finite_values AS (
    SELECT table_name, row_id, field_name, amount::numeric AS amount_numeric
    FROM payable_values
    WHERE amount IS NOT NULL
      AND amount::text NOT IN ('NaN', 'Infinity', '-Infinity')
), rounded_candidates AS (
    SELECT table_name, row_id, field_name, amount_numeric,
           round(amount_numeric) AS half_up_candidate
    FROM finite_values
)
SELECT table_name, row_id, field_name, amount_numeric, half_up_candidate
FROM rounded_candidates
WHERE half_up_candidate < CAST('-9223372036854775808' AS numeric)
   OR half_up_candidate > CAST('9223372036854775807' AS numeric)
ORDER BY table_name, row_id;
```

PostgreSQL `numeric round` uses ties away from zero, consistent with `HALF_UP` for these decimal candidate checks. This query does not establish that legacy provider-settled values may be rounded.

### 5. Invalid order-item quantities and snapshots

```sql
SELECT order_item_id, order_id, quantity, price_snapshot,
       CASE
           WHEN quantity IS NULL OR quantity <= 0 THEN 'INVALID_QUANTITY'
           WHEN price_snapshot IS NULL THEN 'NULL_SNAPSHOT'
           WHEN price_snapshot::text IN ('NaN', 'Infinity', '-Infinity') THEN 'NON_FINITE_SNAPSHOT'
           WHEN price_snapshot <= 0 THEN 'NON_POSITIVE_SNAPSHOT'
       END AS issue
FROM order_items
WHERE quantity IS NULL OR quantity <= 0
   OR price_snapshot IS NULL
   OR price_snapshot::text IN ('NaN', 'Infinity', '-Infinity')
   OR price_snapshot <= 0
ORDER BY order_id, order_item_id;
```

### 6. Order total versus item snapshots × quantity

Invalid item rows are flagged and excluded from candidate summation. Exact numeric deltas are shown for review; no tolerance is applied and no delta is auto-classified as harmless floating-point noise.

```sql
WITH item_rows AS (
    SELECT order_id, order_item_id, quantity, price_snapshot,
           (quantity IS NULL OR quantity <= 0
            OR price_snapshot IS NULL
            OR price_snapshot::text IN ('NaN', 'Infinity', '-Infinity')
            OR price_snapshot <= 0) AS invalid_item
    FROM order_items
), item_totals AS (
    SELECT order_id,
           bool_or(invalid_item) AS has_invalid_item,
           SUM(CASE WHEN NOT invalid_item
                    THEN price_snapshot::numeric * quantity::numeric
               END) AS valid_items_total,
           COUNT(*) AS item_count
    FROM item_rows
    GROUP BY order_id
), finite_orders AS (
    SELECT order_id, total_amount::numeric AS legacy_total
    FROM orders
    WHERE total_amount IS NOT NULL
      AND total_amount::text NOT IN ('NaN', 'Infinity', '-Infinity')
)
SELECT o.order_id, o.legacy_total, i.valid_items_total,
       o.legacy_total - i.valid_items_total AS exact_numeric_delta,
       i.item_count, i.has_invalid_item
FROM finite_orders o
JOIN item_totals i USING (order_id)
WHERE i.has_invalid_item
   OR i.valid_items_total IS NULL
   OR o.legacy_total <> i.valid_items_total
ORDER BY o.order_id;
```

Orders without any `order_items` are separately identified below. A nonzero exact delta is an investigation candidate: compare the original quote, persisted snapshot, and provider settlement evidence. Do not pick an epsilon or overwrite a settled amount in this phase.

### 7. Payment versus order legacy amount

```sql
WITH finite_pairs AS (
    SELECT p.payment_id, p.order_id, p.status, p.method, p.paid_at,
           p.amount::numeric AS payment_amount,
           o.total_amount::numeric AS order_amount
    FROM payments p
    JOIN orders o ON o.order_id = p.order_id
    WHERE p.amount IS NOT NULL
      AND o.total_amount IS NOT NULL
      AND p.amount::text NOT IN ('NaN', 'Infinity', '-Infinity')
      AND o.total_amount::text NOT IN ('NaN', 'Infinity', '-Infinity')
)
SELECT payment_id, order_id, status, method, paid_at,
       payment_amount, order_amount,
       payment_amount - order_amount AS exact_numeric_delta
FROM finite_pairs
WHERE payment_amount <> order_amount
ORDER BY order_id, payment_id;
```

### 8. V6 nullable VND columns versus legacy values

These are HALF_UP **candidate** comparisons, not a backfill rule approval. Exceptional legacy values are excluded from numeric projection, and there is no unsafe cast to `BIGINT`.

```sql
WITH pairs AS (
    SELECT 'price_estimations' AS table_name, estimate_id::text AS row_id,
           total_price AS legacy_amount, total_price_vnd AS vnd_amount
    FROM price_estimations
    UNION ALL SELECT 'orders', order_id::text, total_amount, total_amount_vnd FROM orders
    UNION ALL SELECT 'order_items', order_item_id::text, price_snapshot, price_snapshot_vnd FROM order_items
    UNION ALL SELECT 'payments', payment_id::text, amount, amount_vnd FROM payments
), finite_pairs AS (
    SELECT table_name, row_id,
           CASE WHEN legacy_amount IS NOT NULL
                     AND legacy_amount::text NOT IN ('NaN', 'Infinity', '-Infinity')
                THEN legacy_amount::numeric END AS legacy_numeric,
           vnd_amount
    FROM pairs
)
SELECT table_name, row_id, legacy_numeric, round(legacy_numeric) AS half_up_candidate,
       vnd_amount,
       CASE WHEN legacy_numeric IS NULL THEN 'LEGACY_INVALID_OR_NULL'
            WHEN vnd_amount IS NULL THEN 'VND_NULL'
            WHEN round(legacy_numeric) <> vnd_amount::numeric THEN 'CANDIDATE_MISMATCH'
       END AS issue
FROM finite_pairs
WHERE legacy_numeric IS NULL
   OR vnd_amount IS NULL
   OR round(legacy_numeric) <> vnd_amount::numeric
ORDER BY table_name, row_id;
```

### 9. Successful payments requiring provider-document reconciliation

The current payment schema has no provider transaction ID or callback event ID. This query produces a local worklist only; it cannot prove settlement. Match each row manually to the authoritative provider statement/transaction receipt before deciding a case.

```sql
SELECT p.payment_id, p.order_id, p.method, p.amount, p.amount_vnd,
       p.status, p.paid_at, o.status AS order_status,
       o.total_amount, o.total_amount_vnd
FROM payments p
LEFT JOIN orders o ON o.order_id = p.order_id
WHERE p.status = 'SUCCESS'
ORDER BY p.paid_at NULLS FIRST, p.payment_id;
```

### 10. Missing historical inputs or related records

```sql
SELECT 'ESTIMATE_TOTAL_MISSING' AS issue, estimate_id::text AS row_id
FROM price_estimations WHERE total_price IS NULL
UNION ALL
SELECT 'ESTIMATE_COMPONENTS_INCOMPLETE', estimate_id::text
FROM price_estimations WHERE material_cost IS NULL OR labor_cost IS NULL
UNION ALL
SELECT 'ORDER_WITHOUT_ITEMS', o.order_id::text
FROM orders o LEFT JOIN order_items i ON i.order_id = o.order_id
GROUP BY o.order_id HAVING COUNT(i.order_item_id) = 0
UNION ALL
SELECT 'ORDER_WITHOUT_PAYMENT', o.order_id::text
FROM orders o LEFT JOIN payments p ON p.order_id = o.order_id
GROUP BY o.order_id HAVING COUNT(p.payment_id) = 0
UNION ALL
SELECT 'PAYMENT_WITHOUT_ORDER', p.payment_id::text
FROM payments p LEFT JOIN orders o ON o.order_id = p.order_id
WHERE o.order_id IS NULL
UNION ALL
SELECT 'ORDER_ITEM_ESTIMATE_MISSING', i.order_item_id::text
FROM order_items i LEFT JOIN price_estimations e ON e.estimate_id = i.estimate_id
WHERE e.estimate_id IS NULL
ORDER BY issue, row_id;
```

V1 foreign keys may make some orphan cases impossible in a healthy schema, but the checks are retained to detect disabled constraints, partial imports, or schema drift. `ESTIMATE_COMPONENTS_INCOMPLETE` is not by itself proof that a final payable amount is unrecoverable; inspect quote-generation provenance and any immutable order snapshot first.

### 11. VND-column completeness counts

```sql
SELECT 'price_estimations' AS table_name, COUNT(*) AS total_rows,
       COUNT(total_price_vnd) AS populated_vnd_rows,
       COUNT(*) FILTER (WHERE total_price_vnd IS NULL) AS null_vnd_rows
FROM price_estimations
UNION ALL SELECT 'orders', COUNT(*), COUNT(total_amount_vnd),
       COUNT(*) FILTER (WHERE total_amount_vnd IS NULL) FROM orders
UNION ALL SELECT 'order_items', COUNT(*), COUNT(price_snapshot_vnd),
       COUNT(*) FILTER (WHERE price_snapshot_vnd IS NULL) FROM order_items
UNION ALL SELECT 'payments', COUNT(*), COUNT(amount_vnd),
       COUNT(*) FILTER (WHERE amount_vnd IS NULL) FROM payments;
```

## Reconciliation rules and case handling

1. Preserve original legacy fields and settled provider amounts. Never round a settled payment to make it agree with an order.
2. Classify missing, non-finite, non-positive, fractional, and out-of-range values before considering integer conversion. Do not cast an exceptional or out-of-range value to `BIGINT`.
3. A one-dong or sub-dong numeric delta is not automatically binary floating-point noise. No tolerance is defined here. Compare source quote inputs, stored estimate/order snapshots, prior API/provider units, and provider settlement documents. Record the discrepancy and obtain an explicit business/SYSTEM decision.
4. Compare payment rows with the exact order amount and provider evidence. A `SUCCESS` state alone is insufficient settlement proof; the current schema lacks provider transaction/event identifiers and has no durable reconciliation case or refund-case model.
5. Mark cases with missing quote provenance, missing line snapshots, incomplete provider documentation, or ambiguous historical currency/scale as unresolved. Do not infer a payable amount from current material rates or current pricing formulas.
6. For each approved case, retain source row IDs, original values, proposed integer candidate, calculation rule/version, provider evidence reference, reviewer, decision, and timestamp in a separately approved audit/reconciliation mechanism before any write migration.

No production VND backfill or automatic refund workflow is implemented or authorized here.

## Cutover gates

| Stage | Current status | Gate before advancing |
|---|---|---|
| **EXPAND** | V6 passed local disposable PostgreSQL QA in Phase B; four new VND columns are nullable. | Verify V4–V6 provenance/checksums against the approved release chain before deployment. |
| **BACKFILL** | **Not authorized.** | Complete legacy inventory and case-by-case reconciliation; approve rounding, provider discrepancy, invalid-value, and audit policies. |
| **VALIDATE** | Checks are designed here only; no real data has been certified. | Run approved read-only checks on a protected production snapshot/clone and have owners sign off all exceptions. |
| **SWITCH** | **Not authorized.** | Coordinate compatible old/new application writers and API contracts; implement exact `BigDecimal` quote calculation, single final `HALF_UP`, integer snapshots/totals, exact provider boundaries, and rollback strategy. |
| **CONTRACT** | **Not authorized.** | Prove all deployed application versions no longer read/write legacy `DOUBLE PRECISION`; only then review removal in a separately approved migration. |

### Mandatory stop conditions

Stop and keep `MIGRATION_NEXT_PHASE_BLOCKED` if any of these apply:

- Migration version, source provenance, or checksum conflict.
- Null, non-finite, invalid, non-positive, fractional-policy, or BIGINT overflow value without an approved disposition.
- Any unresolved Order/OrderItem total mismatch.
- Any unresolved Payment/Order mismatch.
- Any provider settlement discrepancy or unverified `SUCCESS` payment.
- Missing source quote, currency/scale, provider receipt, or other information required to determine a historical payable amount.
- No verified backup and restore rehearsal for the target data.
- No tested way to keep legacy and integer writers consistent during a staged cutover or safely roll back.

## Reference arithmetic contract

`MoneyVndArithmeticContractTest` is isolated reference arithmetic. It does not invoke the current order/payment services, gateways, or provider accounts and is not evidence that production has migrated to `long` or `BigDecimal`.

- Complete quote components with decimal arithmetic; apply one `HALF_UP` rounding only after the quote is complete.
- Require the resulting payable unit amount to be positive and exactly representable as Java `long`/PostgreSQL `BIGINT`.
- Multiply unit snapshots by positive integer quantity with `Math.multiplyExact`; accumulate order lines with `Math.addExact`.
- Require exact equality between payment and order integer VND amounts.
- Convert VNPay integer VND to provider units by overflow-checked `amount × 100`; pass MoMo integer VND through without another rounding operation.
- Compare callback amount with the exact provider-specific expected amount.
- Treat a candidate historical rounding discrepancy as a reconciliation case; never mutate a settled value in the reference contract.

## Verification boundary

Arithmetic tests can run as local JUnit tests without a database. The SQL above is intended for PostgreSQL and should be syntax/runtime-checked only on disposable fixtures or a protected non-production clone. Passing either check does not authorize production data access, data modification, backfill, or migration.
