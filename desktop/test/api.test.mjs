// Tests for the request shapes in src/js/api.js, with fetch replaced by a recorder.
// Run with: npm test (from desktop\)

import test from "node:test";
import assert from "node:assert/strict";

import {
  ApiError,
  DEFAULT_BASE_URL,
  MOVEMENTS_PAGE_SIZE,
  LIST_PAGE_SIZE,
  backupApi,
  customersApi,
  exportApi,
  fileNameFrom,
  newIdempotencyKey,
  ordersApi,
  productsApi,
  purchaseOrdersApi,
  restockApi,
  stockMovementsApi,
} from "../src/js/api.js";

/** Replaces fetch for one test. Returns the list of recorded calls. */
function mockFetch(t, { status = 200, body = null } = {}) {
  const calls = [];
  const original = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    calls.push({ url, method: init.method, body: init.body ? JSON.parse(init.body) : undefined });
    const text = body === null ? "" : JSON.stringify(body);
    return {
      status,
      ok: status >= 200 && status < 300,
      statusText: status === 200 ? "OK" : "Error",
      text: async () => text,
    };
  };
  t.after(() => {
    globalThis.fetch = original;
  });
  return calls;
}

test("adjustStock sends the delta and the note", async (t) => {
  const calls = mockFetch(t, { body: { id: 4, quantity: 8 } });
  await productsApi.adjustStock(4, -2, "Broken in transit");
  assert.deepEqual(calls, [
    {
      url: `${DEFAULT_BASE_URL}/api/products/4/stock`,
      method: "PATCH",
      body: { delta: -2, note: "Broken in transit" },
    },
  ]);
});

test("adjustStock surfaces the backend's 400 field errors", async (t) => {
  mockFetch(t, {
    status: 400,
    body: {
      status: 400,
      error: "Bad Request",
      message: "Validation failed",
      fieldErrors: [{ field: "note", message: "A note is required for manual stock adjustments" }],
    },
  });
  await assert.rejects(productsApi.adjustStock(4, 1, ""), (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 400);
    assert.equal(error.message, "Validation failed");
    assert.deepEqual(error.fieldErrors, [
      { field: "note", message: "A note is required for manual stock adjustments" },
    ]);
    return true;
  });
});

test("product movements are requested a page at a time", async (t) => {
  const calls = mockFetch(t, { body: [] });
  await productsApi.movements(9);
  await productsApi.movements(9, { page: 2, size: 25 });
  assert.equal(calls[0].url, `${DEFAULT_BASE_URL}/api/products/9/movements?page=0&size=${MOVEMENTS_PAGE_SIZE}`);
  assert.equal(calls[1].url, `${DEFAULT_BASE_URL}/api/products/9/movements?page=2&size=25`);
  assert.ok(MOVEMENTS_PAGE_SIZE >= 1 && MOVEMENTS_PAGE_SIZE <= 500);
});

test("all movements only send the filters that are set", async (t) => {
  const calls = mockFetch(t, { body: [] });
  await stockMovementsApi.list();
  await stockMovementsApi.list({ productId: 3, reason: "SALE", page: 1, size: 10 });
  assert.equal(calls[0].url, `${DEFAULT_BASE_URL}/api/stock-movements?page=0&size=${MOVEMENTS_PAGE_SIZE}`);
  assert.equal(calls[1].url, `${DEFAULT_BASE_URL}/api/stock-movements?productId=3&reason=SALE&page=1&size=10`);
});

test("backup posts without a body and returns the file details", async (t) => {
  const result = {
    fileName: "inventix-backup-20261004-153000.zip",
    path: "C:\\inventix\\backups\\inventix-backup-20261004-153000.zip",
    sizeBytes: 20480,
    createdAt: "2026-10-04T15:30:00Z",
  };
  const calls = mockFetch(t, { status: 201, body: result });
  assert.deepEqual(await backupApi.create(), result);
  assert.deepEqual(calls, [{ url: `${DEFAULT_BASE_URL}/api/backup`, method: "POST", body: undefined }]);
});

test("backup errors carry the server's message", async (t) => {
  mockFetch(t, {
    status: 409,
    body: { status: 409, error: "Conflict", message: "Backups need a file database; this one is in memory" },
  });
  await assert.rejects(backupApi.create(), (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 409);
    assert.equal(error.message, "Backups need a file database; this one is in memory");
    return true;
  });
});

test("ordersApi.create sends the Idempotency-Key header only when a key is given", async (t) => {
  const headers = [];
  const original = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    headers.push(init.headers);
    return { status: 201, ok: true, statusText: "Created", text: async () => '{"id":1}' };
  };
  t.after(() => {
    globalThis.fetch = original;
  });

  await ordersApi.create([{ productId: 1, quantity: 2 }], "key-1");
  await ordersApi.create([]);

  assert.equal(headers[0]["Idempotency-Key"], "key-1");
  assert.equal("Idempotency-Key" in headers[1], false);
});

test("newIdempotencyKey returns a different key each time", () => {
  assert.notEqual(newIdempotencyKey(), newIdempotencyKey());
});

