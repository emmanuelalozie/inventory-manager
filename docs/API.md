# Inventix REST API (v2)

This is the contract between the Inventix backend and the desktop UI.

- **Base URL:** `http://localhost:8080` (or `http://127.0.0.1:8080`)
- **Local only:** the server binds to `127.0.0.1` (`server.address`), so only programs on the same machine can
  reach it. Other machines on the network can't connect.
- **Authentication:** none, by design. Inventix has one user on one machine, and the localhost binding replaces
  a login. Don't change `server.address` without adding authentication first.
- **Content type:** every request body and response body is JSON (`Content-Type: application/json`).
- **Responses are DTOs.** No endpoint returns or accepts a JPA entity. The shapes below are the full response
  bodies.
- **Database:** H2 file database in the per-user Inventix folder (see the README, "Data and backups"), kept between
  restarts. The schema is managed by
  Flyway. Five sample products are added on the first start only, when there are no products (turn this off with
  `inventix.seed-data=false`).
- **OpenAPI:** Swagger UI at `http://localhost:8080/swagger-ui.html`, the generated document at
  `http://localhost:8080/v3/api-docs`. This file remains the reference for the rules behind each endpoint.
- **H2 console:** `http://localhost:8080/h2-console` (JDBC URL: the `Database:` path from the backend's log without
  `.mv.db`, user `sa`, no password).

## CORS

By default, requests to `/api/**` are allowed from these origins:

| Origin | Used by |
|---|---|
| `http://tauri.localhost` | Tauri v2 production build on Windows |
| `https://tauri.localhost` | Tauri v2 on Windows with `useHttpsScheme: true` |
| `tauri://localhost` | Tauri on macOS / Linux |
| `http://localhost:1420`, `http://127.0.0.1:1420` | Tauri dev server (`npm run dev` / `npm run serve` in `desktop/`, fixed port) |
| `http://localhost:5500`, `http://127.0.0.1:5500` | Static file server (e.g. VS Code Live Server) |

Allowed methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS`. Allowed headers: any. Exposed headers: `Location`.
Preflight responses are cached for 3600 seconds. A request from any other origin is rejected by the browser
(it shows up in JS as `TypeError: Failed to fetch`). The defaults live in `config/WebConfig.java`. To use a
different list without changing code, set `inventix.cors.allowed-origins` (comma-separated) in
`application.properties` or the `INVENTIX_CORS_ALLOWED_ORIGINS` environment variable. It replaces the defaults, so
include the Tauri origins if the desktop app should keep working.

If the Tauri app sets `app.security.csp`, its `connect-src` must include `http://localhost:8080`.

## Data types

- Money (`price`, `pricePerUnit`, `subtotal`, `totalAmount`) is a JSON number with up to 2 decimals, e.g. `24.99`.
- Product, order and movement timestamps (`createdAt`, `updatedAt`) are local date-times without a zone, e.g.
  `"2026-10-04T10:15:30.123456"`. `updatedAt` is `null` until the record is changed for the first time.
- The error `timestamp` and the backup `createdAt` are UTC instants, e.g. `"2026-10-04T08:15:30.123456Z"`.
- IDs are positive integers.

### Product (`ProductResponse`)

```json
{
  "id": 1,
  "name": "Wireless Mouse",
  "sku": "WM-001",
  "description": "2.4 GHz wireless optical mouse",
  "price": 24.99,
  "quantity": 120,
  "source": "BUY",
  "supplierId": 2,
  "reorderPoint": 20,
  "reorderQuantity": 100,
  "categoryId": 1,
  "unitCost": 9.50,
  "createdAt": "2026-10-04T10:15:30.123456",
  "updatedAt": null,
  "version": 0
}
```

Requests to `POST` and `PUT /api/products` use `ProductRequest`: `name`, `sku`, `description`, `price`,
`quantity`, `source`, `supplierId`, `reorderPoint`, `reorderQuantity`, `categoryId`, `unitCost` and `version`. Any other field (`id`, `createdAt`, `updatedAt`, …) is ignored.

