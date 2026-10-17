// Thin wrapper around fetch() for the Inventix REST API (see docs/API.md).
// Works in the Tauri webview and in a normal browser; it does not need window.__TAURI__.

export const DEFAULT_BASE_URL = "http://localhost:8080";
const STORAGE_KEY = "inventix.apiBaseUrl";
const REQUEST_TIMEOUT_MS = 10000;
const TOTAL_COUNT_HEADER = "X-Total-Count";
/** Rows per page in the product and order lists. */
export const LIST_PAGE_SIZE = 50;

/** Error returned by the backend (4xx/5xx) with the JSON error body from docs/API.md. */
export class ApiError extends Error {
  constructor(status, body, fallbackText) {
    const message = (body && body.message) || fallbackText || `Request failed with status ${status}`;
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.error = body && body.error ? body.error : "";
    this.path = body && body.path ? body.path : "";
    this.fieldErrors = body && Array.isArray(body.fieldErrors) ? body.fieldErrors : [];
  }
}

/** The request never got a response: backend down, wrong URL, blocked by CORS/CSP, or timed out. */
export class NetworkError extends Error {
  constructor(baseUrl, cause, timedOut = false, timeoutMs = REQUEST_TIMEOUT_MS) {
    super(
      timedOut
        ? `Backend at ${baseUrl} did not respond within ${timeoutMs / 1000} seconds`
        : `Backend not reachable at ${baseUrl}`
    );
    this.name = "NetworkError";
    this.baseUrl = baseUrl;
    this.cause = cause;
    this.timedOut = timedOut;
  }
}

// ---------------------------------------------------------------------------
// Base URL
// ---------------------------------------------------------------------------

/** Checks a user-entered URL and returns it without a trailing slash. Throws on invalid input. */
export function normalizeBaseUrl(value) {
  const text = String(value || "").trim();
  let url;
  try {
    url = new URL(text);
  } catch {
    throw new Error(`"${text}" is not a valid URL. Example: ${DEFAULT_BASE_URL}`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error("The backend URL must start with http:// or https://");
  }
  return (url.origin + url.pathname).replace(/\/+$/, "");
}

export function getBaseUrl() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored ? normalizeBaseUrl(stored) : DEFAULT_BASE_URL;
  } catch {
    return DEFAULT_BASE_URL;
  }
}

export function setBaseUrl(value) {
  const normalized = normalizeBaseUrl(value);
  try {
    if (normalized === DEFAULT_BASE_URL) {
      localStorage.removeItem(STORAGE_KEY);
    } else {
      localStorage.setItem(STORAGE_KEY, normalized);
    }
  } catch {
    // localStorage can be unavailable (e.g. privacy mode); the setting then lasts for this session only.
  }
  notifyBaseUrlChange(normalized);
  return normalized;
}

// ---------------------------------------------------------------------------
// Connection status listeners
// ---------------------------------------------------------------------------

const connectionListeners = new Set();
const baseUrlListeners = new Set();

/** Calls listener(true) after any response and listener(false, error) after a network failure. */
export function onConnectionChange(listener) {
  connectionListeners.add(listener);
  return () => connectionListeners.delete(listener);
}

export function onBaseUrlChange(listener) {
  baseUrlListeners.add(listener);
  return () => baseUrlListeners.delete(listener);
}

function notifyConnection(ok, error) {
  connectionListeners.forEach((listener) => listener(ok, error));
}

function notifyBaseUrlChange(url) {
  baseUrlListeners.forEach((listener) => listener(url));
}

// ---------------------------------------------------------------------------
// Core request
// ---------------------------------------------------------------------------

/**
 * Sends a request and resolves with the parsed JSON body. With withTotal, resolves with { items, total } instead,
 * where total comes from the X-Total-Count header (the number of matches across all pages).
 */
