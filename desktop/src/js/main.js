// Entry point: routing between views, connection status and the settings view.

import {
  DEFAULT_BASE_URL,
  backupApi,
  getBaseUrl,
  normalizeBaseUrl,
  onBaseUrlChange,
  onConnectionChange,
  productsApi,
  setBaseUrl,
} from "./api.js";
import { initDashboard, showDashboard } from "./dashboard.js";
import { describeBackup, formatFileSize } from "./helpers.js";
import { initOrders, showOrders } from "./orders.js";
import { initProduction, showProduction } from "./production.js";
import { initProducts, showProducts } from "./products.js";
import { initPurchaseOrders, showPurchaseOrders } from "./purchase-orders.js";
import { initReports, showReports } from "./reports.js";
import {
  DEFAULT_LOW_STOCK_THRESHOLD,
  getLowStockThreshold,
  setLowStockThreshold,
} from "./settings.js";
import { initCustomers, showCustomers } from "./customers.js";
import { initSuppliers, showSuppliers } from "./suppliers.js";
import {
  clearFieldErrors,
  confirmDialog,
  describeError,
  escapeHtml,
  formatDateTime,
  renderPlaceholder,
  setFieldError,
  showError,
  toast,
} from "./ui.js";

const VIEWS = {
  dashboard: showDashboard,
  products: showProducts,
  orders: showOrders,
  customers: showCustomers,
  "purchase-orders": showPurchaseOrders,
  production: showProduction,
  suppliers: showSuppliers,
  reports: showReports,
  settings: showSettings,
};

const VIEW_TITLES = {
  dashboard: "Dashboard",
  products: "Products",
  orders: "Orders",
  customers: "Customers",
  "purchase-orders": "Purchase orders",
  production: "Production",
  suppliers: "Suppliers",
  reports: "Reports",
  settings: "Settings",
};

const el = (id) => document.getElementById(id);

// ---------------------------------------------------------------------------
// Routing (#/dashboard, #/products, #/orders, #/orders/12, #/customers, #/purchase-orders/3, #/production, #/suppliers,
// #/settings)
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
  document.title = `${VIEW_TITLES[view]} · Inventix`;
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
  el("backup-now").addEventListener("click", () => runBackup());
  el("backup-list").addEventListener("click", async (event) => {
    const button = event.target.closest('button[data-action="restore"]');
    if (!button) return;
    const fileName = button.dataset.file;
    const ok = await confirmDialog({
      title: "Restore this backup?",
      message: `${fileName} will replace the current data the next time the backend starts. The current database is kept as a separate file, so nothing is lost.`,
      confirmLabel: "Restore on next start",
      danger: true,
    });
    if (!ok) return;
    try {
      renderBackupStatus(await backupApi.restore(fileName));
      toast("Restore scheduled. Restart the backend to finish it.", { type: "success" });
    } catch (error) {
      showError(error, "Could not schedule the restore");
    }
  });
  el("restore-pending").addEventListener("click", async (event) => {
    if (!event.target.closest('[data-action="cancel-restore"]')) return;
    try {
      renderBackupStatus(await backupApi.cancelRestore());
      toast("Restore cancelled", { type: "success" });
    } catch (error) {
      showError(error, "Could not cancel the restore");
    }
  });
}

async function loadBackupStatus() {
  try {
    renderBackupStatus(await backupApi.status());
  } catch (error) {
    renderPlaceholder(el("backup-list"), "error", "Backups are unavailable");
  }
}

function renderBackupStatus(status) {
  el("backup-locations").innerHTML = [
    ["Database file", status.databaseFile || "Not a file (in-memory database)"],
    ["Backup folder", status.backupDir],
  ]
    .map(([name, value]) => `<dt>${escapeHtml(name)}</dt><dd><code>${escapeHtml(value)}</code></dd>`)
    .join("");
  const pending = el("restore-pending");
  pending.hidden = !status.pendingRestore;
  pending.innerHTML = status.pendingRestore
    ? `<div class="banner-body"><strong>Restore pending</strong><p>${escapeHtml(status.pendingRestore)} will be restored the next time the backend starts.</p></div>
       <button type="button" class="button" data-action="cancel-restore">Cancel restore</button>`
    : "";
  const list = el("backup-list");
  if (!status.backups.length) {
    renderPlaceholder(list, "empty", "No backups yet.");
    return;
  }
  list.innerHTML = `
    <div class="table-wrap"><table class="table table-compact">
      <thead><tr><th>File</th><th>Made</th><th class="num">Size</th><th></th></tr></thead>
      <tbody>${status.backups
        .map(
          (b) => `
          <tr>
            <td><code>${escapeHtml(b.fileName)}</code></td>
            <td>${escapeHtml(formatDateTime(b.createdAt))}</td>
            <td class="num">${escapeHtml(formatFileSize(b.sizeBytes))}</td>
            <td class="row-actions">
              <button type="button" class="button button-small" data-action="restore" data-file="${escapeHtml(b.fileName)}"
                ${!status.databaseFile ? "disabled" : ""}>Restore…</button>
            </td>
          </tr>`
        )
        .join("")}</tbody>
    </table></div>`;
}

function showSettings() {
  const form = el("settings-form");
  clearFieldErrors(form);
  form.elements.baseUrl.value = getBaseUrl();
  form.elements.threshold.value = getLowStockThreshold();
  loadBackupStatus();
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

async function runBackup() {
  const button = el("backup-now");
  const result = el("backup-result");
  const label = button.textContent;
  button.disabled = true;
  button.textContent = "Backing up…";
  result.hidden = true;
  try {
    const backup = await backupApi.create();
    const { title, rows } = describeBackup(backup);
    result.className = "result-success";
    result.innerHTML = `
      <strong>${escapeHtml(title)}</strong>
      <dl class="info-list">
        ${rows.map(([name, value]) => `<dt>${escapeHtml(name)}</dt><dd>${escapeHtml(value)}</dd>`).join("")}
      </dl>`;
    toast(title, { type: "success" });
    loadBackupStatus();
  } catch (error) {
    // The server's message says why, e.g. the backend uses an in-memory database (409).
    const { message, details } = describeError(error);
    result.className = "form-error";
    result.innerHTML = `
      <div>Backup failed. ${escapeHtml(message)}</div>
      ${details.length ? `<ul>${details.map((line) => `<li>${escapeHtml(line)}</li>`).join("")}</ul>` : ""}`;
  } finally {
    result.hidden = false;
    button.disabled = false;
    button.textContent = label;
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
  initCustomers();
  initPurchaseOrders();
  initProduction();
  initSuppliers();
  initReports();
  initSettings();
  window.addEventListener("hashchange", () => route());
  route();
}

start();
