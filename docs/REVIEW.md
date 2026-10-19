# Inventix Review

This review covers the code smells, bugs and missing features found while building Inventix, how each one was
handled, and what is still open. It started with v1 and has been updated through v2 Run 3, Spring Boot 4.1, H2
2.4.240, automatic backups (F-25) and server mode (F-26).

- **Last updated:** 2026-10-18
- **Starting point:** `develop` at `94aa41a` (merge of PR #5, `order-orderitem-fix`); current as of `e19b768` (merge of PR #19)
- **Scope:** the Spring Boot backend, its tests, the docs, and the Tauri desktop UI in `desktop/`

> The backend compiles and `./gradlew build` passes (302 tests, on H2 and in CI on PostgreSQL 17), and
> `npm test --prefix desktop` passes (35 tests). The desktop app has not had a first real run yet (see
> [O-1](#o-1-build-and-tests)).

Paths below are relative to `src/main/java/com/example/inventix/` unless they start with `src/`, `docs/`,
`desktop/` or a file at the repository root.

---

## 1. Bugs fixed

| # | Problem | Fix | Files |
|---|---|---|---|
| B-1 | The update and delete order endpoints were mapped to `"/id"` instead of `"/{id}"`, so they never matched a real order id. | All path variables now match their paths. | `controller/OrderController.java` |
| B-2 | The `Order` entity had no `@Table`, so Hibernate tried to create a table called `order`, a reserved word in SQL and H2 2.x. Startup would fail. | `@Table(name = "orders")` on `Order` and `@Table(name = "order_items")` on `OrderItem`. | `model/Order.java`, `model/OrderItem.java` |
| B-3 | `Order` and `OrderItem` referenced each other with no JSON handling, so serializing an order looped forever. | `@JsonManagedReference` on `Order.orderItems`. Items no longer include their order, only an `orderId`. Superseded in v2 by response DTOs (O-9). | `model/Order.java`, `model/OrderItem.java` |
| B-4 | Lombok `@Data` on entities generated `toString`/`equals`/`hashCode` that followed the order↔item links, causing recursion and unstable hashing. | Replaced with `@Getter`/`@Setter`/`@ToString`, with the relationship fields excluded from `toString`. | `model/Product.java`, `model/Order.java`, `model/OrderItem.java` |
| B-5 | `OrderStatus` had no `@Enumerated`, so it was stored as a number and would break if the enum order changed. | `@Enumerated(EnumType.STRING)` plus `@JdbcTypeCode(SqlTypes.VARCHAR)` (a plain text column, not an H2 `ENUM`), defaulting to `PENDING`. | `model/Order.java` |
| B-6 | There was no exception handler, so "not found" and "out of stock" came back as 500. | `GlobalExceptionHandler` maps every error to the right status (400/404/405/409/415/500) and one JSON body. | `exception/GlobalExceptionHandler.java`, `dto/ApiError.java` |
| B-7 | `createOrder` never set a status or `totalAmount`. | New orders are `PENDING` with total 0. The total is recalculated whenever items change. | `service/impl/OrderServiceImpl.java`, `model/Order.java` |
| B-8 | `updateOrder` cleared items without giving their stock back, then added each new item twice. | Gives the old items' stock back, then adds the new items once. Only `PENDING` orders can be updated. | `service/impl/OrderServiceImpl.java` |
| B-9 | Deleting an order changed the item list while looping over it (`ConcurrentModificationException`). | Loops over a copy. Only `PENDING` orders give stock back on delete. | `service/impl/OrderServiceImpl.java` |
| B-10 | Creating a product with an `id` in the body could overwrite an existing product. | `id`, `createdAt` and `updatedAt` are read-only in JSON, and the service ignores a client-sent id. | `model/Product.java`, `service/impl/ProductServiceImpl.java` |
| B-11 | Deleting a product that is on an order failed with a database constraint error (500). | Checked first and returned as 409. | `service/impl/ProductServiceImpl.java`, `repository/OrderItemRepository.java`, `exception/ProductInUseException.java` |
| B-12 | Duplicate SKUs were only caught (if at all) by the database. | Checked on create and update and returned as 409. SKU is also `unique` in the schema. | `service/impl/ProductServiceImpl.java`, `exception/DuplicateSkuException.java`, `model/Product.java` |
| B-13 | Removing an order item relied only on orphan removal, which left a row behind if the item was added and removed in the same transaction. | `deleteOrderItem` also calls `orderItemRepository.delete(...)`. Covered by `removingItem_addedInTheSameTransaction_deletesIt`. | `service/impl/OrderItemServiceImpl.java` |
| B-14 | `application.properties` only set the app name, so the H2 URL, console and Batch behaviour were all defaults. | Explicit H2 URL (`jdbc:h2:mem:inventix`), console at `/h2-console`, port 8080. The unused Spring Batch was later removed (see O-12). v2 switched to a file database (see O-4). | `src/main/resources/application.properties` |
| B-15 | Two requests changing the same product's stock at once could oversell it. | Optimistic locking with `@Version` on `Product`; conflicts return 409 (see O-8). | `model/Product.java`, `service/impl/ProductServiceImpl.java`, `exception/GlobalExceptionHandler.java` |
| B-16 | `npm run dev` could serve the desktop UI on a port outside the CORS list. | Dev server pinned to `127.0.0.1:1420` (see O-7). | `desktop/scripts/dev-server.js`, `desktop/src-tauri/tauri.conf.json`, `desktop/package.json` |
| B-17 | Shipped and delivered orders could be deleted, losing the record of stock that left the warehouse. | Deleting them returns 409; the desktop app hides their Delete button. | `service/impl/OrderServiceImpl.java`, `desktop/src/js/orders.js` |
| B-18 | `adjustStock` added in `int`, so a large restock wrapped around to a negative quantity. | Computed in `long`; going above `Integer.MAX_VALUE` returns 400. | `service/impl/ProductServiceImpl.java` |
| B-19 | Retrying `POST /api/orders` after a timeout could create a second order and reserve stock twice. | Optional `Idempotency-Key` header, stored with a unique constraint (migration `V3`); a repeat returns the first order with 200. The desktop app reuses the key when the same items are resubmitted. | `controller/OrderController.java`, `model/Order.java`, `db/migration/V3__order_idempotency_key.sql`, `desktop/src/js/api.js`, `desktop/src/js/orders.js` |
| B-20 | `" wm-001"` and `"WM-001"` could exist as separate SKUs. | SKUs are trimmed and upper-cased before the duplicate check and save. | `service/impl/ProductServiceImpl.java` |
| B-21 | `GET /api/products/low-stock?threshold=-5` was accepted. | Negative thresholds return 400. | `service/impl/ProductServiceImpl.java` |
| B-22 | Any `IllegalArgumentException`, including ones from libraries, had its message returned to the client. | The services throw the new `BadRequestException`, whose messages are returned; other ones get `"Invalid request"` and are logged. Spring errors without a client-facing detail use the status text. | `exception/BadRequestException.java`, `exception/GlobalExceptionHandler.java` |

## 2. Code smells addressed

| # | Smell | What changed | Files |
|---|---|---|---|
| S-1 | No input validation. `@Valid` was never used. | Bean Validation rules on `Product` and on every request DTO, and `@Valid` on every request body. Errors list each bad field (e.g. `items[0].quantity`). | `model/Product.java`, `dto/*.java`, `controller/*.java` |
| S-2 | Controllers took entities for every request, so clients could set internal fields. | Order, order-item, status and stock endpoints take small request records. Since v2, products take `ProductRequest` too, and every response is a DTO, so no entity is accepted or returned (see [O-9](#o-9-entities-used-as-api-models)). The stock request also requires a `note`. | `dto/OrderRequest.java`, `dto/OrderItemRequest.java`, `dto/QuantityUpdateRequest.java`, `dto/OrderStatusRequest.java`, `dto/StockAdjustmentRequest.java`, `dto/ProductRequest.java` |
| S-3 | No transactions, so a failure halfway through an order could leave stock taken with no order. | Service methods are `@Transactional`. In v1, read-only transactions were left out on purpose because of open-in-view. Since v2 open-in-view is off, and the read-only `StockMovementServiceImpl` uses `@Transactional(readOnly = true)`. | `service/impl/*.java` |
| S-4 | Status changes had no rules; any status could become any other. | `OrderStatus.canTransitionTo`: `PENDING → SHIPPED/CANCELLED`, `SHIPPED → DELIVERED`; `DELIVERED` and `CANCELLED` are final. Other changes return 409. | `model/OrderStatus.java`, `service/impl/OrderServiceImpl.java`, `exception/InvalidOrderStateException.java` |
| S-5 | Create endpoints returned no `Location` header. | Creates return 201 with a `Location` header; deletes return 204. | `controller/*.java` |
| S-6 | Order totals and item prices weren't kept consistent with each other. | `BigDecimal` columns with explicit precision and scale (2 decimals). The price is captured per item (`pricePerUnit`) so later price changes don't rewrite old orders. | `model/Product.java`, `model/Order.java`, `model/OrderItem.java` |
| S-7 | README was out of date: it claimed JDK 11, pointed to a nonexistent `/api/swagger`, used the wrong clone URL (`inventix.git`) and ended with leftover AI-assistant text. | Rewritten: Java 17, correct clone URL, `develop` branch, real commands, H2 details, API summary and limitations. | `README.md` |
| S-8 | No API documentation. | Full contract with JSON shapes, rules, error codes and examples. | `docs/API.md` |
| S-9 | `.gitignore` didn't cover the desktop app's build output. | Added `desktop/node_modules/` and `desktop/src-tauri/target/` (the root file already covered `build/`, `.gradle`, `.idea` and `*.iml`). | `.gitignore` |

## 3. Features implemented

| # | Feature | Files |
|---|---|---|
| F-1 | Order item endpoints: list, add, change quantity, remove (`/api/orders/{orderId}/items[/{itemId}]`). The service supported these, but no endpoint did. | `controller/OrderController.java`, `service/impl/OrderItemServiceImpl.java` |
| F-2 | Order status endpoint `PATCH /api/orders/{id}/status`, and `GET /api/orders?status=` filtering. | `controller/OrderController.java`, `repository/OrderRepository.java` |
| F-3 | Stock control: reserve on add, return on remove, reduce or cancel; `PATCH /api/products/{id}/stock`; never below zero. | `service/impl/ProductServiceImpl.java`, `service/impl/OrderItemServiceImpl.java`, `exception/InsufficientStockException.java` |
| F-4 | Low-stock report `GET /api/products/low-stock?threshold=` (default 10). | `controller/ProductController.java`, `repository/ProductRepository.java` |
| F-5 | CORS for the Tauri app (`http://tauri.localhost`, `https://tauri.localhost`, `tauri://localhost`) and local dev servers (ports 1420 and 5500). | `config/WebConfig.java` |
| F-6 | Sample data: five products on startup, switchable with `inventix.seed-data`. | `config/DataSeeder.java` |
| F-7 | Desktop UI (Tauri 2, vanilla HTML/CSS/JS): dashboard, products, orders and settings, with backend errors shown inline. | `desktop/src/`, `desktop/src-tauri/`, `desktop/README.md` |
| F-8 | Tests for all of the above (see below). | `src/test/java/com/example/inventix/` |
| F-9 | **v2:** H2 file database with Flyway migrations and `ddl-auto=validate`; sample data only on first start (see O-4). | `src/main/resources/application.properties`, `src/main/resources/db/migration/`, `config/DataSeeder.java` |
| F-10 | **v2:** `POST /api/backup` writes an H2 `BACKUP TO` zip to `./backups`; **Back up now** button in desktop Settings. | `controller/BackupController.java`, `service/impl/BackupServiceImpl.java`, `dto/BackupResponse.java`, `desktop/src/js/main.js` |
| F-11 | **v2:** Stock movement ledger (`stock_movements`). Every stock change writes a row in the same transaction: `SALE`, `CANCEL`, `ADJUSTMENT` (note required); `RECEIPT` and `PRODUCTION` are reserved for Run 2. Read with `GET /api/products/{id}/movements` and `GET /api/stock-movements`. Desktop: note field on the stock dialog and a History window per product. | `model/StockMovement.java`, `model/MovementReason.java`, `service/impl/ProductServiceImpl.java`, `controller/StockMovementController.java`, `V2__stock_movements.sql`, `desktop/src/js/products.js` |
| F-12 | **v2:** Server binds to `127.0.0.1` only. No authentication, by design (see O-2). | `src/main/resources/application.properties` |
| F-13 | **v2 Run 2:** Suppliers (`/api/suppliers`) and per-product sourcing: `source` (`BUY`/`MAKE`), `supplierId`, `reorderPoint`, `reorderQuantity` (migration `V4`). A supplier used by products or purchase orders can't be deleted. | `model/Supplier.java`, `model/ProductSource.java`, `service/impl/SupplierServiceImpl.java`, `controller/SupplierController.java`, `V4__suppliers_and_sourcing.sql` |
| F-14 | **v2 Run 2:** Purchase orders with partial receipts (`/api/purchase-orders`, migration `V5`). `DRAFT` → `ORDERED` → `PARTIALLY_RECEIVED` → `RECEIVED`, plus `CLOSED` and `CANCELLED`. Receipts never exceed what is outstanding, are checked as a whole before booking, write `RECEIPT` movements with `purchaseOrderId`, and always bump the order's version so two receipts at once conflict instead of double-booking. | `model/PurchaseOrder*.java`, `service/impl/PurchaseOrderServiceImpl.java`, `controller/PurchaseOrderController.java`, `V5__purchase_orders.sql` |
| F-15 | **v2 Run 2:** Production runs (`/api/production-runs`, migration `V6`). `PLANNED` → `COMPLETED` adds stock with a `PRODUCTION` movement (`productionRunId`); runs don't consume components. | `model/ProductionRun*.java`, `service/impl/ProductionRunServiceImpl.java`, `controller/ProductionRunController.java`, `V6__production_runs.sql` |
| F-16 | **v2 Run 2:** Reorder list (`GET /api/reorder-list`, with quantity on order and what is missing) and Restock (`POST /api/products/{id}/restock`, `POST /api/reorder-list/restock`): adds to the supplier's draft purchase order or plans a production run, reusing an existing draft or planned run. | `service/impl/RestockServiceImpl.java`, `controller/RestockController.java` |
| F-17 | **v2 Run 2:** Desktop Suppliers, Purchase orders (draft editor, receive dialog) and Production views; Restocking section in the product form; Reorder card with Restock and Restock all on the dashboard; history links to purchase orders and runs. | `desktop/src/js/suppliers.js`, `desktop/src/js/purchase-orders.js`, `desktop/src/js/production.js`, `desktop/src/js/dashboard.js`, `desktop/src/js/products.js` |
| F-18 | **v2 Run 3:** Categories (`/api/categories`, migration `V7`) and a `unitCost` per product; receiving a purchase order line with a cost updates the product's cost. Products can be filtered by `categoryId`. | `model/Category.java`, `service/impl/CategoryServiceImpl.java`, `controller/CategoryController.java`, `V7__categories_and_unit_cost.sql` |
| F-19 | **v2 Run 3:** Reports (`/api/reports/stock-value`, `/movements`, `/top-sellers`) over whole days (`DateRange`), and `from`/`to` plus `X-Total-Count` on `GET /api/stock-movements`. | `service/impl/ReportServiceImpl.java`, `controller/ReportController.java`, `service/DateRange.java` |
| F-20 | **v2 Run 3:** CSV export (`/api/export/*.csv`): UTF-8 with BOM, RFC 4180 quoting, formula-like text defused. Desktop Reports view with downloads; category filter, Categories dialog and unit cost in Products. | `export/CsvWriter.java`, `controller/ExportController.java`, `desktop/src/js/reports.js`, `desktop/src/js/products.js` |
| F-21 | **Run 2 follow-ups:** bills of materials (`/api/products/{id}/components`, migration `V8`) with loop checks; completing a run uses components up (`CONSUMPTION`), all or nothing. Cancelling the rest of one PO line (`quantity_cancelled`, `cancel-remaining`), with orders settling as `CLOSED` or `CANCELLED`. Desktop Components editor, completion preview with shortages, "Cancel rest" and a printable purchase order. | `model/ProductComponent.java`, `service/impl/BillOfMaterialsServiceImpl.java`, `service/impl/ProductionRunServiceImpl.java`, `service/impl/PurchaseOrderServiceImpl.java`, `V8__po_line_cancel_and_components.sql`, `desktop/src/js/purchase-orders.js`, `desktop/src/js/production.js`, `desktop/src/js/products.js` |
| F-22 | **Ops:** per-user data folder with a one-time copy from `./data` (O-15), and restore on next start from Settings or `POST /api/backup/restore` (O-14). `GET /api/backup` lists backups and folders. | `config/DataLocation.java`, `config/DataLocationPostProcessor.java`, `META-INF/spring.factories`, `service/impl/BackupServiceImpl.java`, `controller/BackupController.java`, `desktop/src/js/main.js` |
| F-23 | **Platform:** Spring Boot 4.1.1 and Gradle 9.8.1 (O-3). | `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`, test imports, `config/DataLocationPostProcessor.java` |
| F-24 | **H2 2.4.240** (O-13): pinned to the version Boot 4.1 manages, after checking that 2.2.224 files and backups open unchanged. | `build.gradle` |
| F-26 | **Server mode** (O-2): `SPRING_PROFILES_ACTIVE=server` uses PostgreSQL with the same Flyway migrations, and turns off the per-user data folder, H2 console, zip backups (409) and sample data. Nullable date parameters in queries are cast, because PostgreSQL can't infer the type of `? is null` for `java.time` values. CI runs the tests against PostgreSQL 17 too. Still on `127.0.0.1` until sign-in exists; no data migration from H2 yet. | `application-server.properties`, `service/impl/BackupServiceImpl.java`, `repository/StockMovementRepository.java`, `repository/OrderItemRepository.java`, `build.gradle`, `.github/workflows/ci.yml`, `ServerModeTests` |
| F-25 | **Automatic backups** (O-14): `inventix-auto-<timestamp>.zip` when the newest is older than `inventix.backup.auto.interval` (24h, checked a minute after start and hourly) and on shutdown; only the newest `inventix.backup.auto.keep` (14) are kept, and Back up now files are never deleted. A failed backup is logged and retried at the next check. Pointing `INVENTIX_BACKUP_DIR` at a synced folder takes the copies off the machine. | `config/AutomaticBackups.java`, `service/impl/BackupServiceImpl.java`, `application.properties` |

**Tests added or updated** (`src/test/java/com/example/inventix/`):
`controller/ProductControllerTest`, `controller/OrderControllerTest` (new), `service/ProductServiceTest`,
`service/OrderServiceTest`, `service/OrderItemServiceTest` (new), `service/OrderStockIntegrationTest` (new, real
services on H2), `repository/OrderRepositoryTest` (new), and `InventixApplicationTests` (full-app end-to-end HTTP
flow). `repository/ProductRepositoryTest` is unchanged.

**v2 Run 1 tests added:** `config/DataSeederTest`, `controller/BackupControllerTest`, `service/BackupServiceTest`
(real H2 file database under `build/`), `service/OrderResponseMappingTest`, `repository/StockMovementRepositoryTest`,
`service/StockMovementServiceTest`, `controller/StockMovementControllerTest`, ledger tests in
`service/OrderStockIntegrationTest`, and `desktop/test/helpers.test.mjs` and `desktop/test/api.test.mjs`. Tests use
an in-memory H2 database from `src/test/resources/application.properties`, with Flyway and `validate`, so they
never touch `./data`.

---

## 4. Still open

Ordered roughly by priority.

### O-1. Build and tests

**Status:** backend fixed; the desktop app has had a first real run on Linux, Windows is still to do.
The earlier note that the Java code had never been compiled is out of date. The backend compiles and
`./gradlew build` passes (302 tests). `npm test --prefix desktop` passes (35 tests). The
Tauri app itself has not been built or clicked through against a live backend yet (it needs the icons from O-6).

*First real run (Linux, 2026-10-18):* the debug build of the Tauri window, against the backend jar, showed the
dashboard as Connected with the sample products, **Back up now** in Settings wrote a `.zip`, and a stock
adjustment of +5 with the note "Recount" on the dashboard's low-stock list changed the stock and wrote an
`ADJUSTMENT` movement with that note.

**Still open:** the same run on Windows (`npm run dev` in `desktop\` against `gradlew.bat bootRun`), including the
History window and a release build with `npm run build`.

**v1 at-risk tests** (reviewed by reading the code before the first compile; all now pass):

| Test | Result | Notes |
|---|---|---|
| `controller/OrderControllerTest` 400 test expecting `items[0].quantity` | *Already correct, no change.* | `OrderRequest` declares `List<@NotNull @Valid OrderItemRequest> items` and the controller uses `@Valid @RequestBody`, so Spring reports `items[0].quantity`. One unchecked assumption: Spring's bean wrapper reads the rejected value through the record accessors. If it can't, the test gets 500, not 400. |
| `repository/OrderRepositoryTest` native SQL status check | *Fixed.* | Hibernate 6.5 may map `@Enumerated(STRING)` to a native H2 `ENUM` column. `Order.status` now also has `@JdbcTypeCode(SqlTypes.VARCHAR)`, so the column is always `varchar(20)`. |
| `controller/ProductControllerTest` CORS test expecting 403 for an unknown origin | *Already correct, made sturdier.* | Spring's `DefaultCorsProcessor` returns 403 for an origin that isn't allowed. Added `@Import(WebConfig.class)` so the test doesn't rely on component scanning, and it now also checks that no `Access-Control-Allow-Origin` header is sent. |
| `service/ProductServiceTest` mocks vs the new `OrderItemRepository` constructor argument | *Already correct, no change.* | Both constructor arguments have a `@Mock`, and the delete tests stub `existsByProductId` for both cases. |

### O-2. No authentication or authorization

**Status:** resolved by design (v2).
Inventix has one user on one machine, so v2 binds the server to `127.0.0.1` (`server.address` in
`application.properties`) instead of adding a login. Other machines can't reach the API or the H2 console. The
unused drafts were deleted: `config/SecurityConfig.java` (hard-coded passwords, wrong `/admin/**` and `/user/**`
paths) and `model/Role.java`. The commented-out security lines were removed from `build.gradle`. CORS is unchanged.

**Remaining risk:** any program or user account on the same machine can still call the API and open
`/h2-console`. If the app is ever shared or moved to a server, add authentication (Spring Security for `/api/**`,
users with BCrypt hashes, H2 console off) **before** changing `server.address`. *Update:* moving to a hosted backend is now planned. Server mode (F-26) is the first step and keeps `127.0.0.1`; sign-in comes next, then deployment, then moving existing data.

### O-3. Spring Boot 3.3.x is out of open-source support

**Status:** fixed. `build.gradle` uses Spring Boot **4.1.1** (Spring Framework 7, Hibernate 7.4, Jackson 3, Flyway 12)
with Gradle **9.8.1**, by way of 3.5.16. Boot 4 changes made here:

- Modular starters: `spring-boot-starter-webmvc` instead of `-web`, `spring-boot-starter-flyway` (Flyway's
  auto-configuration is no longer in the core module), `spring-boot-h2console` for `/h2-console`, and the
  `spring-boot-starter-webmvc-test` / `-data-jpa-test` test starters.
- Test slice packages moved (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`,
  `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `org.springframework.boot.jpa.test.autoconfigure.TestEntityManager`).
- `EnvironmentPostProcessor` moved to `org.springframework.boot` (and its `spring.factories` key with it).
- Jackson 3: the test's `ObjectMapper` is `tools.jackson.databind.ObjectMapper`. The annotations used in the app
  (`com.fasterxml.jackson.annotation.*`) are the same in Jackson 3.
- springdoc 3.1.x (the line for Boot 4.1).

H2 moved to 2.4.240 afterwards (F-24, O-13).

**Was:** `build.gradle` used Spring Boot 3.3.5, out of open-source support since June 2025.

### O-4. H2 in-memory database only

**Status:** fixed (v2), with an H2 file database instead of PostgreSQL (one user, one machine).
- The datasource is `jdbc:h2:file:./data/inventix`, so data is kept in `./data/inventix.mv.db` relative to the
  folder the backend is started from.
- **Flyway** owns the schema: `db/migration/V1__initial_schema.sql` (products, orders, order items) and
  `V2__stock_movements.sql`. `ddl-auto` is `validate`, so Hibernate only checks that the entities match. Enum
  columns are plain `VARCHAR` and `LocalDateTime` columns are `TIMESTAMP(6)` so validation passes.
- `DataSeeder` adds sample products only when there are no products (first start).
- Backups: `POST /api/backup` (see F-10). Restore is manual (see O-14).
- Tests use in-memory H2 with the same migrations (`src/test/resources/application.properties`).

See O-13 and O-15 for the risks that come with a file database.

### O-5. No pagination or sorting

**Status:** fixed. `GET /api/products` takes `q` (name, SKU or description), `sort` (whitelisted fields,
`,asc`/`,desc`), `page` and `size`; `GET /api/orders` takes `status`, `page` and `size` and returns newest first.
Both set `X-Total-Count` (exposed through CORS), and without `page`/`size` they still return every row, so
existing callers are unchanged. Orders are paged by id first and then loaded with their items in one query
(`OrderRepository.findIds` + `findByIdIn`), so Hibernate never pages a collection fetch in memory. The desktop
Products and Orders lists now load 50 rows at a time with a "Load more" button, and product search runs on the
backend. The dashboard and the order product pickers still load the full lists. Tests:
`service/SearchIntegrationTest`, plus the controller and desktop `api.test.mjs` tests.

**Was:** the new stock movement endpoints were paged (`page`, `size` 1–500, newest first). `GET /api/products` and
`GET /api/orders` still return every row, and each order includes all its items (loaded in the same query
through an `@EntityGraph`). This is fine for one user's data but won't scale.

**Recommendation:** accept `Pageable` (`?page=&size=&sort=`) in the repositories and controllers and return a page
object, and update `docs/API.md` and `desktop/src/js/api.js`. Paging an entity graph that fetches a collection
makes Hibernate page in memory, so page the order ids first and then fetch their items.
Server-side search (name/SKU) would also let the desktop app stop filtering the full list in the browser.

### O-6. Tauri icons must be generated

**Status:** fixed. The icons are committed in `desktop/src-tauri/icons/`, made from `.github/assets/logo.svg` with
`npx tauri icon`, so a fresh clone builds without that step. CI's `Desktop shell compiles` job runs `cargo check`
on the shell, which also reads `tauri.conf.json` and the icons, and `Cargo.lock` is committed so it builds the
same crates every time.

**Was:** `desktop/src-tauri/tauri.conf.json` listed bundle icons, but the binary icon files weren't in the repo. On
Windows Tauri needs `icon.ico` even in development, so the desktop app wouldn't build until they existed.

### O-7. Desktop dev server port not confirmed

**Status:** fixed (the Tauri side still needs a first real run, see O-1).
The dev port is now pinned instead of widening CORS. `tauri.conf.json` sets `build.devUrl` to
`http://127.0.0.1:1420` and `build.beforeDevCommand` to `npm run serve`, which starts
`desktop/scripts/dev-server.js`: a static server for `desktop/src/` with no dependencies. It always listens on
`127.0.0.1:1420`, which is already in the CORS list, and stops with an error if the port is taken rather than
moving to another one. Plain `npm run dev` now works, and the unverified `--no-dev-server` / `--port` flags are no
longer needed. The CORS list is unchanged, so unknown origins are still rejected. `desktop/scripts/dev-server.test.js`
(`npm test` in `desktop/`) checks the server and that its origin matches `devUrl` and `WebConfig.java`.

**Also fixed:** the allowed origins can be replaced without a code change through `inventix.cors.allowed-origins`
(or `INVENTIX_CORS_ALLOWED_ORIGINS`); the list in `WebConfig.java` is the default. Covered by
`config/WebConfigOriginsPropertyTest`.

### O-8. No protection against concurrent stock updates

**Status:** fixed.
`Product` now has `@Version private Long version` (optimistic locking). Every stock change, including the ones
made by orders, increments it, and a transaction that commits a change based on a stale read fails instead of
overselling. `GlobalExceptionHandler` maps `OptimisticLockingFailureException` (including
`ObjectOptimisticLockingFailureException`) and `jakarta.persistence.OptimisticLockException` to 409 with "The data
was changed by another request at the same time. Reload it and try again." `createProduct` clears a client-sent
`id` and `version`. `PUT /api/products/{id}` accepts an optional `version` and returns 409 if it is stale, and the
desktop edit dialog sends it. Lombok `@AllArgsConstructor` was replaced with an explicit constructor without the
version, so existing callers compile unchanged. Tests: `controller/ProductOptimisticLockTest`,
`service/ProductServiceVersionTest`, `repository/ProductVersionRepositoryTest`.

**Note:** a conflict is not retried automatically. Under heavy contention for one product, clients will see 409s
and must retry.

### O-9. Entities used as API models

**Status:** fixed (v2).
Controllers now take and return records only: `ProductRequest`, `ProductResponse`, `OrderResponse` and
`OrderItemResponse` in `dto/`. The Jackson annotations on the entities are gone. Order items are flattened to
`productId`, `productName` and `productSku` instead of embedding the whole product.

Services still return entities, and each controller maps them with the DTO's static `from(...)` after the service
transaction has committed, so the response shows the `version` and `updatedAt` written on flush. `OrderRepository`
loads orders together with their items and products (`@EntityGraph` on `findById`, `findAll` and `findByStatus`),
so nothing is lazy-loaded during mapping and `spring.jpa.open-in-view=false` is safe. Stock movements use the same
approach (`StockMovementResponse`, product fetched in the query). Tests: `service/OrderResponseMappingTest` (maps
outside any transaction) and `InventixApplicationTests` (runs with open-in-view off).

*Done:* the comment above `spring.jpa.open-in-view=false` in `application.properties` used to say DTOs are built
inside the service transaction; it now says they are built in the controllers after commit, from data the services
load up front.

### O-10. No CI build or test workflow

**Status:** fixed. `.github/workflows/ci.yml` runs `./gradlew build` on JDK 17 and the desktop `npm test` for
pushes and pull requests to `develop`, and uploads the test report when the build fails.
CodeQL is covered by GitHub's code scanning default setup, which is enabled for the repository and scans Java,
JavaScript/TypeScript, Rust (the Tauri shell) and the workflows themselves, so the old commented-out
`codeql.yml` was removed (an advanced workflow can't upload results while default setup is on). `gradlew` is
now executable in git so the runner can call it. To block merges on failures, mark the `CI` checks as required in the branch protection for `develop`.

**Was:** `.github/workflows/codeql.yml` was entirely commented out, so CodeQL scanning doesn't run, and there is no workflow
that builds or tests the project.

**Recommendation:** add a GitHub Actions workflow on pushes and pull requests to `develop` that sets up JDK 17
and runs `./gradlew build`. Re-enable the CodeQL workflow (Java with `build-mode: none` works as written), and
consider adding `javascript-typescript` for `desktop/src/`.

### O-11. No OpenAPI / Swagger UI

**Status:** fixed. `springdoc-openapi-starter-webmvc-ui` 3.1.1 (the line for Spring Boot 4.1; it was 2.8.17 on Boot 3.5) serves the
generated description at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`, with the title set in
`config/OpenApiConfig.java`. Like the API, they are only reachable from this machine. `docs/API.md` stays the
reference for the behaviour rules. Covered by two tests in `InventixApplicationTests`.

**Was:** the old README pointed to `/api/swagger`, which never existed. The API is documented by hand in `docs/API.md`.

**Recommendation:** add `springdoc-openapi-starter-webmvc-ui` (pick the version that matches the Spring Boot
version) to serve `/swagger-ui.html` and `/v3/api-docs`, and keep `docs/API.md` for the behaviour rules.

### O-12. Smaller items

- **Unused dependencies.** *Fixed:* Spring Batch (`spring-boot-starter-batch`, `spring-batch-test`) and its
  `spring.batch.*` properties were removed, because no job existed. Add it back if a real job (e.g. a nightly
  low-stock report) is written. *v2:* `model/Role.java` and `config/SecurityConfig.java` were deleted (see O-2).
- **Currency.** The API has no currency field, and the desktop app shows every amount as USD.
- **`Location` header in docs.** *Fixed:* `docs/API.md` now describes `Location` as an absolute URL (e.g.
  `http://localhost:8080/api/products/6`), which is what `ServletUriComponentsBuilder.fromCurrentRequest()` produces.
- **Duplicate lines.** Adding a product that is already on an order creates a second line instead of increasing
  the quantity. This is documented; decide whether to merge lines.
- **Timestamps.** Entity timestamps are `LocalDateTime` without a zone. Consider `Instant` or `OffsetDateTime`
  once the app runs on more than one machine.
- **Audit trail.** *Fixed (v2):* every stock change writes a `stock_movements` row (see F-11).
- **Desktop tests.** *Partly fixed (v2):* `desktop/test/` covers the pure helpers and the request shapes in
  `api.js`. The DOM code in `products.js`, `orders.js` and `main.js` still has no automated tests.

### O-13. H2 file format changes on upgrade

**Status:** done for 2.4.240; stays a risk for later upgrades.
`build.gradle` pins H2 to 2.4.240, the version Spring Boot 4.1 manages (it was 2.2.224). Before the change it was
checked with the real app, not only the tests: H2 2.4.240 opened a database written by 2.2.224 with every
product, movement and purchase order as before and could write to it, a backup `.zip` made by 2.2.224 restored
into the new version unchanged, and 2.2.224 could still open the file afterwards. So no export or re-import was
needed for this step.

H2 has changed its file format before (2.1 and 2.2 couldn't open each other's files), and the supported way
across such a change is a SQL export and re-import. That is why the version stays pinned rather than left to
Spring Boot: a Boot upgrade can't change it unnoticed.

**Recommendation:** before changing `h2.version`, open a copy of a real database and an old backup with the new
version the same way. If it can't open them: make a backup, export with the old version (`SCRIPT TO
'inventix.sql'` in the H2 console; this includes Flyway's `flyway_schema_history` table), move `data/` aside after
the upgrade and run `RUNSCRIPT FROM 'inventix.sql'` against a new, empty database before starting the app. Old
backups need the same treatment before they can be restored.

### O-14. Restore is manual

**Status:** fixed. Settings lists the backups; **Restore…** (or `POST /api/backup/restore`) writes
`restore-pending.txt` in the data folder, and on the next start `DataLocationPostProcessor` puts the backup's
database in place before the database is opened, keeping the replaced file as
`inventix-before-restore-<timestamp>.mv.db`. A backup without a database, or any error, leaves the current
database as it was and clears the request. Covered by `BackupServiceTest` (full round trip on a real H2 file) and
`DataLocationTest`.

**Scheduled backups and retention:** fixed by F-25. Backups leave the machine only if `INVENTIX_BACKUP_DIR` is a synced folder; uploading to storage the business controls (S3, Backblaze B2) is still open.

### O-15. Database location depends on the working directory

**Status:** fixed. `inventix.home` defaults to a per-user folder (`%LOCALAPPDATA%\Inventix`,
`~/Library/Application Support/Inventix`, `~/.local/share/inventix`), with the database in `{home}/data` and backups in
`{home}/backups`, wherever the backend is started from. `INVENTIX_HOME` and `INVENTIX_DATA_DIR` override it. The
first start copies a database from the old `./data` folder when the new folder has none (the original is left in
place), and the resolved path is logged and shown in Settings (`GET /api/backup`).

### O-16. Ledger follow-ups

**Status:** partly done in v2 Run 2.
- *Done in Run 2:* `ProductService.changeStock` takes a `StockReference` (order, purchase order or production
  run), and receipts and production runs write `RECEIPT` and `PRODUCTION` movements through it.
- `orderId` on a movement has no foreign key on purpose, so it can point to a deleted `PENDING` order. The desktop
  History window then shows "Could not load order" when the link is opened.
- Deleting a product also deletes its movements. If history must survive deletes, switch to archiving products
  instead of deleting them.
- `locationId` is always `null` until multi-location stock exists.
- *Done in Run 3:* movements can be filtered by a range of days and return a total count.

### O-17. Gaps found in the October 2026 review

**Status:** open. Found by reading `develop` at `e19b768`; sign-in, deployment and moving data to PostgreSQL are
already planned (O-2) and left out here.

- **Orders have no customer.** An order is a status, a total and items, with no name, contact or address, so
  there is no way to tell who it is for. Needs a migration.
- **No returns after delivery.** Stock only comes back by cancelling a pending order, so a customer return is a
  manual adjustment and reports can't tell it from a correction.
- **No stocktake.** Counting the shelf means one manual adjustment per product.
- **No packing slip or invoice for customer orders.** Only purchase orders print.
- **No tax, discount or shipping on orders.** The total is the sum of the items. Needs a migration.
- **Deleting a product deletes its stock history** (see O-16). Archiving instead needs a migration.
- **CI doesn't compile the Tauri shell**, and the icons it needs aren't committed (O-6).
- **Running it:** no health endpoint, logs only go to the console, and there are no published builds, code signing
  or updates.

Changes that touch the database or existing data each get their own pull request.

## 5. Roadmap

Done in v2 Run 1: persistent storage, backups, local-only access, response DTOs and the stock movement ledger.
Done in v2 Run 2: suppliers, purchase orders with partial receipts, production runs, buy/make source, reorder
points and the Restock action (F-13 to F-17).
Done in v2 Run 3: categories, unit cost, reports and CSV export (F-18 to F-20).

Still planned:

- **Run 2 follow-ups still open:** emailing a purchase order straight to the supplier (it can be printed or saved
  as PDF from the print dialog), and planning component purchases from production runs
- **Run 3 follow-ups:** charts on the Reports view, a time zone setting for date ranges, costs per receipt (average
  cost) instead of last purchase cost
- **Later:** ship backend and desktop as one app (Tauri sidecar with a bundled Java runtime), multi-location stock,
  demand forecasting
- Role-based access control is not planned while the app has one user on one machine (see O-2)
