# Inventix v1 Review

This review covers the code smells, bugs and missing features found while finishing Inventix v1, how each one was
handled, and what is still open.

- **Date:** 2026-10-04
- **Starting point:** `develop` at `94aa41a` (merge of PR #5, `order-orderitem-fix`)
- **Scope:** the Spring Boot backend, its tests, the docs, and the new Tauri desktop UI in `desktop/`

> **Important:** none of the Java v1 changes have been compiled or run yet. They were checked by reading the code
> only. Run `./gradlew test` (or `gradlew.bat test`) from inside `inventory-manager/` and the desktop app before
> relying on them (see [O-1](#o-1-build-and-tests-not-yet-run)).

Paths below are relative to `src/main/java/com/example/inventix/` unless they start with `src/`, `docs/`,
`desktop/` or a file at the repository root.

---

## 1. Bugs fixed

| # | Problem | Fix | Files |
|---|---|---|---|
| B-1 | The update and delete order endpoints were mapped to `"/id"` instead of `"/{id}"`, so they never matched a real order id. | All path variables now match their paths. | `controller/OrderController.java` |
| B-2 | The `Order` entity had no `@Table`, so Hibernate tried to create a table called `order`, a reserved word in SQL and H2 2.x. Startup would fail. | `@Table(name = "orders")` on `Order` and `@Table(name = "order_items")` on `OrderItem`. | `model/Order.java`, `model/OrderItem.java` |
| B-3 | `Order` and `OrderItem` referenced each other with no JSON handling, so serializing an order looped forever. | `@JsonManagedReference` on `Order.orderItems`. Items no longer include their order, only an `orderId`. | `model/Order.java`, `model/OrderItem.java` |
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
| B-14 | `application.properties` only set the app name, so the H2 URL, console and Batch behaviour were all defaults. | Explicit H2 URL (`jdbc:h2:mem:inventix`), console at `/h2-console`, port 8080. The unused Spring Batch was later removed (see O-12). | `src/main/resources/application.properties` |
| B-15 | Two requests changing the same product's stock at once could oversell it. | Optimistic locking with `@Version` on `Product`; conflicts return 409 (see O-8). | `model/Product.java`, `service/impl/ProductServiceImpl.java`, `exception/GlobalExceptionHandler.java` |
| B-16 | `npm run dev` could serve the desktop UI on a port outside the CORS list. | Dev server pinned to `127.0.0.1:1420` (see O-7). | `desktop/scripts/dev-server.js`, `desktop/src-tauri/tauri.conf.json`, `desktop/package.json` |

## 2. Code smells addressed

| # | Smell | What changed | Files |
|---|---|---|---|
| S-1 | No input validation. `@Valid` was never used. | Bean Validation rules on `Product` and on every request DTO, and `@Valid` on every request body. Errors list each bad field (e.g. `items[0].quantity`). | `model/Product.java`, `dto/*.java`, `controller/*.java` |
| S-2 | Controllers took entities for every request, so clients could set internal fields. | Order, order-item, status and stock endpoints now take small request records. Products still take the entity (see [O-9](#o-9-entities-used-as-api-models)). | `dto/OrderRequest.java`, `dto/OrderItemRequest.java`, `dto/QuantityUpdateRequest.java`, `dto/OrderStatusRequest.java`, `dto/StockAdjustmentRequest.java` |
| S-3 | No transactions, so a failure halfway through an order could leave stock taken with no order. | Service methods are `@Transactional`. Read-only transactions were left out on purpose (with open-in-view, a read-only session would silently ignore later writes in the same request). | `service/impl/*.java` |
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

**Tests added or updated** (`src/test/java/com/example/inventix/`):
`controller/ProductControllerTest`, `controller/OrderControllerTest` (new), `service/ProductServiceTest`,
`service/OrderServiceTest`, `service/OrderItemServiceTest` (new), `service/OrderStockIntegrationTest` (new, real
services on H2), `repository/OrderRepositoryTest` (new), and `InventixApplicationTests` (full-app end-to-end HTTP
flow). `repository/ProductRepositoryTest` is unchanged.

---

## 4. Still open

Ordered roughly by priority.

### O-1. Build and tests not yet run

**Status:** open, highest priority. The four at-risk tests were reviewed and fixed (see below), but nothing has
been compiled yet.
None of the v1 backend or Java test changes have been compiled or run on this machine. The tasks that wrote them
could not run Gradle or git. Only the desktop dev-server tests (`npm test` in `desktop/`) have run, and they pass.

**At-risk tests, reviewed by reading the code:**

| Test | Result | Notes |
|---|---|---|
| `controller/OrderControllerTest` 400 test expecting `items[0].quantity` | *Already correct, no change.* | `OrderRequest` declares `List<@NotNull @Valid OrderItemRequest> items` and the controller uses `@Valid @RequestBody`, so Spring reports `items[0].quantity`. One unchecked assumption: Spring's bean wrapper reads the rejected value through the record accessors. If it can't, the test gets 500, not 400. |
| `repository/OrderRepositoryTest` native SQL status check | *Fixed.* | Hibernate 6.5 may map `@Enumerated(STRING)` to a native H2 `ENUM` column. `Order.status` now also has `@JdbcTypeCode(SqlTypes.VARCHAR)`, so the column is always `varchar(20)`. |
| `controller/ProductControllerTest` CORS test expecting 403 for an unknown origin | *Already correct, made sturdier.* | Spring's `DefaultCorsProcessor` returns 403 for an origin that isn't allowed. Added `@Import(WebConfig.class)` so the test doesn't rely on component scanning, and it now also checks that no `Access-Control-Allow-Origin` header is sent. |
| `service/ProductServiceTest` mocks vs the new `OrderItemRepository` constructor argument | *Already correct, no change.* | Both constructor arguments have a `@Mock`, and the delete tests stub `existsByProductId` for both cases. |

The other test classes were checked against the current constructors, DTOs and exceptions, and nothing needed
fixing.

**Recommendation:**
1. From inside `inventory-manager/`, run `./gradlew test` (Git Bash) or `gradlew.bat test` (cmd) and fix any
   failures. This is the first real compile.
2. Start the backend with `./gradlew bootRun` and the desktop app with `npm run dev` (in `desktop/`, after
   O-6), and click through the main flows.
3. Commit the v1 work to `develop` and push.

### O-2. No authentication or authorization

**Status:** open.
`spring-boot-starter-security` and `spring-security-test` are commented out in `build.gradle`, and the whole of
`config/SecurityConfig.java` is commented out. Anyone who can reach port 8080 can read and change all data, and
the H2 console at `/h2-console` is open. `model/Role.java` (`USER`, `ADMIN`) exists but is unused. The old
`SecurityConfig` draft also has hard-coded passwords (`userpass`, `adminpass`) and rules for `/admin/**` and
`/user/**` paths that don't exist; the real API is under `/api/**`.

**Recommendation:**
- Re-enable Spring Security with rules for `/api/**`: e.g. reads for `USER`, writes and deletes for `ADMIN`.
- Use a stateless scheme that suits a desktop client (HTTP Basic over HTTPS to start, or JWT/OAuth2 later).
  Disable CSRF only for the stateless API, and keep the CORS config (`.cors(withDefaults())`).
- Move users out of code (a `users` table with BCrypt hashes, or an identity provider).
- Disable the H2 console outside development, or restrict it to admins.
- Add a login screen and token/credential storage to the desktop app.

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

**Status:** open.
The datasource is `jdbc:h2:mem:inventix;DB_CLOSE_DELAY=-1` with `ddl-auto=create-drop`, so all data is lost on
every restart and the schema is generated by Hibernate.

**Recommendation:**
- Add a persistent database (PostgreSQL or MySQL) under a `prod` Spring profile, and keep H2 for tests and local
  development.
- Manage the schema with **Flyway** or **Liquibase** and switch `ddl-auto` to `validate`.
- As a quick step, an H2 file database (`jdbc:h2:file:./data/inventix`) with `ddl-auto=update` would keep data
  between restarts for local use.

### O-5. No pagination or sorting

**Status:** open.
`GET /api/products` and `GET /api/orders` return every row, and each order includes all its items (one extra
query per order while JSON is written, because `orderItems` is lazy and `open-in-view` is on). This is fine for
demo data but won't scale.

**Recommendation:** accept `Pageable` (`?page=&size=&sort=`) in the repositories and controllers and return a page
object, update `docs/API.md` and `desktop/src/js/api.js`, and use `@EntityGraph` or a fetch join for order items.
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

**Status:** open (smell).
`ProductController` still accepts and returns the `Product` entity, and orders are returned as entities, held
together with Jackson annotations and `spring.jpa.open-in-view=true`. This couples the database schema to the API
and is why open-in-view is needed (Spring logs a warning about it at startup).

**Recommendation:** add `ProductRequest`/`ProductResponse`, `OrderResponse` and `OrderItemResponse` DTOs (e.g.
Java records), map inside the transactional service layer, then set `spring.jpa.open-in-view=false`.

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
  low-stock report) is written. `model/Role.java` is kept because security (O-2) will need it.
- **Currency.** The API has no currency field, and the desktop app shows every amount as USD.
- **`Location` header in docs.** *Fixed:* `docs/API.md` now describes `Location` as an absolute URL (e.g.
  `http://localhost:8080/api/products/6`), which is what `ServletUriComponentsBuilder.fromCurrentRequest()` produces.
- **Duplicate lines.** Adding a product that is already on an order creates a second line instead of increasing
  the quantity. This is documented; decide whether to merge lines.
- **Timestamps.** Entity timestamps are `LocalDateTime` without a zone. Consider `Instant` or `OffsetDateTime`
  once the app runs on more than one machine.
- **Audit trail.** Stock changes aren't recorded anywhere. A `stock_movements` table would support reporting and
  debugging.
- **Desktop tests.** There are no automated tests for `desktop/src/js/`. Only the dev server has tests so far
  (`desktop/scripts/dev-server.test.js`).

## 5. Roadmap beyond v1

From the original README's planned features, still not started:

- Stock movement history and reporting/analytics (turnover, low-stock trends, order patterns)
- Reorder thresholds per product, with email/SMS notifications
- Purchase orders (incoming stock) as well as sales orders
- Role-based access control (see O-2)
- Multi-warehouse inventory
- Demand forecasting
