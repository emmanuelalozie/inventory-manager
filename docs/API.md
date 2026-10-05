# Inventix REST API (v1)

This is the contract between the Inventix backend and the desktop UI.

- **Base URL:** `http://localhost:8080`
- **Content type:** every request body and response body is JSON (`Content-Type: application/json`).
- **Authentication:** none. Spring Security is switched off in v1.
- **Database:** in-memory H2. Data is lost when the backend stops. Five sample products are added on startup
  (turn this off with `inventix.seed-data=false`).
- **H2 console:** `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:inventix`, user `sa`, no password).

## CORS

Requests to `/api/**` are allowed from these origins:

| Origin | Used by |
|---|---|
| `http://tauri.localhost` | Tauri v2 production build on Windows |
| `https://tauri.localhost` | Tauri v2 on Windows with `useHttpsScheme: true` |
| `tauri://localhost` | Tauri on macOS / Linux |
| `http://localhost:1420`, `http://127.0.0.1:1420` | Tauri dev server (`npm run dev` / `npm run serve` in `desktop/`, fixed port) |
| `http://localhost:5500`, `http://127.0.0.1:5500` | Static file server (e.g. VS Code Live Server) |

Allowed methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS`. Allowed headers: any. Exposed headers: `Location`.
Preflight responses are cached for 3600 seconds. A request from any other origin is rejected by the browser
(it shows up in JS as `TypeError: Failed to fetch`). The list lives in `config/WebConfig.java`.

If the Tauri app sets `app.security.csp`, its `connect-src` must include `http://localhost:8080`.

## Data types

- Money (`price`, `pricePerUnit`, `subtotal`, `totalAmount`) is a JSON number with up to 2 decimals, e.g. `24.99`.
- Entity timestamps (`createdAt`, `updatedAt`) are local date-times without a zone, e.g. `"2026-10-04T10:15:30.123456"`.
  `updatedAt` is `null` until the record is changed for the first time.
- The error `timestamp` is a UTC instant, e.g. `"2026-10-04T08:15:30.123456Z"`.
- IDs are positive integers.

### Product

```json
{
  "id": 1,
  "name": "Wireless Mouse",
  "sku": "WM-001",
  "description": "2.4 GHz wireless optical mouse",
  "price": 24.99,
  "quantity": 120,
  "createdAt": "2026-10-04T10:15:30.123456",
  "updatedAt": null,
  "version": 0
}
```

| Field | Type | Rules |
|---|---|---|
| `id` | number | Read-only. Ignored in request bodies. |
| `name` | string | Required, not blank, max 255 characters |
| `sku` | string | Required, not blank, max 64 characters, unique across products |
| `description` | string or null | Optional, max 1000 characters |
| `price` | number | Required, >= 0 |
| `quantity` | integer | Required, >= 0. This is the stock on hand. |
| `createdAt`, `updatedAt` | string | Read-only, set by the server |
| `version` | integer | Optimistic-lock counter, set by the server. Starts at `0` and goes up on every change (including stock changes from orders). Ignored on `POST`. Optional on `PUT`: if sent, it must match the stored value or the call fails with **409**. |

### Order

