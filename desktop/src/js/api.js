// Thin wrapper around fetch() for the Inventix REST API (see docs/API.md).
// Works in the Tauri webview and in a normal browser; it does not need window.__TAURI__.

export const DEFAULT_BASE_URL = "http://localhost:8080";
const STORAGE_KEY = "inventix.apiBaseUrl";
const REQUEST_TIMEOUT_MS = 10000;

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
  constructor(baseUrl, cause, timedOut = false) {
    super(
      timedOut
        ? `Backend at ${baseUrl} did not respond within ${REQUEST_TIMEOUT_MS / 1000} seconds`
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

async function request(path, { method = "GET", body } = {}) {
  const baseUrl = getBaseUrl();
  const headers = { Accept: "application/json" };
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
  }, REQUEST_TIMEOUT_MS);
  init.signal = controller.signal;

  let response;
  try {
    response = await fetch(baseUrl + path, init);
  } catch (cause) {
    const error = new NetworkError(baseUrl, cause, timedOut);
    notifyConnection(false, error);
    throw error;
  } finally {
    clearTimeout(timer);
  }

  notifyConnection(true);

  if (response.status === 204) {
    return null;
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
  return data;
}

const id = (value) => encodeURIComponent(String(value));

// ---------------------------------------------------------------------------
// Endpoints
// ---------------------------------------------------------------------------

export const productsApi = {
  list: () => request("/api/products"),
  lowStock: (threshold = 10) =>
    request(`/api/products/low-stock?threshold=${encodeURIComponent(threshold)}`),
  get: (productId) => request(`/api/products/${id(productId)}`),
  /** product: { name, sku, description, price, quantity } */
  create: (product) => request("/api/products", { method: "POST", body: product }),
  /** product: same as create, plus optional `version` (from the loaded product) to detect concurrent edits. */
  update: (productId, product) =>
    request(`/api/products/${id(productId)}`, { method: "PUT", body: product }),
  /** delta > 0 restocks, delta < 0 removes stock. */
  adjustStock: (productId, delta) =>
    request(`/api/products/${id(productId)}/stock`, { method: "PATCH", body: { delta } }),
  remove: (productId) => request(`/api/products/${id(productId)}`, { method: "DELETE" }),
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
  list: (status) =>
    request(status ? `/api/orders?status=${encodeURIComponent(status)}` : "/api/orders"),
  get: (orderId) => request(`/api/orders/${id(orderId)}`),
  /** items: [{ productId, quantity }] */
  create: (items = []) => request("/api/orders", { method: "POST", body: { items } }),
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
