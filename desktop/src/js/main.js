// Entry point: routing between views, connection status and the settings view.

import {
  DEFAULT_BASE_URL,
  getBaseUrl,
  normalizeBaseUrl,
  onBaseUrlChange,
  onConnectionChange,
  productsApi,
  setBaseUrl,
} from "./api.js";
import { initDashboard, showDashboard } from "./dashboard.js";
import { initOrders, showOrders } from "./orders.js";
import { initProducts, showProducts } from "./products.js";
import {
  DEFAULT_LOW_STOCK_THRESHOLD,
  getLowStockThreshold,
  setLowStockThreshold,
} from "./settings.js";
import { clearFieldErrors, describeError, setFieldError, showError, toast } from "./ui.js";

const VIEWS = {
  dashboard: showDashboard,
  products: showProducts,
  orders: showOrders,
  settings: showSettings,
};

const el = (id) => document.getElementById(id);

// ---------------------------------------------------------------------------
// Routing (#/dashboard, #/products, #/orders, #/orders/12, #/settings)
// ---------------------------------------------------------------------------

function parseRoute() {
  const parts = window.location.hash.replace(/^#\/?/, "").split("/").filter(Boolean);
  const view = parts[0] && Object.prototype.hasOwnProperty.call(VIEWS, parts[0]) ? parts[0] : "dashboard";
  return { view, param: parts[1] ? decodeURIComponent(parts[1]) : undefined };
}

function route() {
  const { view, param } = parseRoute();
  document.querySelectorAll(".view").forEach((section) => {
    section.hidden = section.dataset.view !== view;
  });
  document.querySelectorAll(".nav-link").forEach((link) => {
    const active = link.dataset.view === view;
    link.classList.toggle("active", active);
    if (active) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
  const title = view.charAt(0).toUpperCase() + view.slice(1);
  document.title = `${title} · Inventix`;
  return VIEWS[view](param);
}

// ---------------------------------------------------------------------------
// Connection status
// ---------------------------------------------------------------------------

function initConnectionStatus() {
  const status = el("connection-status");
  const statusText = status.querySelector(".connection-text");
  const banner = el("connection-banner");

  const showUrl = (url) => {
    status.title = `Backend: ${url}`;
    el("connection-url").textContent = url;
  };
  showUrl(getBaseUrl());
  onBaseUrlChange(showUrl);

  onConnectionChange((ok, error) => {
    status.classList.toggle("online", ok);
    status.classList.toggle("offline", !ok);
    statusText.textContent = ok ? "Connected" : "Offline";
    if (ok) {
      banner.hidden = true;
      return;
    }
    const { message, details } = describeError(error);
    el("connection-banner-title").textContent = message;
    el("connection-banner-text").textContent = details.join(" ");
    banner.hidden = false;
  });

  el("connection-retry").addEventListener("click", () => route());
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

function initSettings() {
  const form = el("settings-form");
  el("settings-environment").textContent = window.__TAURI__ ? "Tauri desktop app" : "Web browser";
  el("settings-origin").textContent = window.location.origin;
  el("settings-default-url").textContent = DEFAULT_BASE_URL;

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    clearFieldErrors(form);
    let url;
    try {
      url = normalizeBaseUrl(form.elements.baseUrl.value);
    } catch (error) {
      setFieldError(form, "baseUrl", error.message);
    }
    const thresholdText = form.elements.threshold.value.trim();
    const threshold = /^\d+$/.test(thresholdText) ? Number(thresholdText) : NaN;
    if (Number.isNaN(threshold)) {
      setFieldError(form, "threshold", "Enter a whole number of 0 or more");
    }
    if (url === undefined || Number.isNaN(threshold)) return;
    form.elements.threshold.value = setLowStockThreshold(threshold);
    form.elements.baseUrl.value = setBaseUrl(url);
    toast("Settings saved", { type: "success" });
    testConnection();
  });

  el("settings-reset").addEventListener("click", () => {
    clearFieldErrors(form);
    form.elements.baseUrl.value = setBaseUrl(DEFAULT_BASE_URL);
    form.elements.threshold.value = setLowStockThreshold(DEFAULT_LOW_STOCK_THRESHOLD);
    toast("Settings reset to defaults", { type: "success" });
  });

  el("settings-test").addEventListener("click", () => testConnection());
}

function showSettings() {
  const form = el("settings-form");
  clearFieldErrors(form);
  form.elements.baseUrl.value = getBaseUrl();
  form.elements.threshold.value = getLowStockThreshold();
}

async function testConnection() {
  const button = el("settings-test");
  button.disabled = true;
  try {
    const products = await productsApi.list();
    toast(`Connected to ${getBaseUrl()} (${products.length} products)`, { type: "success" });
  } catch (error) {
    showError(error, "Connection test failed");
  } finally {
    button.disabled = false;
  }
}

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------

function start() {
  initConnectionStatus();
  initDashboard();
  initProducts();
  initOrders();
  initSettings();
  window.addEventListener("hashchange", () => route());
  route();
}

start();