async function request(
  path,
  { method = "GET", body, headers: extraHeaders, timeoutMs = REQUEST_TIMEOUT_MS, withTotal = false, asFile = false } = {}
) {
  const baseUrl = getBaseUrl();
  const headers = { Accept: "application/json", ...extraHeaders };
  const init = { method, headers };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }

  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  init.signal = controller.signal;

  let response;
  try {
    response = await fetch(baseUrl + path, init);
  } catch (cause) {
    const error = new NetworkError(baseUrl, cause, timedOut, timeoutMs);
    notifyConnection(false, error);
    throw error;
  } finally {
    clearTimeout(timer);
  }

  notifyConnection(true);

  if (response.status === 204) {
    return null;
  }

  // A download: hand back the bytes and the server's file name instead of parsing JSON.
  if (asFile && response.ok) {
    const disposition = (response.headers && response.headers.get("Content-Disposition")) || "";
    return { blob: await response.blob(), fileName: fileNameFrom(disposition) };
  }

  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }

  if (!response.ok) {
    throw new ApiError(response.status, data, response.statusText);
  }
  if (withTotal) {
    const items = Array.isArray(data) ? data : [];
    const header = response.headers && typeof response.headers.get === "function"
      ? response.headers.get(TOTAL_COUNT_HEADER)
      : null;
    const total = header === null || header === "" || Number.isNaN(Number(header)) ? items.length : Number(header);
    return { items, total };
  }
  return data;
}

const id = (value) => encodeURIComponent(String(value));

/** The file name from a Content-Disposition header such as `attachment; filename="products-2026-10-17.csv"`. */
export function fileNameFrom(disposition) {
  const match = /filename="?([^";]+)"?/i.exec(disposition || "");
  return match ? match[1] : "export.csv";
}

/** A random key for the Idempotency-Key header. */
export function newIdempotencyKey() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

/** "?a=1&b=2" from the params that are set, or "" when none are. */
function query(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : "";
}

// A backup copies the whole database file, which can take longer than a normal request.
const BACKUP_TIMEOUT_MS = 120000;

/** Page size for the movement endpoints (the backend allows 1–500). */
export const MOVEMENTS_PAGE_SIZE = 50;

// ---------------------------------------------------------------------------
// Endpoints
// ---------------------------------------------------------------------------

export const productsApi = {
  /** Every product, unpaged (used where the whole catalogue is needed, e.g. the dashboard and order pickers). */
  list: () => request("/api/products"),
  /**
   * One page of products matching q (name, SKU or description), sorted by sort ("name", "quantity,desc", ...).
   * Resolves with { items, total }. page is 0-based.
   */
  search: ({ q, categoryId, page = 0, size = LIST_PAGE_SIZE, sort = "name" } = {}) =>
    request(`/api/products${query({ q, categoryId, page, size, sort })}`, { withTotal: true }),
  lowStock: (threshold = 10) =>
    request(`/api/products/low-stock?threshold=${encodeURIComponent(threshold)}`),
  get: (productId) => request(`/api/products/${id(productId)}`),
  /** product: { name, sku, description, price, quantity }. A quantity above 0 is recorded as "Initial stock". */
  create: (product) => request("/api/products", { method: "POST", body: product }),
  /**
   * product: { name, sku, description, price } plus optional `version` (from the loaded product) to detect
   * concurrent edits. Leave quantity out: the backend rejects a changed quantity here (use adjustStock).
   */
  update: (productId, product) =>
    request(`/api/products/${id(productId)}`, { method: "PUT", body: product }),
  /** delta > 0 restocks, delta < 0 removes stock. The note is required and ends up in the stock ledger. */
  adjustStock: (productId, delta, note) =>
    request(`/api/products/${id(productId)}/stock`, { method: "PATCH", body: { delta, note } }),
  /** A made product's bill of materials: [{ componentId, name, sku, quantity, inStock }]. */
  components: (productId) => request(`/api/products/${id(productId)}/components`),
  /** components: [{ componentId, quantity }]; replaces the whole list. */
  setComponents: (productId, components) =>
    request(`/api/products/${id(productId)}/components`, { method: "PUT", body: { components } }),
  /** Stock movements for one product, newest first. page is 0-based. */
  movements: (productId, { page = 0, size = MOVEMENTS_PAGE_SIZE } = {}) =>
    request(`/api/products/${id(productId)}/movements${query({ page, size })}`),
  remove: (productId) => request(`/api/products/${id(productId)}`, { method: "DELETE" }),
};