test("productsApi.search sends query, paging and sort, and reads X-Total-Count", async (t) => {
  const urls = [];
  const original = globalThis.fetch;
  globalThis.fetch = async (url) => {
    urls.push(url);
    return {
      status: 200,
      ok: true,
      statusText: "OK",
      headers: { get: (name) => (name === "X-Total-Count" ? "120" : null) },
      text: async () => '[{"id":1}]',
    };
  };
  t.after(() => {
    globalThis.fetch = original;
  });

  const result = await productsApi.search({ q: "mouse pad", page: 2 });

  assert.equal(urls[0], `${DEFAULT_BASE_URL}/api/products?q=mouse+pad&page=2&size=${LIST_PAGE_SIZE}&sort=name`);
  assert.deepEqual(result, { items: [{ id: 1 }], total: 120 });
});

test("ordersApi.search falls back to the row count without X-Total-Count", async (t) => {
  const urls = [];
  const original = globalThis.fetch;
  globalThis.fetch = async (url) => {
    urls.push(url);
    return { status: 200, ok: true, statusText: "OK", text: async () => '[{"id":3},{"id":2}]' };
  };
  t.after(() => {
    globalThis.fetch = original;
  });

  const result = await ordersApi.search({ status: "PENDING", size: 10 });

  assert.equal(urls[0], `${DEFAULT_BASE_URL}/api/orders?status=PENDING&page=0&size=10`);
  assert.equal(result.total, 2);
});

test("purchaseOrdersApi.receive posts the lines and note to the receipts endpoint", async (t) => {
  const calls = mockFetch(t, { body: { id: 4 } });

  await purchaseOrdersApi.receive(4, [{ lineId: 9, quantity: 30 }], "Delivery note 881");

  assert.deepEqual(calls, [
    {
      url: `${DEFAULT_BASE_URL}/api/purchase-orders/4/receipts`,
      method: "POST",
      body: { lines: [{ lineId: 9, quantity: 30 }], note: "Delivery note 881" },
    },
  ]);
});

test("restockApi posts to the product and reorder-list endpoints", async (t) => {
  const calls = mockFetch(t, { body: [] });

  await restockApi.restock(12);
  await restockApi.restockAll();

  assert.deepEqual(
    calls.map((c) => [c.method, c.url]),
    [
      ["POST", `${DEFAULT_BASE_URL}/api/products/12/restock`],
      ["POST", `${DEFAULT_BASE_URL}/api/reorder-list/restock`],
    ]
  );
});

test("fileNameFrom reads the name from Content-Disposition", () => {
  assert.equal(fileNameFrom('attachment; filename="products-2026-10-17.csv"'), "products-2026-10-17.csv");
  assert.equal(fileNameFrom("attachment; filename=top.csv"), "top.csv");
  assert.equal(fileNameFrom(""), "export.csv");
});

test("exportApi.csv returns the file instead of parsing JSON", async (t) => {
  const urls = [];
  const original = globalThis.fetch;
  globalThis.fetch = async (url) => {
    urls.push(url);
    return {
      status: 200,
      ok: true,
      statusText: "OK",
      headers: { get: (name) => (name === "Content-Disposition" ? 'attachment; filename="stock-movements-2026-10-17.csv"' : null) },
      blob: async () => "csv-bytes",
      text: async () => "id,created_at\r\n",
    };
  };
  t.after(() => {
    globalThis.fetch = original;
  });

  const file = await exportApi.csv("stock-movements", { from: "2026-10-01", to: undefined });

  assert.equal(urls[0], `${DEFAULT_BASE_URL}/api/export/stock-movements.csv?from=2026-10-01`);
  assert.deepEqual(file, { blob: "csv-bytes", fileName: "stock-movements-2026-10-17.csv" });
});

test("purchaseOrdersApi.cancelRemaining and productsApi.setComponents hit the right endpoints", async (t) => {
  const calls = mockFetch(t, { body: {} });

  await purchaseOrdersApi.cancelRemaining(4, 9);
  await productsApi.setComponents(6, [{ componentId: 3, quantity: 2 }]);

  assert.deepEqual(calls, [
    { url: `${DEFAULT_BASE_URL}/api/purchase-orders/4/lines/9/cancel-remaining`, method: "POST", body: undefined },
    { url: `${DEFAULT_BASE_URL}/api/products/6/components`, method: "PUT", body: { components: [{ componentId: 3, quantity: 2 }] } },
  ]);
});


test("customersApi.list sends the search text only when there is one", async (t) => {
  const calls = mockFetch(t, { body: [] });

  await customersApi.list("ann lee");
  await customersApi.list();

  assert.equal(calls[0].url, `${DEFAULT_BASE_URL}/api/customers?q=ann+lee`);
  assert.equal(calls[1].url, `${DEFAULT_BASE_URL}/api/customers`);
});

test("ordersApi sends the customer on create, in the list filter and when changing it", async (t) => {
  const calls = mockFetch(t, { body: { id: 1 } });

  await ordersApi.create([{ productId: 1, quantity: 2 }], undefined, 7);
  await ordersApi.create([]);
  await ordersApi.setCustomer(5, 7);
  await ordersApi.setCustomer(5, null);
  await ordersApi.search({ customerId: 7, size: 10 });

  assert.deepEqual(calls[0].body, { customerId: 7, items: [{ productId: 1, quantity: 2 }] });
  assert.deepEqual(calls[1].body, { items: [] });
  assert.deepEqual(calls[2], {
    url: `${DEFAULT_BASE_URL}/api/orders/5/customer`,
    method: "PATCH",
    body: { customerId: 7 },
  });
  assert.deepEqual(calls[3].body, { customerId: null });
  assert.equal(calls[4].url, `${DEFAULT_BASE_URL}/api/orders?customerId=7&page=0&size=10`);
});
