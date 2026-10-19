// Pure helpers for stock notes, the movement ledger and backups.
// No DOM or network access, so the node tests in desktop/test can import this file directly.

/** Same limit as StockMovement.NOTE_MAX_LENGTH in the backend. */
export const NOTE_MAX_LENGTH = 500;

/**
 * Checks the note for a manual stock adjustment. The backend rejects a missing or blank note,
 * so this catches it before the request is sent.
 * Returns { note, error }: the trimmed note, and an error message or null.
 */
export function validateNote(value) {
  const note = String(value ?? "").trim();
  if (!note) {
    return { note, error: "A note is required for manual stock adjustments" };
  }
  if (note.length > NOTE_MAX_LENGTH) {
    return { note, error: `Note must be at most ${NOTE_MAX_LENGTH} characters` };
  }
  return { note, error: null };
}

/** Reasons in the stock ledger (MovementReason in the backend), with labels for the UI. */
export const MOVEMENT_REASONS = {
  SALE: "Sale",
  CANCEL: "Returned to stock",
  RECEIPT: "Receipt",
  RETURN: "Customer return",
  PRODUCTION: "Production",
  CONSUMPTION: "Used in production",
  ADJUSTMENT: "Adjustment",
};

export function reasonLabel(reason) {
  return MOVEMENT_REASONS[reason] || String(reason || "Unknown");
}

/** Signed change for display: "+5", "−3" (with a real minus sign), "0". */
export function formatDelta(delta) {
  const number = Number(delta);
  if (!Number.isFinite(number)) return "—";
  if (number > 0) return `+${number}`;
  if (number < 0) return `−${Math.abs(number)}`;
  return "0";
}

/**
 * What a movement refers to: { label, href } for an order, purchase order or production run (href opens it),
 * or null when there is no reference. A pending order may have been deleted since; the movement keeps its id.
 */
export function movementReference(movement) {
  if (movement && movement.orderId != null) {
    return { label: `Order #${movement.orderId}`, href: `#/orders/${encodeURIComponent(movement.orderId)}` };
  }
  if (movement && movement.purchaseOrderId != null) {
    return {
      label: `Purchase order #${movement.purchaseOrderId}`,
      href: `#/purchase-orders/${encodeURIComponent(movement.purchaseOrderId)}`,
    };
  }
  if (movement && movement.productionRunId != null) {
    return { label: `Production run #${movement.productionRunId}`, href: "#/production" };
  }
  return null;
}

/** Newest first, by createdAt and then id. The backend already sorts this way; this keeps merged pages in order. */
export function sortMovementsNewestFirst(movements) {
  return [...(movements || [])].sort((a, b) => {
    const byTime = String(b.createdAt || "").localeCompare(String(a.createdAt || ""));
    return byTime !== 0 ? byTime : Number(b.id || 0) - Number(a.id || 0);
  });
}

/** "3 products", or "50 of 120 products" while not every page is loaded. */
export function countLabel(shown, total, singular, plural = `${singular}s`) {
  const noun = total === 1 ? singular : plural;
  return shown < total ? `${shown} of ${total} ${noun}` : `${total} ${noun}`;
}

/** True when a page of results was full, so there may be another page after it. */
export function hasMorePages(pageRows, pageSize) {
  return Array.isArray(pageRows) && pageRows.length >= pageSize;
}

/** Human-readable size: "512 bytes", "20.0 KB", "3.4 MB". */
export function formatFileSize(bytes) {
  const number = Number(bytes);
  if (!Number.isFinite(number) || number < 0) return "—";
  if (number < 1024) return `${number} ${number === 1 ? "byte" : "bytes"}`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = number / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(1)} ${units[unit]}`;
}

/**
 * Turns the POST /api/backup response into display text:
 * { title, rows: [[label, value], ...] } where the values are plain text.
 */
export function describeBackup(result) {
  const r = result || {};
  const rows = [
    ["File", r.fileName || "—"],
    ["Saved to", r.path || "—"],
    ["Size", formatFileSize(r.sizeBytes)],
  ];
  if (r.createdAt) {
    const date = new Date(r.createdAt);
    rows.push(["Created", Number.isNaN(date.getTime()) ? String(r.createdAt) : date.toLocaleString()]);
  }
  return { title: `Backup saved: ${r.fileName || "file created"}`, rows };
}

/**
 * Request body for POST/PUT /api/products from the form values.
 * When editing, quantity is left out: the backend rejects a changed quantity on PUT,
 * because stock only changes through the stock endpoint (which records a movement).
 */
export function productRequestBody(
  {
    name,
    sku,
    description,
    price,
    quantity,
    source = "BUY",
    supplierId = null,
    reorderPoint = null,
    reorderQuantity = null,
    categoryId = null,
    unitCost = null,
  },
  { editing = false, version } = {}
) {
  // PUT replaces the sourcing fields too, so they are always sent. Made products have no supplier.
  const body = {
    name,
    sku,
    description: description || null,
    price,
    source,
    supplierId: source === "MAKE" ? null : supplierId,
    reorderPoint,
    reorderQuantity,
    categoryId,
    unitCost,
  };
  if (!editing) body.quantity = quantity;
  if (editing && version != null) body.version = version;
  return body;
}

/** "Partially received" for PARTIALLY_RECEIVED. */
export function statusLabel(status) {
  const text = String(status || "").toLowerCase().replace(/_/g, " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** One line per restock result, e.g. "BOLT-1: Added 50 to draft purchase order #4". */
export function describeRestockResults(results) {
  const list = Array.isArray(results) ? results : [];
  const acted = list.filter((r) => r.action !== "SKIPPED" && r.added);
  return {
    title: acted.length
      ? `Restocked ${acted.length} ${acted.length === 1 ? "product" : "products"}`
      : "Nothing new to restock",
    lines: list.map((r) => `${r.sku}: ${r.message}`),
  };
}

/** "YYYY-MM-DD" for a Date, in local time (what the date inputs and the API use). */
export function isoDate(date) {
  const pad = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** The default report range: the first day of this month up to today. */
export function defaultReportRange(today = new Date()) {
  return { from: isoDate(new Date(today.getFullYear(), today.getMonth(), 1)), to: isoDate(today) };
}


/**
 * Builds the stocktake request lines from the products on the count sheet and the counts typed in (product id ->
 * text). Blank counts are skipped (not counted). Returns { lines, invalid, changed }: the request lines, with the
 * stock the sheet showed as expectedQuantity; the ids whose count isn't a whole number of 0 or more; and how many
 * counted products differ from the recorded stock.
 */
export function stocktakeLines(products, counts) {
  const lines = [];
  const invalid = [];
  let changed = 0;
  for (const product of products) {
    const text = String(counts.get(String(product.id)) ?? "").trim();
    if (!text) continue;
    if (!/^\d+$/.test(text) || !Number.isSafeInteger(Number(text))) {
      invalid.push(product.id);
      continue;
    }
    const counted = Number(text);
    const expected = Number(product.quantity ?? 0);
    if (counted !== expected) changed++;
    lines.push({ productId: product.id, countedQuantity: counted, expectedQuantity: expected });
  }
  return { lines, invalid, changed };
}
