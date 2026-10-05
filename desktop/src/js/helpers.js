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
  PRODUCTION: "Production",
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
 * What a movement refers to. Returns { label, href } for an order (href opens it in the Orders view),
 * { label, href: null } for a purchase order, or null when there is no reference.
 * The order may have been deleted since; the movement keeps its id either way.
 */
export function movementReference(movement) {
  if (movement && movement.orderId != null) {
    return { label: `Order #${movement.orderId}`, href: `#/orders/${encodeURIComponent(movement.orderId)}` };
  }
  if (movement && movement.purchaseOrderId != null) {
    return { label: `Purchase order #${movement.purchaseOrderId}`, href: null };
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
export function productRequestBody({ name, sku, description, price, quantity }, { editing = false, version } = {}) {
  const body = { name, sku, description: description || null, price };
  if (!editing) body.quantity = quantity;
  if (editing && version != null) body.version = version;
  return body;
}