export const stockMovementsApi = {
  /** All movements, newest first. productId and reason (SALE, CANCEL, ...) are optional filters. */
  list: ({ productId, reason, page = 0, size = MOVEMENTS_PAGE_SIZE } = {}) =>
    request(`/api/stock-movements${query({ productId, reason, page, size })}`),
};

export const backupApi = {
  /** Writes a backup zip on the backend machine. Resolves with { fileName, path, sizeBytes, createdAt }. */
  create: () => request("/api/backup", { method: "POST", timeoutMs: BACKUP_TIMEOUT_MS }),
};

export const ORDER_STATUSES = ["PENDING", "SHIPPED", "DELIVERED", "CANCELLED"];

/** Allowed status transitions, mirroring the backend rules in docs/API.md. */
export const STATUS_TRANSITIONS = {
  PENDING: ["SHIPPED", "CANCELLED"],
  SHIPPED: ["DELIVERED"],
  DELIVERED: [],
  CANCELLED: [],
};

export const ordersApi = {
  /** Every order, unpaged, optionally filtered by status. */
  list: (status) =>
    request(status ? `/api/orders?status=${encodeURIComponent(status)}` : "/api/orders"),
  /** One page of orders, newest first, optionally filtered by status. Resolves with { items, total }. */
  search: ({ status, page = 0, size = LIST_PAGE_SIZE } = {}) =>
    request(`/api/orders${query({ status, page, size })}`, { withTotal: true }),
  get: (orderId) => request(`/api/orders/${id(orderId)}`),
  /**
   * items: [{ productId, quantity }]. Pass the same idempotencyKey when retrying a create that may have
   * reached the backend (e.g. after a timeout), so it returns the first order instead of making a second one.
   */
  create: (items = [], idempotencyKey) =>
    request("/api/orders", {
      method: "POST",
      body: { items },
      headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : undefined,
    }),
  replaceItems: (orderId, items) =>
    request(`/api/orders/${id(orderId)}`, { method: "PUT", body: { items } }),
  setStatus: (orderId, status) =>
    request(`/api/orders/${id(orderId)}/status`, { method: "PATCH", body: { status } }),
  remove: (orderId) => request(`/api/orders/${id(orderId)}`, { method: "DELETE" }),

  listItems: (orderId) => request(`/api/orders/${id(orderId)}/items`),
  addItem: (orderId, productId, quantity) =>
    request(`/api/orders/${id(orderId)}/items`, {
      method: "POST",
      body: { productId, quantity },
    }),
  updateItem: (orderId, itemId, quantity) =>
    request(`/api/orders/${id(orderId)}/items/${id(itemId)}`, {
      method: "PUT",
      body: { quantity },
    }),
  removeItem: (orderId, itemId) =>
    request(`/api/orders/${id(orderId)}/items/${id(itemId)}`, { method: "DELETE" }),
};

// ---------------------------------------------------------------------------
// Purchasing and production (v2 Run 2)
// ---------------------------------------------------------------------------

export const suppliersApi = {
  /** All suppliers, by name. */
  list: () => request("/api/suppliers"),
  get: (supplierId) => request(`/api/suppliers/${id(supplierId)}`),
  /** supplier: { name, email, phone, notes } plus `version` on update */
  create: (supplier) => request("/api/suppliers", { method: "POST", body: supplier }),
  update: (supplierId, supplier) => request(`/api/suppliers/${id(supplierId)}`, { method: "PUT", body: supplier }),
  remove: (supplierId) => request(`/api/suppliers/${id(supplierId)}`, { method: "DELETE" }),
};

export const PURCHASE_ORDER_STATUSES = ["DRAFT", "ORDERED", "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED", "CANCELLED"];

/** Status changes a client can request, mirroring the backend (receiving moves an order on by itself). */
export const PURCHASE_ORDER_TRANSITIONS = {
  DRAFT: ["ORDERED", "CANCELLED"],
  ORDERED: ["CANCELLED"],
  PARTIALLY_RECEIVED: ["CLOSED"],
  RECEIVED: [],
  CLOSED: [],
  CANCELLED: [],
};

