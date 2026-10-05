# Inventix Review (v1, updated for v2 Run 1)

This review covers the code smells, bugs and missing features found while finishing Inventix v1, how each one was
handled, and what is still open. It was updated for v2 Run 1 (persistence, backups, local-only access, response
DTOs and the stock movement ledger).

- **Date:** 2026-10-04
- **Starting point:** `develop` at `94aa41a` (merge of PR #5, `order-orderitem-fix`)
- **Scope:** the Spring Boot backend, its tests, the docs, and the Tauri desktop UI in `desktop/`

> The backend compiles and `gradlew.bat clean test` passes (206 tests), and `npm test --prefix desktop` passes
> (21 tests). The desktop app has not had a first real run yet (see [O-1](#o-1-build-and-tests)).

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

**Status:** backend fixed; desktop app still needs a first real run.
The earlier note that the Java code had never been compiled is out of date. The backend compiles and
`gradlew.bat clean test` passes (206 tests after v2 Run 1). `npm test --prefix desktop` passes (21 tests). The
Tauri app itself has not been built or clicked through against a live backend yet (it needs the icons from O-6).

**Still open:** run `npm run dev` in `desktop/` against `gradlew.bat bootRun` and click through the main flows,
including **Back up now**, a stock adjustment with a note and the History window.

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
users with BCrypt hashes, H2 console off) **before** changing `server.address`.

### O-3. Spring Boot 3.3.x is out of open-source support

**Status:** open.
`build.gradle` uses Spring Boot **3.3.5**. Open-source support for 3.3.x ended on **30 June 2025** (last release
3.3.13), so it no longer gets security fixes. 3.4.x ended 31 December 2025 and 3.5.x ended 30 June 2026. The
supported lines are now 4.0.x and 4.1.x.

**Recommendation:**
- **Next step (low risk):** move to the latest **3.5.x** (3.5.16 at the time of writing). It stays on Spring
  Framework 6 / Jakarta EE 10 and Java 17, so code changes should be small. Bump
  `io.spring.dependency-management` too, then run the full test suite.
- **Later (major migration):** plan a separate move to **Spring Boot 4.x**. Review the official 4.0 migration
  guide first; expect changes around Spring Framework 7, Jackson, Hibernate and Spring Batch. 3.5.x is also out
  of OSS support, so don't stay on it for long.

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

**Status:** partly done.
The new stock movement endpoints are paged (`page`, `size` 1–500, newest first). `GET /api/products` and
`GET /api/orders` still return every row, and each order includes all its items (loaded in the same query
through an `@EntityGraph`). This is fine for one user's data but won't scale.

**Recommendation:** accept `Pageable` (`?page=&size=&sort=`) in the repositories and controllers and return a page
object, and update `docs/API.md` and `desktop/src/js/api.js`. Paging an entity graph that fetches a collection
makes Hibernate page in memory, so page the order ids first and then fetch their items.
Server-side search (name/SKU) would also let the desktop app stop filtering the full list in the browser.

### O-6. Tauri icons must be generated

**Status:** open.
`desktop/src-tauri/tauri.conf.json` lists bundle icons, but the binary icon files aren't in the repo. On Windows
Tauri needs `icon.ico` even in development, so the desktop app won't build until they exist.

**Recommendation:** run `npx tauri icon path\to\logo.png` once in `desktop/` (any square PNG of at least
1024×1024) and commit the generated files in `desktop/src-tauri/icons/`. See `desktop/src-tauri/icons/README.md`.

### O-7. Desktop dev server port not confirmed

**Status:** fixed (the Tauri side still needs a first real run, see O-1).
The dev port is now pinned instead of widening CORS. `tauri.conf.json` sets `build.devUrl` to
`http://127.0.0.1:1420` and `build.beforeDevCommand` to `npm run serve`, which starts
`desktop/scripts/dev-server.js`: a static server for `desktop/src/` with no dependencies. It always listens on
`127.0.0.1:1420`, which is already in the CORS list, and stops with an error if the port is taken rather than
moving to another one. Plain `npm run dev` now works, and the unverified `--no-dev-server` / `--port` flags are no
longer needed. The CORS list is unchanged, so unknown origins are still rejected. `desktop/scripts/dev-server.test.js`
(`npm test` in `desktop/`) checks the server and that its origin matches `devUrl` and `WebConfig.java`.

**Still open:** moving the allowed origins into `application.properties` so they can be changed without a code
change.

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

**Small follow-up:** the comment above `spring.jpa.open-in-view=false` in `application.properties` says DTOs are
built inside the service transaction. They are built in the controllers after commit; the comment should say so.

### O-10. No CI build or test workflow

**Status:** open.
`.github/workflows/codeql.yml` is entirely commented out, so CodeQL scanning doesn't run, and there is no workflow
that builds or tests the project.

**Recommendation:** add a GitHub Actions workflow on pushes and pull requests to `develop` that sets up JDK 17
and runs `./gradlew build`. Re-enable the CodeQL workflow (Java with `build-mode: none` works as written), and
consider adding `javascript-typescript` for `desktop/src/`.

### O-11. No OpenAPI / Swagger UI

**Status:** open.
The old README pointed to `/api/swagger`, which never existed. The API is documented by hand in `docs/API.md`.

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

**Status:** open (risk).
The database file is written by H2 2.2.224 (managed by Spring Boot 3.3.x). H2 has changed its file format between
versions before (2.1 → 2.2 files could not be opened by the other version), and the only supported upgrade path
was a SQL export and re-import. A Spring Boot upgrade (O-3) can bump H2 the same way, and an old `.zip` backup
has the same problem.

**Recommendation:** before any Boot or H2 upgrade, make a backup, then export everything with the old version
(`SCRIPT TO 'inventix.sql'` in the H2 console; this includes Flyway's `flyway_schema_history` table). After the
upgrade, move `data/` aside and run `RUNSCRIPT FROM 'inventix.sql'` against a new, empty database before starting
the app. Try it on a copy first. Check the H2 version in `gradlew.bat dependencies` whenever Boot changes.

### O-14. Restore is manual

**Status:** open.
There is a backup endpoint but no restore endpoint or button. The steps (in the README) are: stop the backend,
move `data/inventix.mv.db` aside, unzip the backup into `data/`, start the backend. Backups are never deleted, so
`./backups` grows until it is cleaned up by hand, and they are kept on the same disk as the database.

**Recommendation:** document copying backups to another drive or cloud folder. Later, consider a restore command
(it needs the app to close the database first), a retention setting, and a scheduled backup.

### O-15. Database location depends on the working directory

**Status:** open.
`./data/inventix` and `./backups` are relative to the folder the backend is started from. Starting it from
another folder (for example a jar run from `Downloads`) silently creates a new empty database there, which is
then seeded with sample data. Only one process can open the file at a time (`inventix.lock.db`), so a second
backend, or an external tool such as a stand-alone H2 console, fails with "database may be already in use".

**Recommendation:** set an absolute path (e.g. under `%LOCALAPPDATA%\Inventix`) when the app is packaged as a
single app (Tauri sidecar), and log the resolved database path on startup.

### O-16. Ledger follow-ups

**Status:** open, for v2 Run 2.
- `ProductService.adjustStock` has an `orderId` parameter but no `purchaseOrderId` yet. Purchase-order receipts
  (`RECEIPT`) and production runs (`PRODUCTION`) need it, plus their own endpoints.
- `orderId` on a movement has no foreign key on purpose, so it can point to a deleted `PENDING` order. The desktop
  History window then shows "Could not load order" when the link is opened.
- Deleting a product also deletes its movements. If history must survive deletes, switch to archiving products
  instead of deleting them.
- `locationId` is always `null` until multi-location stock exists.
- Movements are listed newest first with no date range filter or total count; reports (Run 3) will need both.

## 5. Roadmap

Done in v2 Run 1: persistent storage, backups, local-only access, response DTOs and the stock movement ledger.

Still planned:

- **Run 2:** suppliers and purchase orders (`RECEIPT` movements), production runs (`PRODUCTION` movements), source
  per product (buy or make), per-product reorder point and quantity, and a Restock action
- **Run 3:** categories, unit cost, reports (stock value, movements by date range, top sellers) and CSV export
- **Later:** ship backend and desktop as one app (Tauri sidecar with a bundled Java runtime), multi-location stock,
  demand forecasting
- Role-based access control is not planned while the app has one user on one machine (see O-2)
