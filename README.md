# Inventix: Inventory Management System

**Inventix** is an inventory management system for small and medium businesses. A Spring Boot backend exposes a
REST API for products, stock levels and orders, and a lightweight desktop app (Tauri 2 with plain HTML, CSS and
JavaScript) sits on top of it.

The project is at **v2 Run 1**: data is kept in an H2 file database with Flyway migrations, the database can be
backed up from the desktop app, and every stock change is recorded in a stock movement ledger. Inventix is built
for one user on one machine: the API only accepts local connections and has no login. See
[Known limitations and roadmap](#known-limitations-and-roadmap) and the full [review](docs/REVIEW.md).

## Features

**Products and stock**
- Create, view, update and delete products (name, unique SKU, description, price, quantity on hand).
- Adjust stock up or down with a single call and a required note; stock can never go below zero.
- List low-stock products with a configurable threshold (default 10).
- Stock movement ledger: every stock change (orders, cancellations, manual adjustments, starting stock) writes a
  movement with a reason (`SALE`, `CANCEL`, `RECEIPT`, `PRODUCTION`, `ADJUSTMENT`), a timestamp and an optional
  note and order reference. Movements can be listed per product or for all products.

**Data**
- Data is kept between restarts in an H2 file database. The schema is managed by Flyway migrations.
- One-click backups to a `.zip` file (`POST /api/backup`, or **Back up now** in the desktop Settings).

**Orders**
- Create orders with or without items. New orders start as `PENDING`.
- Add, change and remove order items. Stock is reserved when an item is added and given back when it is removed
  or reduced.
- The price per unit is captured when an item is added, and the order total is recalculated whenever items change.
- Status workflow: `PENDING → SHIPPED → DELIVERED`, or `PENDING → CANCELLED`. Cancelling gives the stock back.
  Only `PENDING` orders can be edited.

**API quality**
- Request validation with field-level error messages.
- Responses are DTOs; no database entity is sent or accepted by the API.
- One consistent JSON error body for every error, with proper status codes (400, 404, 405, 409, 415, 500).
- CORS configured for the Tauri desktop app and local static servers.
- The server listens on `127.0.0.1` only.
- Five sample products are added on the first start, when there are no products (can be turned off).

**Desktop app** (`desktop/`)
- Dashboard with stock and order summaries, a low-stock list and recent orders.
- Product management with search, forms that mirror the backend rules, stock adjustment with a note, and a
  stock history window per product.
- Order management: create from a product picker, edit items, change status, delete.
- Settings for the backend URL and low-stock threshold, with a connection test and a **Back up now** button.
- Also runs in a normal browser for quick testing.

## Tech stack

| Area | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.3.5 (Web, Data JPA, Validation) |
| Database | H2 file database (`./data/inventix`), schema managed by Flyway; in-memory H2 for tests |
| Build | Gradle 8.10.2 via the included wrapper |
| Utilities | Lombok |
| Tests | JUnit 5, Mockito, AssertJ, MockMvc (`spring-boot-starter-test`) |
| Desktop UI | Tauri 2, vanilla HTML / CSS / JavaScript (no framework or bundler) |
| CI | GitHub CodeQL workflow (`.github/workflows/codeql.yml`, currently commented out) |

There is no authentication, by design: the server binds to `127.0.0.1` (`server.address`), so only programs on
the same machine can reach it. Add authentication before ever exposing it on a network.

## Project structure

```
inventory-manager/
├── build.gradle, settings.gradle     Gradle build (project name "Inventix")
├── gradlew, gradlew.bat, gradle/     Gradle wrapper
├── docs/
│   ├── API.md                        REST API contract
│   └── REVIEW.md                     Review: what was fixed, what is still open
├── src/
│   ├── main/java/com/example/inventix/
│   │   ├── InventixApplication.java
│   │   ├── config/                   WebConfig (CORS), DataSeeder (sample data, first start only)
│   │   ├── controller/               Product, Order, StockMovement and Backup controllers
│   │   ├── dto/                      Request and response records, ApiError
│   │   ├── exception/                Domain exceptions and GlobalExceptionHandler
│   │   ├── model/                    Product, Order, OrderItem, OrderStatus, StockMovement, MovementReason
│   │   ├── repository/               Spring Data JPA repositories
│   │   └── service/                  Service interfaces, implementations in service/impl/
│   ├── main/resources/
│   │   ├── application.properties
│   │   └── db/migration/             Flyway migrations (V1 schema, V2 stock_movements)
│   ├── test/resources/
│   │   └── application.properties    Test settings: in-memory H2, so tests never touch data/
│   └── test/java/com/example/inventix/
│       ├── config/                   DataSeeder tests
│       ├── controller/               @WebMvcTest tests
│       ├── service/                  Mockito unit tests, H2 stock/ledger integration and backup tests
│       ├── repository/               @DataJpaTest tests
│       └── InventixApplicationTests  Full-app context and end-to-end HTTP tests
├── data/                             H2 database files, created on first start (gitignored)
├── backups/                          Backup zips from POST /api/backup (gitignored)
├── desktop/                          Tauri 2 desktop app (see desktop/README.md)
│   ├── package.json
│   ├── src/                          index.html, styles.css, js/
│   └── src-tauri/                    Rust shell, tauri.conf.json, capabilities, icons
└── .github/workflows/codeql.yml
```

## Prerequisites

**Backend**
- **JDK 17.** The build uses a Gradle Java toolchain set to 17.
- No separate Gradle install is needed; use the wrapper (`gradlew.bat`).
- No database install is needed; H2 is embedded and stores its data in a file.

**Desktop app** (optional): Microsoft C++ Build Tools, WebView2, Rust (MSVC toolchain) and Node.js LTS. The
details are in [desktop/README.md](desktop/README.md#prerequisites-windows).

## Getting started (Windows PowerShell)

```powershell
git clone https://github.com/emmanuelalozie/inventory-manager
cd inventory-manager
```

The default branch is **`develop`**.

| Task | Command |
|---|---|
| Build (compiles and runs the tests) | `.\gradlew.bat build` |
| Run the tests only | `.\gradlew.bat test` |
| Start the backend | `.\gradlew.bat bootRun` |

On macOS or Linux, use `./gradlew` instead of `.\gradlew.bat`.

The backend listens on **`http://localhost:8080`**, on `127.0.0.1` only, so other machines can't connect. Test
reports are written to `build\reports\tests\test\index.html`.

Start `bootRun` from the repository root (`inventory-manager\`). The database path is relative to the folder the
backend is started from (see [Data and backups](#data-and-backups)).

### Configuration

Settings live in `src/main/resources/application.properties`:

| Setting | Value |
|---|---|
| Server address and port | `127.0.0.1:8080` (`server.address`, `server.port`) |
| JDBC URL | `jdbc:h2:file:./data/inventix` (user `sa`, empty password) |
| Schema | Flyway migrations in `src/main/resources/db/migration`; Hibernate only validates (`ddl-auto=validate`) |
| Open-in-view | `spring.jpa.open-in-view=false` |
| Sample data | `inventix.seed-data=true` adds five products on the first start, when there are no products; set it to `false` to turn this off |
| Backup folder | `inventix.backup.dir=./backups` |

Tests use `src/test/resources/application.properties` instead: an in-memory H2 database with the same Flyway
migrations, so running the tests never touches `data/`.

### H2 console

While the backend is running, open **`http://localhost:8080/h2-console`** and sign in with:

- **JDBC URL:** `jdbc:h2:file:./data/inventix`
- **User name:** `sa`
- **Password:** leave empty

The console runs inside the backend, so it can open the database while the app is running. Don't change data
there by hand unless you know what you're doing: stock changes made in the console skip the stock ledger.

## Data and backups

**Where the data lives.** The database is the file **`data\inventix.mv.db`**, relative to the folder the backend
was started from. With `.\gradlew.bat bootRun` from the repository root, that's `inventory-manager\data\`. If you
start the backend from another folder, it creates a new, empty database there. `data\` and `backups\` are
gitignored.

**Sample data.** On the first start (no products in the database) five sample products are added, with their
starting stock recorded in the ledger. Later starts leave the data alone. To start from scratch, stop the backend
and delete `data\`.

**One process at a time.** Only one process may open the database. While the backend runs, H2 keeps a
`data\inventix.lock.db` file, and a second backend or an outside tool opening the same file fails with "database may
be already in use". Use the built-in H2 console instead, and don't copy `inventix.mv.db` while the backend is
running; use a backup.

**Making a backup.** Click **Back up now** in the desktop app's Settings, or call the API:

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/backup
```

The backend writes `backups\inventix-backup-<yyyyMMdd-HHmmss>.zip` (relative to the start folder, set by
`inventix.backup.dir`) and returns the file name, full path and size. It is safe to run while the app is in use.
Old backups are never deleted automatically, and they sit on the same disk as the database, so copy important ones
somewhere else.

**Restoring a backup** (manual):

1. Stop the backend (Ctrl+C in its terminal) and close the desktop app.
2. Move the current `data\inventix.mv.db` somewhere safe, in case you need it again.
3. Unzip the backup into `data\`, so that `data\inventix.mv.db` is the file from the zip:

   ```powershell
   Expand-Archive backups\inventix-backup-20261004-153000.zip -DestinationPath data -Force
   ```

4. Start the backend again. Flyway applies any newer migrations to the restored database.

A backup is an H2 database file, so a newer H2 version may not be able to open it. Before upgrading Spring Boot
or H2, read
[O-13 in the review](docs/REVIEW.md#o-13-h2-file-format-changes-on-upgrade).

## API summary

The full contract, including JSON shapes, validation rules, stock rules and error codes, is in
**[docs/API.md](docs/API.md)**. There is no Swagger/OpenAPI UI yet.

| Method | Path | Description |
|---|---|---|
| GET | `/api/products` | List products |
| GET | `/api/products/low-stock?threshold=10` | Products with quantity ≤ threshold |
| GET / PUT / DELETE | `/api/products/{id}` | Get, replace or delete a product |
| POST | `/api/products` | Create a product |
| PATCH | `/api/products/{id}/stock` | Adjust stock with `{"delta": n, "note": "why"}` (note required) |
| GET | `/api/products/{id}/movements` | A product's stock movements, newest first |
| GET | `/api/stock-movements[?productId=&reason=]` | All stock movements, newest first |
| GET | `/api/orders[?status=PENDING]` | List orders, optionally by status |
| POST | `/api/orders` | Create an order, optionally with items |
| GET / PUT / DELETE | `/api/orders/{id}` | Get, replace the items of, or delete an order |
| PATCH | `/api/orders/{id}/status` | Change status with `{"status": "SHIPPED"}` |
| GET / POST | `/api/orders/{orderId}/items` | List or add order items |
| PUT / DELETE | `/api/orders/{orderId}/items/{itemId}` | Change an item's quantity or remove it |
| POST | `/api/backup` | Write a backup zip of the database to `./backups` |

`PUT /api/products/{id}` doesn't change stock: leave `quantity` out (a different value returns 400) and use the
stock endpoint instead.

Errors always use the same body:

```json
{ "timestamp": "...", "status": 409, "error": "Conflict", "message": "...", "path": "...", "fieldErrors": [ ... ] }
```

`fieldErrors` only appears on validation errors (400).

Quick check from PowerShell once the backend is running:

```powershell
Invoke-RestMethod http://localhost:8080/api/products
```

## Desktop app

The desktop client lives in `desktop/`. In short:

```powershell
# terminal 1, in inventory-manager\
.\gradlew.bat bootRun

# terminal 2
cd desktop
npm install
npx tauri icon path\to\logo.png   # once, to generate the app icons
npm run dev
```

`npm run dev` serves the UI on `http://127.0.0.1:1420`, which the backend allows through CORS. See
**[desktop/README.md](desktop/README.md)** for prerequisites, building an installer, and running the UI in a
normal browser (`npm run serve`).

## Known limitations and roadmap

The [review](docs/REVIEW.md) has the full list with recommendations. The main points:

**Known limitations**
- **No authentication, by design.** The API binds to `127.0.0.1`, but any program or user account on the same
  machine can use it and the H2 console.
- **Restoring a backup is manual** (see [Data and backups](#data-and-backups)), and old backups are never cleaned up.
- **The database path depends on the start folder.** Starting the backend somewhere else creates a new database.
- **H2 file format.** A future Spring Boot or H2 upgrade may not open existing database files or backups without
  an export and re-import.
- **Spring Boot 3.3.x is out of open-source support** (since June 2025).
- **No pagination** on the product and order lists (movement lists are paged).
- **Tauri icons are not checked in** and must be generated before the first desktop build. The desktop app hasn't
  had a first real run yet.
- The CodeQL workflow is commented out, and there is no CI workflow that builds and tests the project.

**Roadmap**
1. **v2 Run 2:** suppliers and purchase orders (`RECEIPT`), production runs (`PRODUCTION`), buy/make source,
   reorder points and a Restock action.
2. **v2 Run 3:** categories, unit cost, reports and CSV export.
3. Upgrade to Spring Boot 3.5.x (low risk, mind the H2 file format), then plan a separate migration to 4.x.
4. Add a GitHub Actions build-and-test workflow, pagination on the remaining lists and OpenAPI/Swagger docs.
5. Later: ship backend and desktop as one app, multi-location stock, demand forecasting.

## Contributing

Contributions are welcome. Fork the repository, create a branch from `develop`, and open a pull request against
`develop`. Please run `.\gradlew.bat test` before submitting.