| Field | Type | Rules |
|---|---|---|
| `id` | number | Read-only. Ignored in request bodies. |
| `name` | string | Required, not blank, max 255 characters |
| `sku` | string | Required, not blank, max 64 characters, unique across products. Stored trimmed and in upper case, so `" wm-001"` is saved as `"WM-001"` and counts as a duplicate of it. |
| `description` | string or null | Optional, max 1000 characters |
| `price` | number | Required, >= 0 |
| `quantity` | integer | The stock on hand, >= 0. Required on `POST` (the starting stock). Optional on `PUT`, and it can't change there (see [`PUT /api/products/{id}`](#put-apiproductsid)). |
| `source` | string | `BUY` (from a supplier, the default) or `MAKE` (made in-house). Decides what [Restock](#restock) does. |
| `supplierId` | number or null | The supplier a `BUY` product is ordered from. Must be an existing supplier (**400** otherwise), and must be left out for `MAKE` products (**400**). |
| `reorderPoint` | integer or null | >= 0. The product appears on the [reorder list](#get-apireorder-list) when `quantity <= reorderPoint`. |
| `reorderQuantity` | integer or null | > 0. How many Restock orders or plans to make. |
| `categoryId` | number or null | An existing [category](#categories) (**400** otherwise) |
| `unitCost` | number or null | >= 0, at most 2 decimals. What one unit costs to buy or make (not the selling price). Receiving a purchase order line that has a `unitCost` sets the product's `unitCost` to it (last purchase cost). |
| `createdAt`, `updatedAt` | string | Read-only, set by the server |
| `version` | integer | Optimistic-lock counter, set by the server. Starts at `0` and goes up on every change (including stock changes from orders). Ignored on `POST`. Optional on `PUT`: if sent, it must match the stored value or the call fails with **409**. |

`PUT` replaces `source`, `supplierId`, `reorderPoint`, `reorderQuantity`, `categoryId` and `unitCost` like the other
fields, so leaving them out clears them (and `source` falls back to `BUY`).

### Order (`OrderResponse`)

```json
{
  "id": 1,
  "status": "PENDING",
  "customerId": 3,
  "customerName": "Harbor Cafe",
  "totalAmount": 139.48,
  "createdAt": "2026-10-04T10:20:00.000001",
  "updatedAt": "2026-10-04T10:20:00.000002",
  "orderItems": [ /* OrderItem objects, see below */ ]
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | number | |
| `status` | string | One of the `OrderStatus` values below. New orders are always `PENDING`. |
| `customerId`, `customerName` | number, string | The customer the order is for, or both `null` for an order without one |
| `totalAmount` | number | The sum of `subtotal` over all `orderItems`. Recalculated by the server whenever items change. |
| `createdAt`, `updatedAt` | string | Set by the server |
| `orderItems` | array of OrderItem | Empty array if the order has no items |

### OrderItem (`OrderItemResponse`)

The product is flattened to `productId`, `productName` and `productSku`. Since v2 there is no nested `product`
object.

```json
{
  "id": 3,
  "orderId": 1,
  "productId": 1,
  "productName": "Wireless Mouse",
  "productSku": "WM-001",
  "quantity": 2,
  "pricePerUnit": 24.99,
  "subtotal": 49.98
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | number | |
| `orderId` | number | The order this item belongs to |
| `productId` | number | The ordered product |
| `productName`, `productSku` | string | The product's **current** name and SKU. For its current stock, read the product from `/api/products`. |
| `quantity` | integer | Units ordered (> 0) |
| `pricePerUnit` | number | The product price when the item was added. It does not change if the product price changes later. |
| `subtotal` | number | `quantity × pricePerUnit` |

### OrderStatus

`"PENDING"`, `"SHIPPED"`, `"DELIVERED"`, `"CANCELLED"` (uppercase strings).

Allowed transitions:

```
PENDING ──> SHIPPED ──> DELIVERED
   │
   └──────> CANCELLED
```

- `DELIVERED` and `CANCELLED` are final.
- Setting the status an order already has is a no-op and returns 200.
- Any other transition returns **409**.
- Only `PENDING` orders can have items added, changed or removed, or be replaced with `PUT`. For any other
  status these calls return **409**.

### StockMovement (`StockMovementResponse`)

One row of the stock ledger (`stock_movements` table). Movements are written by the server and can't be created,
changed or deleted through the API.

```json
{
  "id": 17,
  "productId": 3,
  "productName": "USB-C Hub",
  "productSku": "HUB-003",
  "delta": -2,
  "reason": "SALE",
  "note": "Added to order #5",
  "orderId": 5,
  "purchaseOrderId": null,
  "locationId": null,
  "createdAt": "2026-10-04T15:30:00.123456"
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | number | |
| `productId`, `productName`, `productSku` | number, string, string | The product whose stock changed (current name and SKU) |
| `delta` | integer | Change in stock: positive adds, negative removes. Never `0`. |
| `reason` | string | One of the `MovementReason` values below |
| `note` | string or null | Free text, max 500 characters. Always set for `ADJUSTMENT`. Order movements get a generated note, e.g. `"Order #5 cancelled"`. |
| `orderId` | number or null | The order that caused the change. Kept after a `PENDING` order is deleted, so it can point to an order that no longer exists. |
| `purchaseOrderId` | number or null | The purchase order a `RECEIPT` was booked on |
| `productionRunId` | number or null | The production run a `PRODUCTION` or `CONSUMPTION` movement came from |
| `locationId` | number or null | Reserved for multiple locations. Always `null` for now. |
| `createdAt` | string | When the change was made (local date-time, no zone) |

### MovementReason

| Value | Written when |
|---|---|
| `SALE` | An item is added to an order, or an item's quantity is raised |
| `CANCEL` | An item is removed or its quantity lowered, an order is replaced with `PUT`, cancelled, or deleted while `PENDING` |
| `RECEIPT` | Stock received on a purchase order (`POST /api/purchase-orders/{id}/receipts`) |
| `PRODUCTION` | A production run is completed (`PATCH /api/production-runs/{id}/status`) |
| `CONSUMPTION` | Components used up by a completed production run (`productionRunId` set) |
| `ADJUSTMENT` | `PATCH /api/products/{id}/stock` (note required), and the starting stock of a new product (note `"Initial stock"`) |

### Backup (`BackupResponse`)

```json
{
  "fileName": "inventix-backup-20261004-153000.zip",
  "path": "C:\\Users\\me\\inventory-manager\\backups\\inventix-backup-20261004-153000.zip",
  "sizeBytes": 20480,
  "createdAt": "2026-10-04T15:30:00.512Z"
}
```

| Field | Type | Notes |
|---|---|---|
| `fileName` | string | `inventix-backup-<yyyyMMdd-HHmmss>.zip`, with `-1`, `-2`, … added if a file with that name already exists |
| `path` | string | Absolute path of the zip file on the backend machine |
| `sizeBytes` | number | Size of the zip file |
| `createdAt` | string | When the backup finished (UTC instant) |

### Stock rules

- Adding an item to an order takes `quantity` units out of the product's stock. If there isn't enough
  stock, the call fails with **409** and nothing changes.
- Changing an item's quantity takes or gives back the difference.
- Removing an item, or replacing the items with `PUT /api/orders/{id}`, gives the stock back.
- Cancelling an order gives back the stock for all its items. The items stay on the order as a record.
- Deleting a `PENDING` order gives the stock back. Deleting a `SHIPPED`, `DELIVERED` or `CANCELLED` order
  doesn't change stock.
- Stock changes use optimistic locking on the product (`version`). If two requests change the same product at the
  same moment, the one that commits second fails with **409** ("The data was changed by another request at the
  same time. Reload it and try again.") and nothing in it is saved, so stock can't be oversold. Retry the request.
- **Every stock change writes a `StockMovement`** in the same transaction, so if the change fails, no movement is
  written. A product's movements add up to its `quantity`.
- Stock can only change through orders, `PATCH /api/products/{id}/stock` and the starting stock on
  `POST /api/products`. `PUT /api/products/{id}` can't change it.
- Deleting a product also deletes its movements.

## Error body

Every error response (4xx and 5xx) has this shape:

```json
{
  "timestamp": "2026-10-04T08:15:30.123456Z",
  "status": 404,
  "error": "Not Found",
  "message": "Product not found with id: 42",
  "path": "/api/products/42"
}
```

Validation errors (400) also include `fieldErrors`, and so does the 400 for a changed `quantity` on
`PUT /api/products/{id}`. It is left out of every other error.

```json
{
  "timestamp": "2026-10-04T08:15:30.123456Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/products",
  "fieldErrors": [
    { "field": "name", "message": "Name is required" },
    { "field": "price", "message": "Price must be zero or greater" }
  ]
}
```

For nested fields, `field` uses paths like `items[0].quantity`.

| Status | When |
|---|---|
| 400 | Validation failed (`fieldErrors` present), malformed JSON, unknown enum value in the body or query (e.g. `?reason=FOO`), a path or query parameter of the wrong type (e.g. `/api/products/abc`), a missing required parameter, a stock `delta` of `0`, a changed `quantity` on product `PUT`, `page` < 0 or `size` outside 1–500 on movement lists |
| 404 | Product, order or order item not found, an item that belongs to a different order, or an unknown URL |
| 405 | HTTP method not supported on that path |
| 409 | Not enough stock, invalid status change, changing a non-`PENDING` order, duplicate SKU, deleting a product that is used by an order, a stale product `version`, a concurrent change to the same product, or a backup requested while the database is in memory |
| 415 | Body isn't sent as `application/json` |
| 500 | Unexpected server error (`message` is `"An unexpected error occurred"`), or a backup that failed (`message` says why) |

## Endpoints

| Method | Path | Success | Description |
|---|---|---|---|
| GET | `/api/products` | 200 | List products (optional `?q=&categoryId=&page=&size=&sort=`) |
| GET | `/api/products/low-stock?threshold=10` | 200 | List products with `quantity <= threshold` |
| GET | `/api/products/{id}` | 200 | Get one product |
| POST | `/api/products` | 201 | Create a product |
| PUT | `/api/products/{id}` | 200 | Replace a product's fields |
| PATCH | `/api/products/{id}/stock` | 200 | Add to or remove from stock, with a required note |
| DELETE | `/api/products/{id}` | 204 | Delete a product and its movements |
| GET | `/api/products/{id}/movements` | 200 | A product's stock movements, newest first |
| GET | `/api/stock-movements` | 200 | All stock movements, newest first (optional `?productId=&reason=&from=&to=`) |
| GET | `/api/orders` | 200 | List orders, newest first (optional `?status=&customerId=&page=&size=`) |
| GET | `/api/orders/{id}` | 200 | Get one order with its items |
| POST | `/api/orders` | 201 | Create an order, optionally with items |
| PUT | `/api/orders/{id}` | 200 | Replace all items of a `PENDING` order |
| PATCH | `/api/orders/{id}/customer` | 200 | Set or clear an order's customer, in any status |
| PATCH | `/api/orders/{id}/status` | 200 | Change an order's status |
| DELETE | `/api/orders/{id}` | 204 | Delete an order |
| GET | `/api/orders/{orderId}/items` | 200 | List an order's items |
| POST | `/api/orders/{orderId}/items` | 201 | Add an item to a `PENDING` order |
| PUT | `/api/orders/{orderId}/items/{itemId}` | 200 | Change an item's quantity |
| DELETE | `/api/orders/{orderId}/items/{itemId}` | 204 | Remove an item from a `PENDING` order |
| POST | `/api/backup` | 201 | Write a backup zip of the database |
| GET | `/api/backup` | 200 | Data and backup folders, backups, pending restore |
| POST | `/api/backup/restore` | 200 | Restore a backup on the next start |
| DELETE | `/api/backup/restore` | 200 | Cancel a pending restore |
| GET | `/api/customers` | 200 | List customers by name (optional `?q=`) |
| GET | `/api/customers/{id}` | 200 | Get one customer |
| POST | `/api/customers` | 201 | Create a customer |
| PUT | `/api/customers/{id}` | 200 | Replace a customer's fields |
| DELETE | `/api/customers/{id}` | 204 | Delete a customer that has no orders |
| GET | `/api/suppliers` | 200 | List suppliers by name |
| GET | `/api/suppliers/{id}` | 200 | Get one supplier |
| POST | `/api/suppliers` | 201 | Create a supplier |
| PUT | `/api/suppliers/{id}` | 200 | Replace a supplier's fields |
| DELETE | `/api/suppliers/{id}` | 204 | Delete a supplier no product or purchase order uses |
| GET | `/api/purchase-orders` | 200 | List purchase orders, newest first (optional `?status=&supplierId=&page=&size=`) |
| GET | `/api/purchase-orders/{id}` | 200 | Get one purchase order with its lines |
| POST | `/api/purchase-orders` | 201 | Create a `DRAFT` purchase order |
| PUT | `/api/purchase-orders/{id}` | 200 | Replace a `DRAFT`'s supplier, notes and lines |
| PATCH | `/api/purchase-orders/{id}/status` | 200 | Order, cancel or close a purchase order |
| POST | `/api/purchase-orders/{id}/receipts` | 200 | Receive stock on some or all lines |
| POST | `/api/purchase-orders/{id}/lines/{lineId}/cancel-remaining` | 200 | Stop expecting what is still to come on one line |
| DELETE | `/api/purchase-orders/{id}` | 204 | Delete a `DRAFT` or `CANCELLED` purchase order |
| GET | `/api/production-runs` | 200 | List production runs, newest first (optional `?status=&productId=&page=&size=`) |
| GET | `/api/production-runs/{id}` | 200 | Get one production run |
| POST | `/api/production-runs` | 201 | Plan a production run |
| PUT | `/api/production-runs/{id}` | 200 | Change a `PLANNED` run |
| PATCH | `/api/production-runs/{id}/status` | 200 | Complete (uses components, adds stock) or cancel a run |
| GET | `/api/products/{id}/components` | 200 | A made product's components (bill of materials) |
| PUT | `/api/products/{id}/components` | 200 | Replace a made product's components |
| DELETE | `/api/production-runs/{id}` | 204 | Delete a `PLANNED` or `CANCELLED` run |
| GET | `/api/reorder-list` | 200 | Products at or below their reorder point |
| POST | `/api/products/{id}/restock` | 200 | Add a product's reorder quantity to a draft purchase order or a planned run |
| POST | `/api/reorder-list/restock` | 200 | Restock every ready product on the reorder list |
| GET | `/api/categories` | 200 | List categories with their product counts |
| POST | `/api/categories` | 201 | Create a category |
| PUT | `/api/categories/{id}` | 200 | Rename a category |
| DELETE | `/api/categories/{id}` | 204 | Delete a category that has no products |
| GET | `/api/reports/stock-value` | 200 | Stock value at price and at cost, per category |
| GET | `/api/reports/movements` | 200 | Stock changes per reason (optional `?from=&to=`) |
| GET | `/api/reports/top-sellers` | 200 | Products by units sold (optional `?from=&to=&limit=`) |
| GET | `/api/export/{products,stock-value,stock-movements,top-sellers}.csv` | 200 | CSV downloads |

---

### Products

#### `GET /api/products`

Returns products, optionally searched, sorted and paged. Without `page` and `size` every match is returned.

| Parameter | Rules |
|---|---|
| `q` | Optional. Case-insensitive text that the `name`, `sku` or `description` must contain. `%` and `_` match literally. |
| `sort` | Optional, default `id`. One of `id`, `name`, `sku`, `price`, `quantity`, `createdAt`, `updatedAt`, optionally followed by `,asc` or `,desc` (e.g. `quantity,desc`). Ties are ordered by `id`. |
| `page` | Optional, 0-based, default `0` when `size` is given |
| `size` | Optional, 1–500, default `50` when `page` is given |

The `X-Total-Count` response header holds the number of matching products across all pages (it is exposed to
the browser through CORS).

- **200**: `Product[]`
- **400**: unknown `sort` field or direction, negative `page`, or `size` outside 1–500

```json
[
  { "id": 1, "name": "Wireless Mouse", "sku": "WM-001", "description": "2.4 GHz wireless optical mouse",
    "price": 24.99, "quantity": 120, "createdAt": "2026-10-04T10:15:30.123456", "updatedAt": null, "version": 0 }
]
```

#### `GET /api/products/low-stock?threshold={n}`

Returns products whose `quantity` is less than or equal to `threshold`, lowest stock first.
`threshold` is optional and defaults to `10`.

- **200**: `Product[]`
- **400**: `threshold` isn't an integer, or is negative

```json
[
  { "id": 5, "name": "Laptop Stand", "sku": "LS-005", "description": "Adjustable aluminium laptop stand",
    "price": 29.99, "quantity": 0, "createdAt": "2026-10-04T10:15:30.123456", "updatedAt": null, "version": 0 },
  { "id": 4, "name": "27\" Monitor", "sku": "MON-004", "description": "27 inch 1440p IPS monitor",
    "price": 279.00, "quantity": 4, "createdAt": "2026-10-04T10:15:30.123456", "updatedAt": null, "version": 0 }
]
```

#### `GET /api/products/{id}`

- **200**: `Product`
- **404**: no product with that id

#### `POST /api/products`

Request:

```json
{
  "name": "Webcam",
  "sku": "CAM-006",
  "description": "1080p USB webcam",
  "price": 49.99,
  "quantity": 25
}
```

If `quantity` is above 0, an `ADJUSTMENT` movement with note `"Initial stock"` is written for it.

- **201**: the created `Product`. The `Location` header is the product's absolute URL, e.g.
  `http://localhost:8080/api/products/6`.

```json
{
  "id": 6, "name": "Webcam", "sku": "CAM-006", "description": "1080p USB webcam",
  "price": 49.99, "quantity": 25, "createdAt": "2026-10-04T10:30:00.000001", "updatedAt": null, "version": 0
}
```

- **400**: validation failed (see the Product field rules)
- **409**: a product with that `sku` already exists

#### `PUT /api/products/{id}`

Replaces `name`, `sku`, `description` and `price`. All required fields must be sent. It **never changes stock**;
use [`PATCH /api/products/{id}/stock`](#patch-apiproductsidstock) for that, so every change gets a ledger entry.

`quantity` is optional. Leave it out (recommended), or send the stored value, and it is ignored. A different value
returns **400**.

Request: same body as `POST /api/products` without `quantity`, plus an optional `version`. Send the `version` from
the product you loaded to make sure you don't overwrite a change someone else made in the meantime:

```json
{ "name": "Webcam", "sku": "CAM-006", "description": "1080p USB webcam", "price": 44.99, "version": 0 }
```

- **200**: the updated `Product` (its `version` goes up by 1 if any field changed)
- **400**: validation failed, or `quantity` differs from the stored value:

```json
{
  "timestamp": "2026-10-04T08:15:30.123456Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Quantity can't be changed by editing the product (stored 25, sent 30). Use PATCH /api/products/6/stock with a note, or leave quantity out.",
  "path": "/api/products/6",
  "fieldErrors": [
    { "field": "quantity", "message": "Quantity can't be changed by editing the product (stored 25, sent 30). Use PATCH /api/products/6/stock with a note, or leave quantity out." }
  ]
}
```

- **404**: no product with that id
- **409**: another product already uses that `sku`, or `version` was sent and doesn't match the stored value
  (reload the product and try again)

#### `PATCH /api/products/{id}/stock`

Manual stock adjustment. Adds `delta` to the product's `quantity` and writes an `ADJUSTMENT` movement with the
note. Use a positive `delta` to add stock and a negative one to remove it.

Request:

```json
{ "delta": -2, "note": "Broken in transit" }
```

| Field | Rules |
|---|---|
| `delta` | Required integer, not `0` |
| `note` | Required, not blank, max 500 characters. Leading and trailing spaces are trimmed. |

- **200**: the updated `Product`
- **400**:
  - `delta` missing (`fieldErrors` field `delta`) or not an integer
  - `note` missing or blank (`fieldErrors` field `note`, message `"A note is required for manual stock adjustments"`)
    or longer than 500 characters
  - `delta` is `0` (message `"delta must not be 0"`, no `fieldErrors`)
- **404**: no product with that id
- **409**: the stock would go below 0, e.g.

```json
{
  "timestamp": "2026-10-04T08:15:30.123456Z",
  "status": 409,
  "error": "Conflict",
  "message": "Not enough stock for product 'USB-C Hub': available 8, requested 10",
  "path": "/api/products/3/stock"
}
```

A stale or concurrent change also returns **409** (see [Stock rules](#stock-rules)).

#### `DELETE /api/products/{id}`

Deletes the product and its stock movements.

- **204**: deleted, no body
- **404**: no product with that id
- **409**: the product is used by at least one order item (in any order status), a purchase order line, a
  production run, or another product's components. Its own components list is deleted with it.

---

### Stock movements

Read-only. Both endpoints return a plain JSON array of `StockMovement`, newest first (by `createdAt`, then `id`).

Paging: `page` starts at `0` (default `0`) and `size` must be 1–500 (default `100`). There is no total count. A
page with fewer than `size` rows is the last one.

#### `GET /api/products/{id}/movements?page=0&size=100`

- **200**: `StockMovement[]` for that product (empty if it has none)
- **400**: `page` < 0, `size` outside 1–500, or either isn't an integer
- **404**: no product with that id

#### `GET /api/stock-movements?productId=&reason=&from=&to=&page=0&size=100`

All filters are optional and can be combined. `from` and `to` are dates (`2026-10-01`), both days included (see
[Dates in reports](#dates-in-reports)). The `X-Total-Count` header holds the number of matches across all pages. `reason` is one of `SALE`, `CANCEL`, `RECEIPT`, `PRODUCTION`,
`ADJUSTMENT`. An unknown `productId` returns an empty array, not 404.

- **200**: `StockMovement[]`

```json
[
  { "id": 18, "productId": 3, "productName": "USB-C Hub", "productSku": "HUB-003", "delta": 2, "reason": "CANCEL",
    "note": "Order #5 cancelled", "orderId": 5, "purchaseOrderId": null, "locationId": null,
    "createdAt": "2026-10-04T15:40:00.000001" },
  { "id": 17, "productId": 3, "productName": "USB-C Hub", "productSku": "HUB-003", "delta": -2, "reason": "SALE",
    "note": "Added to order #5", "orderId": 5, "purchaseOrderId": null, "locationId": null,
    "createdAt": "2026-10-04T15:30:00.123456" }
]
```

- **400**: `reason` isn't a valid `MovementReason`, `page`/`size` is invalid, a date isn't `YYYY-MM-DD`, or `from`
  is after `to`

---

### Backup

#### `POST /api/backup`

Writes a zip copy of the database with H2's `BACKUP TO`. No request body: the server picks the folder and file
name. It runs while the app is in use; H2 copies the transaction log too, so the zip is consistent.

- **Folder:** `inventix.backup.dir`, default `backups` in the per-user Inventix folder (see the README, "Data and
  backups"). It is created if it doesn't exist.
- **File name:** `inventix-backup-<yyyyMMdd-HHmmss>.zip` (local time). A second backup in the same second gets
  `-1`, `-2`, … instead of overwriting the first.
- **Contents:** `inventix.mv.db`. To restore one, see [`POST /api/backup/restore`](#post-apibackuprestore).
- Old backups are never deleted by the server.

Responses:

- **201**: `Backup`, e.g.

```json
{
  "fileName": "inventix-backup-20261004-153000.zip",
  "path": "C:\\Users\\me\\inventory-manager\\backups\\inventix-backup-20261004-153000.zip",
  "sizeBytes": 20480,
  "createdAt": "2026-10-04T15:30:00.512Z"
}
```

- **409**: the database is in memory, so there is nothing to back up (`"The database is in memory, so there is
  nothing to back up. Use a file database to enable backups."`)
- **500**: the folder couldn't be created or the backup command failed. `message` starts with `"Backup failed: "`
  or names the folder.

The desktop app uses a 120-second timeout for this call. Every other call uses 10 seconds.

#### `GET /api/backup`

Where the data lives, the backups (newest first) and any restore waiting for the next start:

```json
{
  "databaseFile": "C:\\Users\\me\\AppData\\Local\\Inventix\\data\\inventix.mv.db",
  "backupDir": "C:\\Users\\me\\AppData\\Local\\Inventix\\backups",
  "pendingRestore": null,
  "backups": [{ "fileName": "inventix-backup-20261017-091500.zip", "sizeBytes": 20480, "createdAt": "2026-10-17T09:15:00Z" }]
}
```

`databaseFile` is `null` when the database isn't a file managed by Inventix (e.g. the in-memory test database).

#### `POST /api/backup/restore`

`{"fileName": "inventix-backup-20261017-091500.zip"}` schedules that backup to be restored **the next time the
backend starts**; nothing changes until then. On that start, before the database is opened, the backup's
`inventix.mv.db` replaces the current one, which is kept as `inventix-before-restore-<yyyyMMdd-HHmmss>.mv.db` in the
data folder. Flyway then applies any newer migrations. If the backup can't be used, the current database is kept
and the request is dropped (the log says why).

- **200**: the same body as `GET /api/backup`, with `pendingRestore` set
- **400**: `fileName` is missing, isn't a plain `.zip` file name in the backup folder, doesn't exist, or holds no
  database
- **409**: the database isn't a file managed by Inventix

#### `DELETE /api/backup/restore`

Cancels a scheduled restore. **200** with the backup status.

---

### Orders

#### `GET /api/orders`

Returns orders newest first (highest `id` first). Optional query parameter `status` (`PENDING`, `SHIPPED`,
`DELIVERED` or `CANCELLED`) returns only orders with that status, and `customerId` only that customer's orders. `page` (0-based) and `size` (1–500) work as for
`GET /api/products`; without both, every match is returned. `X-Total-Count` holds the number of matching orders.

- **200**: `Order[]` (each order includes its `orderItems`)
- **400**: `status` isn't a valid `OrderStatus`, negative `page`, or `size` outside 1–500

#### `GET /api/orders/{id}`

- **200**: `Order`
- **404**: no order with that id

#### `POST /api/orders`

Creates a `PENDING` order for the given customer (optional) and adds the given items, reserving their stock.
`items` can be omitted or left empty to create an empty order. If the customer or any item fails (unknown product, not enough stock), the whole request
fails and nothing is created.

**Retries:** send an `Idempotency-Key` header (any unique string up to 100 characters, e.g. a UUID) and reuse it
when retrying the same create, for example after a timeout. If an order was already created with that key, it
is returned with **200** and nothing new is created or reserved. The key is matched on its own; the body of a
repeated request isn't compared with the first one. Two requests with the same key at exactly the same time can
make the second fail with **409**; retrying it then returns the order.

Request:

```json
{
  "customerId": 3,
  "items": [
    { "productId": 1, "quantity": 2 },
    { "productId": 2, "quantity": 1 }
  ]
}
```

| Field | Rules |
|---|---|
| `customerId` | Optional. Leave it out (or `null`) for an order without a customer. |
| `items` | Optional array. Must not contain `null`. |
| `items[].productId` | Required |
| `items[].quantity` | Required, > 0 |

- **201**: the created `Order`. The `Location` header is the order's absolute URL, e.g.
  `http://localhost:8080/api/orders/1`.
- **200**: an order already exists for this `Idempotency-Key`; it is returned unchanged.
- **400**: `Idempotency-Key` is empty or longer than 100 characters

```json
{
  "id": 1,
  "status": "PENDING",
  "totalAmount": 139.88,
  "createdAt": "2026-10-04T10:20:00.000001",
  "updatedAt": "2026-10-04T10:20:00.000002",
  "orderItems": [
    {
      "id": 1, "orderId": 1, "productId": 1, "productName": "Wireless Mouse", "productSku": "WM-001",
      "quantity": 2, "pricePerUnit": 24.99, "subtotal": 49.98
    },
    {
      "id": 2, "orderId": 1, "productId": 2, "productName": "Mechanical Keyboard", "productSku": "KB-002",
      "quantity": 1, "pricePerUnit": 89.90, "subtotal": 89.90
    }
  ]
}
```

- **400**: validation failed (e.g. `fieldErrors: [{ "field": "items[0].quantity", "message": "quantity must be greater than 0" }]`)
- **404**: the `customerId` or a `productId` doesn't exist
- **409**: not enough stock for one of the items

#### `PUT /api/orders/{id}`

Replaces **all** items of a `PENDING` order. Stock for the old items is given back, then stock for the new
items is reserved. Sending `{"items": []}` empties the order. If any new item fails, nothing changes.

Request: same body as `POST /api/orders`. `customerId` is ignored here and the order keeps its customer; use
`PATCH /api/orders/{id}/customer` to change it.

- **200**: the updated `Order`
- **400**: validation failed
- **404**: no order with that id, or a `productId` doesn't exist
- **409**: the order isn't `PENDING`, or there isn't enough stock

#### `PATCH /api/orders/{id}/customer`

Sets the customer of an order, or clears it with `null`. Works in every status, because it only corrects who the
order is for: items, stock and status don't change.

Request: `{ "customerId": 3 }` or `{ "customerId": null }`.

- **200**: the updated `Order`
- **404**: no order with that id, or the `customerId` doesn't exist

#### `PATCH /api/orders/{id}/status`

Request:

```json
{ "status": "SHIPPED" }
```

- **200**: the updated `Order`. Moving to `CANCELLED` gives the stock back and writes one `CANCEL` movement per
  item.
- **400**: `status` missing or not a valid `OrderStatus`
- **404**: no order with that id
- **409**: transition not allowed, e.g.

```json
{
  "timestamp": "2026-10-04T08:15:30.123456Z",
  "status": 409,
  "error": "Conflict",
  "message": "Cannot change order 1 from DELIVERED to PENDING",
  "path": "/api/orders/1/status"
}
```

#### `DELETE /api/orders/{id}`

Deletes a `PENDING` or `CANCELLED` order and its items. If the order is `PENDING`, its stock is given back
first, with a `CANCEL` movement per item. Those movements keep the deleted order's `orderId`. `SHIPPED` and
`DELIVERED` orders are kept as a record and can't be deleted.

- **204**: deleted, no body
- **404**: no order with that id
- **409**: the order is `SHIPPED` or `DELIVERED`

---

### Order items

#### `GET /api/orders/{orderId}/items`

- **200**: `OrderItem[]`
- **404**: no order with that id

#### `POST /api/orders/{orderId}/items`

Adds one item to a `PENDING` order, reserves its stock and updates the order's `totalAmount`. Adding a
product that is already on the order creates a separate line. The response is the new item only. Call
`GET /api/orders/{orderId}` to get the new `totalAmount`.

Request:

```json
{ "productId": 3, "quantity": 1 }
```

- **201**: the created `OrderItem`. The `Location` header is the item's absolute URL, e.g.
  `http://localhost:8080/api/orders/1/items/4`.

```json
{
  "id": 3, "orderId": 1, "productId": 3, "productName": "USB-C Hub", "productSku": "HUB-003",
  "quantity": 1, "pricePerUnit": 39.50, "subtotal": 39.50
}
```

- **400**: validation failed
- **404**: no order with that id, or no product with that `productId`
- **409**: the order isn't `PENDING`, or there isn't enough stock

#### `PUT /api/orders/{orderId}/items/{itemId}`

Changes the item's quantity. The difference is taken from or given back to stock, and `subtotal` and the
order's `totalAmount` are recalculated. `pricePerUnit` stays the same.

Request:

```json
{ "quantity": 3 }
```

- **200**: the updated `OrderItem`
- **400**: `quantity` missing or not > 0
- **404**: no order with that id, no item with that id, or the item belongs to a different order
- **409**: the order isn't `PENDING`, or there isn't enough stock

#### `DELETE /api/orders/{orderId}/items/{itemId}`

Removes the item, gives its stock back and updates the order's `totalAmount`.

- **204**: removed, no body
- **404**: no order with that id, no item with that id, or the item belongs to a different order
- **409**: the order isn't `PENDING`

### Customers

A customer: `{ "id": 3, "name": "Harbor Cafe", "email": "orders@harborcafe.example", "phone": "555-0142",
"address": "12 Pier Road, Portland", "notes": null, "createdAt": "...", "updatedAt": null, "version": 0 }`.

Requests (`POST`, `PUT`) take `name` (required, max 255; two customers may share a name), `email` (optional, must be
an email address), `phone` (optional, max 50), `address` (optional, max 1000), `notes` (optional, max 1000) and, on
`PUT`, an optional `version`. Blank optional fields are stored as `null`.

- `GET /api/customers`: **200**, customers by name. `q` returns only those whose name, email or phone contains it,
  ignoring case.
- `GET /api/customers/{id}`: **200**; **404**
- `POST /api/customers`: **201** with `Location`; **400** for a missing name or bad email
- `PUT /api/customers/{id}`: **200**; **404**; **409** if `version` is stale
- `DELETE /api/customers/{id}`: **204**; **404**; **409** while any order is for the customer (orders are kept as a
  record; clear or change the order's customer first, or keep the customer)

### Suppliers

A supplier: `{ "id": 2, "name": "Acme Supply", "email": "orders@acme.test", "phone": null, "notes": null,
"createdAt": "...", "updatedAt": null, "version": 0 }`.

Requests (`POST`, `PUT`) take `name` (required, max 255, unique ignoring case), `email` (optional, must be an email
address), `phone` (optional, max 50), `notes` (optional, max 1000) and, on `PUT`, an optional `version`. Blank
optional fields are stored as `null`.

- `GET /api/suppliers`: **200**, all suppliers by name
- `POST /api/suppliers`: **201** with `Location`; **400** for a missing name or bad email; **409** if the name is taken
- `PUT /api/suppliers/{id}`: **200**; **404**; **409** if the name is taken or `version` is stale
- `DELETE /api/suppliers/{id}`: **204**; **404**; **409** while a product has it as `supplierId` or it has any
  purchase order (purchase orders are kept as a record)

### Purchase orders

Statuses: `DRAFT` → `ORDERED` → `PARTIALLY_RECEIVED` → `RECEIVED`.

| Change | How |
|---|---|
| `DRAFT` → `ORDERED` | `PATCH .../status` `{"status": "ORDERED"}`. Needs at least one line; sets `orderedAt`. |
| `DRAFT` or `ORDERED` → `CANCELLED` | `PATCH .../status`. Not possible once anything was received. |
| `ORDERED` → `PARTIALLY_RECEIVED` → `RECEIVED` | Automatic when receiving: `RECEIVED` once every line is in full |
| `PARTIALLY_RECEIVED` → `CLOSED` | `PATCH .../status`, when the rest won't arrive |
| `ORDERED` / `PARTIALLY_RECEIVED` → `CLOSED` or `CANCELLED` | Automatic once [cancelling the rest of lines](#post-apipurchase-ordersidlineslineidcancel-remaining) leaves nothing to come: `CLOSED` if anything arrived, `CANCELLED` if nothing did. Receiving the last outstanding units of an order with cancelled quantities also ends in `CLOSED`, not `RECEIVED`. |

`RECEIVED`, `CLOSED` and `CANCELLED` are final. Other changes return **409**.

```json
{
  "id": 1,
  "supplierId": 2,
  "supplierName": "Acme Supply",
  "status": "PARTIALLY_RECEIVED",
  "notes": "Weekly order",
  "totalCost": 25.00,
  "orderedAt": "2026-10-17T09:12:00.123456",
  "createdAt": "2026-10-17T09:10:00.654321",
  "updatedAt": "2026-10-17T11:40:00.000001",
  "version": 3,
  "lines": [
    { "id": 7, "productId": 1, "productName": "Wireless Mouse", "productSku": "WM-001",
      "quantityOrdered": 100, "quantityReceived": 60, "quantityCancelled": 0, "quantityRemaining": 40,
      "unitCost": 0.25, "lineTotal": 25.00 }
  ]
}
```

`unitCost` is what the supplier charges and is optional. `quantityRemaining` is ordered − received − cancelled.
`lineTotal` is `unitCost` × (ordered − cancelled), and `totalCost` adds up the lines that have a cost.

#### `POST /api/purchase-orders` and `PUT /api/purchase-orders/{id}`

```json
{ "supplierId": 2, "notes": "Weekly order", "lines": [{ "productId": 1, "quantity": 100, "unitCost": 0.25 }] }
```

`POST` creates a `DRAFT` (**201** with `Location`). `PUT` replaces a `DRAFT`'s supplier, notes and lines (**200**),
with an optional `version`. Each product may appear on one line only.

- **400**: validation failed, unknown supplier, or the same product on two lines
- **404**: unknown `productId`, or no purchase order with that id
- **409**: `PUT` on a purchase order that isn't a `DRAFT`, or a stale `version`

#### `POST /api/purchase-orders/{id}/receipts`

Books what arrived, on `ORDERED` or `PARTIALLY_RECEIVED` purchase orders. Lines not listed stay as they are.

```json
{ "lines": [{ "lineId": 7, "quantity": 60 }], "note": "Delivery note 881" }
```

Each line adds its quantity to stock with a `RECEIPT` movement (`purchaseOrderId` set, note `"Received on purchase
order #1: Delivery note 881"`). Everything is checked first, so one bad line books nothing.

- **200**: the updated purchase order
- **400**: empty `lines`, a quantity below 1, or the same line twice
- **404**: a `lineId` that isn't on this purchase order
- **409**: the purchase order can't receive (wrong status), or a quantity is more than the line's
  `quantityRemaining`. Two receipts at the same moment can also get **409**; reload and try again.

#### `POST /api/purchase-orders/{id}/lines/{lineId}/cancel-remaining`

Stops expecting what is still to come on one line of an `ORDERED` or `PARTIALLY_RECEIVED` purchase order (it moves
from `quantityRemaining` to `quantityCancelled`). What already arrived stays. Drafts are edited instead.

- **200**: the updated purchase order (its status settles as described above when nothing is left to come)
- **404**: the line isn't on this purchase order
- **409**: the purchase order isn't `ORDERED` or `PARTIALLY_RECEIVED`, or the line has nothing left to come

#### `DELETE /api/purchase-orders/{id}`

Only `DRAFT` and `CANCELLED` purchase orders can be deleted (**204**); others return **409**.

### Production runs

Statuses: `PLANNED` → `COMPLETED` or `CANCELLED` (both final). When the product has
[components](#bills-of-materials), completing a run uses them up.

```json
{ "id": 3, "productId": 6, "productName": "Workbench", "productSku": "WB-01", "quantity": 5, "status": "PLANNED",
  "notes": "Oak batch", "completedAt": null, "createdAt": "...", "updatedAt": null, "version": 0 }
```

- `POST /api/production-runs` `{"productId": 6, "quantity": 5, "notes": "Oak batch"}`: **201**, status `PLANNED`.
  Stock doesn't change yet.
- `PUT /api/production-runs/{id}` (same body plus optional `version`): **200**; **409** unless `PLANNED`
- `PATCH /api/production-runs/{id}/status` `{"status": "COMPLETED"}`: takes component quantity × run quantity of
  each component out of stock (`CONSUMPTION` movements, note `"Used in production run #3"`), then adds `quantity` to
  stock with a `PRODUCTION` movement, and sets `completedAt`. If any component is short, nothing changes and the
  call returns **409** naming the product. `{"status": "CANCELLED"}` changes nothing else. Any other change is
  **409**.
- `DELETE /api/production-runs/{id}`: **204** for `PLANNED` or `CANCELLED`; **409** for `COMPLETED` (kept as a record)

### Bills of materials

What one unit of a made product (`source: "MAKE"`) uses. Completing a production run takes these out of stock.

- `GET /api/products/{id}/components`: `[{ "componentId": 3, "name": "USB-C Hub", "sku": "HUB-003", "quantity": 2,
  "inStock": 8 }]`, by name. **404** for an unknown product.
- `PUT /api/products/{id}/components` `{"components": [{"componentId": 3, "quantity": 2}]}` replaces the whole list
  (`[]` removes it) and returns the new list. **400** if the product isn't `MAKE`, a component doesn't exist, is the
  product itself, appears twice, or would make a loop (the component is made, directly or indirectly, from this
  product).
- While a product has components, `PUT /api/products/{id}` can't change its `source` to `BUY` (**400**).

### Restock

Restock never orders or makes anything by itself: it fills a draft for someone to review.

#### `GET /api/reorder-list`

Products whose `quantity <= reorderPoint`, lowest stock first.

```json
[
  { "productId": 1, "name": "Wireless Mouse", "sku": "WM-001", "source": "BUY", "supplierId": 2,
    "supplierName": "Acme Supply", "quantity": 12, "reorderPoint": 20, "reorderQuantity": 100,
    "onOrder": 40, "covered": true, "problem": null }
]
```

| Field | Meaning |
|---|---|
| `onOrder` | Units still to arrive on `DRAFT`, `ORDERED` and `PARTIALLY_RECEIVED` purchase orders, plus units in `PLANNED` production runs |
| `covered` | `quantity + onOrder > reorderPoint`, so nothing more is needed |
| `problem` | Why Restock can't act yet: `"no reorder quantity set"` or `"no supplier set"`; otherwise `null` |

#### `POST /api/products/{id}/restock`

- `BUY` products: adds a line for `reorderQuantity` to the supplier's newest `DRAFT` purchase order, creating the
  draft if there is none. A product already on that draft is left as it is.
- `MAKE` products: plans a production run for `reorderQuantity`, unless the product already has a `PLANNED` run.

```json
{ "productId": 1, "sku": "WM-001", "action": "PURCHASE_ORDER", "purchaseOrderId": 4, "productionRunId": null,
  "created": true, "added": true, "message": "Added 100 to draft purchase order #4" }
```

`action` is `PURCHASE_ORDER` or `PRODUCTION_RUN`; `created` says a new draft or run was started, `added` that the
product was added (false when it was already there). Works whatever the current stock.

- **404**: no product with that id
- **409**: the product has no `reorderQuantity`, or is `BUY` without a supplier

#### `POST /api/reorder-list/restock`

Restocks every product on the reorder list that has no `problem` and isn't `covered`. Returns one result per
product on the list; the others have `action: "SKIPPED"` and the reason in `message`.

### Categories

`GET /api/categories` returns `[{ "id": 1, "name": "Cables", "productCount": 3, "version": 0 }]`, by name.

- `POST /api/categories` `{"name": "Cables"}` (required, max 100 characters, unique ignoring case): **201** with
  `Location`; **409** if the name is taken
- `PUT /api/categories/{id}` `{"name": "Cables & adapters", "version": 0}`: **200**; **404**; **409** if the name is
  taken or `version` is stale
- `DELETE /api/categories/{id}`: **204**; **404**; **409** while products are in it

### Reports

#### Dates in reports

`from` and `to` are dates in `YYYY-MM-DD` form and both days are included: `from=2026-10-01&to=2026-10-31` covers
every timestamp from 1 October 00:00 up to (not including) 1 November 00:00. Leave either out for an open range.
Timestamps are the backend machine's local time. `from` after `to` returns **400**.

#### `GET /api/reports/stock-value`

```json
{
  "total": { "products": 5, "units": 163, "valueAtPrice": 7412.43, "valueAtCost": 1111.50, "productsWithoutCost": 4 },
  "categories": [
    { "categoryId": 1, "categoryName": "Peripherals", "products": 1, "units": 117, "valueAtPrice": 2923.83,
      "valueAtCost": 1111.50, "productsWithoutCost": 0 },
    { "categoryId": null, "categoryName": null, "products": 4, "units": 46, "valueAtPrice": 4488.60,
      "valueAtCost": 0.00, "productsWithoutCost": 4 }
  ]
}
```

Values are `quantity × price` and `quantity × unitCost`. Products without a `unitCost` count as 0 at cost;
`productsWithoutCost` says how many, so the cost value can be read as a lower bound. Products without a category
are grouped last with `categoryId: null`.

#### `GET /api/reports/movements?from=&to=`

Per reason with any movements in the range: `[{ "reason": "SALE", "movements": 2, "unitsIn": 0, "unitsOut": 4 }]`.

#### `GET /api/reports/top-sellers?from=&to=&limit=10`

Products by units on `SHIPPED` and `DELIVERED` orders created in the range, most first (revenue breaks ties):
`[{ "productId": 1, "name": "Wireless Mouse", "sku": "WM-001", "unitsSold": 3, "revenue": 74.97 }]`. Revenue is
the item subtotals (the price captured when each item was added). `limit` is 1–100 (**400** otherwise).

### CSV export

`GET /api/export/products.csv`, `/api/export/stock-value.csv`, `/api/export/stock-movements.csv?productId=&reason=&from=&to=`
and `/api/export/top-sellers.csv?from=&to=&limit=` return `text/csv;charset=UTF-8` with
`Content-Disposition: attachment; filename="products-2026-10-17.csv"` (exposed through CORS).

- UTF-8 with a byte order mark, so Excel shows non-English text correctly; comma separated, CRLF line ends,
  fields quoted when they contain a comma, quote or line break.
- Text that a spreadsheet could run as a formula (starting with `=`, `+`, `-`, `@`, tab or carriage return) is
  written with a leading `'`. Numbers are written as plain numbers.
- The stock movements export includes every match (it reads the ledger in pages of 500).

---

## Example: calling the API from plain JS

```js
const API = "http://localhost:8080";

async function api(path, options = {}) {
  const res = await fetch(API + path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (res.status === 204) return null;
  const body = await res.json();
  if (!res.ok) throw body; // ApiError: { timestamp, status, error, message, path, fieldErrors? }
  return body;
}

// Create an order with two items, then ship it
const order = await api("/api/orders", {
  method: "POST",
  body: JSON.stringify({ items: [{ productId: 1, quantity: 2 }, { productId: 2, quantity: 1 }] }),
});
await api(`/api/orders/${order.id}/status`, { method: "PATCH", body: JSON.stringify({ status: "SHIPPED" }) });
```