```json
{
  "id": 1,
  "status": "PENDING",
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
| `totalAmount` | number | The sum of `subtotal` over all `orderItems`. Recalculated by the server whenever items change. |
| `createdAt`, `updatedAt` | string | Set by the server |
| `orderItems` | array of OrderItem | Empty array if the order has no items |

### OrderItem

```json
{
  "id": 3,
  "orderId": 1,
  "product": {
    "id": 1,
    "name": "Wireless Mouse",
    "sku": "WM-001",
    "description": "2.4 GHz wireless optical mouse",
    "price": 24.99,
    "quantity": 118,
    "createdAt": "2026-10-04T10:15:30.123456",
    "updatedAt": "2026-10-04T10:20:00.000001",
    "version": 1
  },
  "quantity": 2,
  "pricePerUnit": 24.99,
  "subtotal": 49.98
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | number | |
| `orderId` | number | The order this item belongs to |
| `product` | Product | The full product **as it is now**, including its current stock in `product.quantity` |
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

Validation errors (400) also include `fieldErrors`. It is left out of every other error.

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
| 400 | Validation failed (`fieldErrors` present), malformed JSON, unknown enum value in the body, a path or query parameter of the wrong type (e.g. `/api/products/abc`), a missing required parameter |
| 404 | Product, order or order item not found, an item that belongs to a different order, or an unknown URL |
| 405 | HTTP method not supported on that path |
| 409 | Not enough stock, invalid status change, changing a non-`PENDING` order, duplicate SKU, deleting a product that is used by an order, a stale product `version`, or a concurrent change to the same product |
| 415 | Body isn't sent as `application/json` |
| 500 | Unexpected server error (`message` is `"An unexpected error occurred"`) |

## Endpoints

| Method | Path | Success | Description |
|---|---|---|---|
| GET | `/api/products` | 200 | List all products |
| GET | `/api/products/low-stock?threshold=10` | 200 | List products with `quantity <= threshold` |
| GET | `/api/products/{id}` | 200 | Get one product |
| POST | `/api/products` | 201 | Create a product |
| PUT | `/api/products/{id}` | 200 | Replace a product's fields |
| PATCH | `/api/products/{id}/stock` | 200 | Add to or remove from stock |
| DELETE | `/api/products/{id}` | 204 | Delete a product |
| GET | `/api/orders` | 200 | List all orders (optional `?status=`) |
| GET | `/api/orders/{id}` | 200 | Get one order with its items |
| POST | `/api/orders` | 201 | Create an order, optionally with items |
| PUT | `/api/orders/{id}` | 200 | Replace all items of a `PENDING` order |
| PATCH | `/api/orders/{id}/status` | 200 | Change an order's status |
| DELETE | `/api/orders/{id}` | 204 | Delete an order |
| GET | `/api/orders/{orderId}/items` | 200 | List an order's items |
| POST | `/api/orders/{orderId}/items` | 201 | Add an item to a `PENDING` order |
| PUT | `/api/orders/{orderId}/items/{itemId}` | 200 | Change an item's quantity |
| DELETE | `/api/orders/{orderId}/items/{itemId}` | 204 | Remove an item from a `PENDING` order |

---

### Products

#### `GET /api/products`

Returns every product.

- **200**: `Product[]`

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
- **400**: `threshold` isn't an integer

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

Replaces `name`, `sku`, `description`, `price` and `quantity`. All required fields must be sent. Use the
`/stock` endpoint to change only the stock.

Request: same body as `POST /api/products`, plus an optional `version`. Send the `version` from the product you
loaded to make sure you don't overwrite a change someone else made in the meantime:

```json
{ "name": "Webcam", "sku": "CAM-006", "description": "1080p USB webcam", "price": 44.99, "quantity": 25, "version": 0 }
```

- **200**: the updated `Product` (its `version` goes up by 1 if any field changed)
- **400**: validation failed
- **404**: no product with that id
- **409**: another product already uses that `sku`, or `version` was sent and doesn't match the stored value
  (reload the product and try again)

#### `PATCH /api/products/{id}/stock`

Adds `delta` to the product's `quantity`. Use a positive `delta` to restock and a negative one to remove stock.

Request:

```json
{ "delta": 25 }
```

- **200**: the updated `Product`
- **400**: `delta` missing or not an integer
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

#### `DELETE /api/products/{id}`

- **204**: deleted, no body
- **404**: no product with that id
- **409**: the product is used by at least one order item, in any order status

---

### Orders

#### `GET /api/orders`

Optional query parameter `status` (`PENDING`, `SHIPPED`, `DELIVERED` or `CANCELLED`) returns only orders
with that status.

- **200**: `Order[]` (each order includes its `orderItems`)
- **400**: `status` isn't a valid `OrderStatus`

#### `GET /api/orders/{id}`

- **200**: `Order`
- **404**: no order with that id

#### `POST /api/orders`

Creates a `PENDING` order and adds the given items, reserving their stock. `items` can be omitted or left
empty to create an empty order. If any item fails (unknown product, not enough stock), the whole request
fails and nothing is created.

Request:

```json
{
  "items": [
    { "productId": 1, "quantity": 2 },
    { "productId": 2, "quantity": 1 }
  ]
}
```

| Field | Rules |
|---|---|
| `items` | Optional array. Must not contain `null`. |
| `items[].productId` | Required |
| `items[].quantity` | Required, > 0 |

- **201**: the created `Order`. The `Location` header is the order's absolute URL, e.g.
  `http://localhost:8080/api/orders/1`.

```json
{
  "id": 1,
  "status": "PENDING",
  "totalAmount": 139.88,
  "createdAt": "2026-10-04T10:20:00.000001",
  "updatedAt": "2026-10-04T10:20:00.000002",
  "orderItems": [
    {
      "id": 1, "orderId": 1,
      "product": { "id": 1, "name": "Wireless Mouse", "sku": "WM-001", "description": "2.4 GHz wireless optical mouse",
                   "price": 24.99, "quantity": 118, "createdAt": "2026-10-04T10:15:30.123456", "updatedAt": "2026-10-04T10:20:00.000001", "version": 1 },
      "quantity": 2, "pricePerUnit": 24.99, "subtotal": 49.98
    },
    {
      "id": 2, "orderId": 1,
      "product": { "id": 2, "name": "Mechanical Keyboard", "sku": "KB-002", "description": "Tenkeyless keyboard with brown switches",
                   "price": 89.90, "quantity": 34, "createdAt": "2026-10-04T10:15:30.123456", "updatedAt": "2026-10-04T10:20:00.000001", "version": 1 },
      "quantity": 1, "pricePerUnit": 89.90, "subtotal": 89.90
    }
  ]
}
```

- **400**: validation failed (e.g. `fieldErrors: [{ "field": "items[0].quantity", "message": "quantity must be greater than 0" }]`)
- **404**: a `productId` doesn't exist
- **409**: not enough stock for one of the items

#### `PUT /api/orders/{id}`

Replaces **all** items of a `PENDING` order. Stock for the old items is given back, then stock for the new
items is reserved. Sending `{"items": []}` empties the order. If any new item fails, nothing changes.

Request: same body as `POST /api/orders`.

- **200**: the updated `Order`
- **400**: validation failed
- **404**: no order with that id, or a `productId` doesn't exist
- **409**: the order isn't `PENDING`, or there isn't enough stock

#### `PATCH /api/orders/{id}/status`

Request:

```json
{ "status": "SHIPPED" }
```

- **200**: the updated `Order`. Moving to `CANCELLED` gives the stock back.
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

Deletes the order and its items. If the order is `PENDING`, its stock is given back first.

- **204**: deleted, no body
- **404**: no order with that id

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
  "id": 3, "orderId": 1,
  "product": { "id": 3, "name": "USB-C Hub", "sku": "HUB-003", "description": "7-in-1 USB-C hub with HDMI and card reader",
               "price": 39.50, "quantity": 7, "createdAt": "2026-10-04T10:15:30.123456", "updatedAt": "2026-10-04T10:25:00.000001", "version": 1 },
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
