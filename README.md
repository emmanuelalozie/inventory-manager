# Inventix: Inventory Management System

**Inventix** is an inventory management system for small and medium businesses. A Spring Boot backend exposes a
REST API for products, stock levels and orders, and a lightweight desktop app (Tauri 2 with plain HTML, CSS and
JavaScript) sits on top of it.

The project is at **v1**: the core API and the desktop UI are in place, but there is no authentication yet and data
is kept in an in-memory database. See [Known limitations and roadmap](#known-limitations-and-roadmap) and the full
[v1 review](docs/REVIEW.md).

## Features

**Products and stock**
- Create, view, update and delete products (name, unique SKU, description, price, quantity on hand).
- Adjust stock up or down with a single call; stock can never go below zero.
- List low-stock products with a configurable threshold (default 10).

**Orders**
- Create orders with or without items. New orders start as `PENDING`.
- Add, change and remove order items. Stock is reserved when an item is added and given back when it is removed
  or reduced.
- The price per unit is captured when an item is added, and the order total is recalculated whenever items change.
- Status workflow: `PENDING → SHIPPED → DELIVERED`, or `PENDING → CANCELLED`. Cancelling gives the stock back.
  Only `PENDING` orders can be edited.

**API quality**
- Request validation with field-level error messages.
- One consistent JSON error body for every error, with proper status codes (400, 404, 405, 409, 415, 500).
- CORS configured for the Tauri desktop app and local static servers.
- Five sample products are added on startup (can be turned off).

**Desktop app** (`desktop/`)
- Dashboard with stock and order summaries, a low-stock list and recent orders.
- Product management with search, forms that mirror the backend rules, and stock adjustment.
- Order management: create from a product picker, edit items, change status, delete.
- Settings for the backend URL and low-stock threshold, with a connection test.
- Also runs in a normal browser for quick testing.

## Tech stack

| Area | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.3.5 (Web, Data JPA, Validation) |
| Database | H2, in memory |
| Build | Gradle 8.10.2 via the included wrapper |
| Utilities | Lombok |
| Tests | JUnit 5, Mockito, AssertJ, MockMvc (`spring-boot-starter-test`) |
| Desktop UI | Tauri 2, vanilla HTML / CSS / JavaScript (no framework or bundler) |
| CI | GitHub CodeQL workflow (`.github/workflows/codeql.yml`, currently commented out) |

Spring Security is declared in `build.gradle` but commented out, so the API has no authentication in v1.

## Project structure

```
inventory-manager/
├── build.gradle, settings.gradle     Gradle build (project name "Inventix")
├── gradlew, gradlew.bat, gradle/     Gradle wrapper
├── docs/
│   ├── API.md                        REST API contract
│   └── REVIEW.md                     v1 review: what was fixed, what is still open
├── src/
│   ├── main/java/com/example/inventix/
│   │   ├── InventixApplication.java
│   │   ├── config/                   WebConfig (CORS), DataSeeder (sample data), SecurityConfig (disabled)
│   │   ├── controller/               ProductController, OrderController
│   │   ├── dto/                      Request bodies and the ApiError response
│   │   ├── exception/                Domain exceptions and GlobalExceptionHandler
│   │   ├── model/                    Product, Order, OrderItem, OrderStatus, Role
│   │   ├── repository/               Spring Data JPA repositories
│   │   └── service/                  Service interfaces, implementations in service/impl/
│   ├── main/resources/
│   │   └── application.properties
│   └── test/java/com/example/inventix/
│       ├── controller/               @WebMvcTest tests
│       ├── service/                  Mockito unit tests and an H2 stock integration test
│       ├── repository/               @DataJpaTest tests
│       └── InventixApplicationTests  Full-app context and end-to-end HTTP tests
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
- No database install is needed; H2 runs in memory.

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

The backend listens on **`http://localhost:8080`**. Test reports are written to `build\reports\tests\test\index.html`.

### Configuration

Settings live in `src/main/resources/application.properties`:

| Setting | Value |
|---|---|
| Server port | `8080` |
| JDBC URL | `jdbc:h2:mem:inventix;DB_CLOSE_DELAY=-1` |
| Schema | Created on startup and dropped on shutdown (`spring.jpa.hibernate.ddl-auto=create-drop`) |
| Sample data | `inventix.seed-data=true` adds five products when the database is empty; set it to `false` to turn this off |

### H2 console

While the backend is running, open **`http://localhost:8080/h2-console`** and sign in with:

- **JDBC URL:** `jdbc:h2:mem:inventix`
- **User name:** `sa`
- **Password:** leave empty

All data is lost when the backend stops.

## API summary

The full contract, including JSON shapes, validation rules, stock rules and error codes, is in
**[docs/API.md](docs/API.md)**. There is no Swagger/OpenAPI UI yet.

| Method | Path | Description |
|---|---|---|
| GET | `/api/products` | List products |
| GET | `/api/products/low-stock?threshold=10` | Products with quantity ≤ threshold |
| GET / PUT / DELETE | `/api/products/{id}` | Get, replace or delete a product |
| POST | `/api/products` | Create a product |
| PATCH | `/api/products/{id}/stock` | Adjust stock by `{"delta": n}` |
| GET | `/api/orders[?status=PENDING]` | List orders, optionally by status |
| POST | `/api/orders` | Create an order, optionally with items |
| GET / PUT / DELETE | `/api/orders/{id}` | Get, replace the items of, or delete an order |
| PATCH | `/api/orders/{id}/status` | Change status with `{"status": "SHIPPED"}` |
| GET / POST | `/api/orders/{orderId}/items` | List or add order items |
| PUT / DELETE | `/api/orders/{orderId}/items/{itemId}` | Change an item's quantity or remove it |

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

The [v1 review](docs/REVIEW.md) has the full list with recommendations. The main points:

**Known limitations**
- **No authentication or authorization.** Spring Security is disabled and `SecurityConfig` is commented out.
  Anyone who can reach port 8080 can change data, and the H2 console is open.
- **In-memory database only.** Data is lost on every restart.
- **Spring Boot 3.3.x is out of open-source support** (since June 2025).
- **No pagination** on list endpoints.
- **Tauri icons are not checked in** and must be generated before the first desktop build.
- **Not yet verified on this machine.** The v1 changes were written without running `.\gradlew.bat build`,
  the tests or the desktop app. Run them before relying on this version.
- The CodeQL workflow is commented out, and there is no CI workflow that builds and tests the project.

**Roadmap**
1. Run the build and tests, fix anything that fails, and add a GitHub Actions build-and-test workflow.
2. Upgrade to Spring Boot 3.5.x (low risk), then plan a separate migration to 4.x.
3. Turn on Spring Security with roles (`Role.USER`, `Role.ADMIN`) and lock down the H2 console.
4. Add a persistent database (PostgreSQL or MySQL) with Flyway or Liquibase migrations.
5. Add pagination and sorting, plus OpenAPI/Swagger docs.
6. Add optimistic locking on product stock.
7. Planned features: stock movement history, reorder alerts and notifications, reporting and analytics,
   multi-warehouse support and demand forecasting.

## Contributing

Contributions are welcome. Fork the repository, create a branch from `develop`, and open a pull request against
`develop`. Please run `.\gradlew.bat test` before submitting.
