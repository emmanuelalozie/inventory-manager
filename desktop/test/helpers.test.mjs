// Tests for the pure UI helpers in src/js/helpers.js (stock notes, movement formatting, backups).
// Run with: npm test (from desktop\)

import test from "node:test";
import assert from "node:assert/strict";

import {
  NOTE_MAX_LENGTH,
  describeBackup,
  formatDelta,
  formatFileSize,
  hasMorePages,
  movementReference,
  productRequestBody,
  reasonLabel,
  sortMovementsNewestFirst,
  validateNote,
} from "../src/js/helpers.js";

test("validateNote requires a non-blank note and trims it", () => {
  for (const blank of [undefined, null, "", "   ", "\n\t"]) {
    const { error } = validateNote(blank);
    assert.equal(error, "A note is required for manual stock adjustments", `blank note: ${JSON.stringify(blank)}`);
  }
  assert.deepEqual(validateNote("  Recount  "), { note: "Recount", error: null });
});

test("validateNote enforces the backend's 500 character limit after trimming", () => {
  assert.equal(NOTE_MAX_LENGTH, 500);
  assert.equal(validateNote("x".repeat(500)).error, null);
  assert.equal(validateNote(`  ${"x".repeat(500)}  `).error, null);
  assert.match(validateNote("x".repeat(501)).error, /at most 500/);
});

test("formatDelta shows a sign on every non-zero change", () => {
  assert.equal(formatDelta(5), "+5");
  assert.equal(formatDelta(-3), "\u22123");
  assert.equal(formatDelta(0), "0");
  assert.equal(formatDelta("12"), "+12");
  assert.equal(formatDelta(undefined), "—");
});

test("reasonLabel covers every ledger reason and falls back to the raw value", () => {
  for (const reason of ["SALE", "CANCEL", "RECEIPT", "PRODUCTION", "ADJUSTMENT"]) {
    const label = reasonLabel(reason);
    assert.ok(label && label !== reason, `${reason} should have a friendly label`);
  }
  assert.equal(reasonLabel("TRANSFER"), "TRANSFER");
  assert.equal(reasonLabel(null), "Unknown");
});

test("movementReference links orders, names purchase orders and ignores plain adjustments", () => {
  assert.deepEqual(movementReference({ orderId: 12, purchaseOrderId: null }), {
    label: "Order #12",
    href: "#/orders/12",
  });
  assert.deepEqual(movementReference({ orderId: null, purchaseOrderId: 7 }), {
    label: "Purchase order #7",
    href: null,
  });
  assert.equal(movementReference({ orderId: null, purchaseOrderId: null }), null);
  assert.equal(movementReference({}), null);
});

test("sortMovementsNewestFirst orders by time, then id, without changing the input", () => {
  const input = [
    { id: 1, createdAt: "2026-10-01T09:00:00" },
    { id: 3, createdAt: "2026-10-04T15:30:00.123456" },
    { id: 2, createdAt: "2026-10-04T15:30:00.123456" },
    { id: 4, createdAt: "2026-10-02T08:00:00" },
  ];
  const sorted = sortMovementsNewestFirst(input);
  assert.deepEqual(sorted.map((m) => m.id), [3, 2, 4, 1]);
  assert.deepEqual(input.map((m) => m.id), [1, 3, 2, 4]);
  assert.deepEqual(sortMovementsNewestFirst(null), []);
});

test("hasMorePages is true only for a full page", () => {
  assert.equal(hasMorePages(new Array(50).fill({}), 50), true);
  assert.equal(hasMorePages(new Array(49).fill({}), 50), false);
  assert.equal(hasMorePages([], 50), false);
  assert.equal(hasMorePages(undefined, 50), false);
});

test("formatFileSize uses bytes, KB and MB", () => {
  assert.equal(formatFileSize(1), "1 byte");
  assert.equal(formatFileSize(512), "512 bytes");
  assert.equal(formatFileSize(20480), "20.0 KB");
  assert.equal(formatFileSize(3.4 * 1024 * 1024), "3.4 MB");
  assert.equal(formatFileSize(-1), "—");
  assert.equal(formatFileSize("nope"), "—");
});

test("describeBackup shows the file name, path and size from POST /api/backup", () => {
  const { title, rows } = describeBackup({
    fileName: "inventix-backup-20261004-153000.zip",
    path: "C:\\inventix\\backups\\inventix-backup-20261004-153000.zip",
    sizeBytes: 20480,
    createdAt: "2026-10-04T15:30:00Z",
  });
  assert.equal(title, "Backup saved: inventix-backup-20261004-153000.zip");
  const byLabel = Object.fromEntries(rows);
  assert.equal(byLabel.File, "inventix-backup-20261004-153000.zip");
  assert.equal(byLabel["Saved to"], "C:\\inventix\\backups\\inventix-backup-20261004-153000.zip");
  assert.equal(byLabel.Size, "20.0 KB");
  assert.ok(byLabel.Created && byLabel.Created !== "—");
});

test("describeBackup copes with a partial response", () => {
  const { title, rows } = describeBackup({});
  assert.equal(title, "Backup saved: file created");
  assert.deepEqual(Object.fromEntries(rows), { File: "—", "Saved to": "—", Size: "—" });
});

test("productRequestBody never sends quantity when editing", () => {
  const values = { name: "Keyboard", sku: "KB-002", description: "", price: 49.99, quantity: 7 };

  const created = productRequestBody(values, { editing: false });
  assert.deepEqual(created, { name: "Keyboard", sku: "KB-002", description: null, price: 49.99, quantity: 7 });

  const updated = productRequestBody(values, { editing: true, version: 3 });
  assert.deepEqual(updated, { name: "Keyboard", sku: "KB-002", description: null, price: 49.99, version: 3 });
  assert.ok(!("quantity" in updated));

  assert.ok(!("version" in productRequestBody(values, { editing: true, version: null })));
});