export const purchaseOrdersApi = {
  /** One page, newest first. Resolves with { items, total }. */
  search: ({ status, supplierId, page = 0, size = LIST_PAGE_SIZE } = {}) =>
    request(`/api/purchase-orders${query({ status, supplierId, page, size })}`, { withTotal: true }),
  get: (purchaseOrderId) => request(`/api/purchase-orders/${id(purchaseOrderId)}`),
  /** body: { supplierId, notes, lines: [{ productId, quantity, unitCost }] } */
  create: (body) => request("/api/purchase-orders", { method: "POST", body }),
  /** Replaces a draft's supplier, notes and lines. */
  update: (purchaseOrderId, body) =>
    request(`/api/purchase-orders/${id(purchaseOrderId)}`, { method: "PUT", body }),
  setStatus: (purchaseOrderId, status) =>
    request(`/api/purchase-orders/${id(purchaseOrderId)}/status`, { method: "PATCH", body: { status } }),
  /** lines: [{ lineId, quantity }] */
  receive: (purchaseOrderId, lines, note) =>
    request(`/api/purchase-orders/${id(purchaseOrderId)}/receipts`, { method: "POST", body: { lines, note } }),
  /** Cancels what is still to come on one line. */
  cancelRemaining: (purchaseOrderId, lineId) =>
    request(`/api/purchase-orders/${id(purchaseOrderId)}/lines/${id(lineId)}/cancel-remaining`, { method: "POST" }),
  remove: (purchaseOrderId) => request(`/api/purchase-orders/${id(purchaseOrderId)}`, { method: "DELETE" }),
};

export const PRODUCTION_RUN_STATUSES = ["PLANNED", "COMPLETED", "CANCELLED"];

export const productionRunsApi = {
  /** One page, newest first. Resolves with { items, total }. */
  search: ({ status, productId, page = 0, size = LIST_PAGE_SIZE } = {}) =>
    request(`/api/production-runs${query({ status, productId, page, size })}`, { withTotal: true }),
  /** body: { productId, quantity, notes } */
  create: (body) => request("/api/production-runs", { method: "POST", body }),
  update: (runId, body) => request(`/api/production-runs/${id(runId)}`, { method: "PUT", body }),
  /** COMPLETED adds the quantity to stock; CANCELLED changes nothing else. */
  setStatus: (runId, status) =>
    request(`/api/production-runs/${id(runId)}/status`, { method: "PATCH", body: { status } }),
  remove: (runId) => request(`/api/production-runs/${id(runId)}`, { method: "DELETE" }),
};

export const restockApi = {
  /** Products at or below their reorder point, with what is on order and what is missing for Restock. */
  reorderList: () => request("/api/reorder-list"),
  /** Adds the product to its supplier's draft purchase order, or plans a production run. */
  restock: (productId) => request(`/api/products/${id(productId)}/restock`, { method: "POST" }),
  /** Restocks every ready product that isn't already covered. Resolves with one result per product. */
  restockAll: () => request("/api/reorder-list/restock", { method: "POST" }),
};

// ---------------------------------------------------------------------------
// Categories, reports and CSV export (v2 Run 3)
// ---------------------------------------------------------------------------

export const categoriesApi = {
  /** All categories by name, each with productCount. */
  list: () => request("/api/categories"),
  create: (name) => request("/api/categories", { method: "POST", body: { name } }),
  rename: (categoryId, name, version) =>
    request(`/api/categories/${id(categoryId)}`, { method: "PUT", body: { name, version } }),
  remove: (categoryId) => request(`/api/categories/${id(categoryId)}`, { method: "DELETE" }),
};

/** from and to are "YYYY-MM-DD" dates, both included; leave either out for an open range. */
export const reportsApi = {
  stockValue: () => request("/api/reports/stock-value"),
  movements: ({ from, to } = {}) => request(`/api/reports/movements${query({ from, to })}`),
  topSellers: ({ from, to, limit = 10 } = {}) => request(`/api/reports/top-sellers${query({ from, to, limit })}`),
};

export const EXPORTS = ["products", "stock-value", "stock-movements", "top-sellers"];

export const exportApi = {
  /** Downloads /api/export/{name}.csv. Resolves with { blob, fileName }. */
  csv: (name, params = {}) => request(`/api/export/${encodeURIComponent(name)}.csv${query(params)}`, { asFile: true }),
};

