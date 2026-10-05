// Tests for the request shapes in src/js/api.js, with fetch replaced by a recorder.
// Run with: npm test (from desktop\)

import test from "node:test";
import assert from "node:assert/strict";

import {
  ApiError,
  DEFAULT_BASE_URL,
  MOVEMENTS_PAGE_SIZE,
  backupApi,
  productsApi,
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
