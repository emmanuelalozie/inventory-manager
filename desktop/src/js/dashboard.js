// Dashboard: headline counts, low-stock list, orders by status and recent orders.

import { ORDER_STATUSES, ordersApi, productsApi } from "./api.js";
import { openStockDialog } from "./products.js";
import { getLowStockThreshold } from "./settings.js";
import {
  escapeHtml,
  formatDateTime,
  formatMoney,
  formatNumber,
  renderPlaceholder,
  showLoadError,
  statusBadge,
  stockBadge,
} from "./ui.js";

const RECENT_ORDER_COUNT = 5;

let lowStock = [];

const el = (id) => document.getElementById(id);

export function initDashboard() {
  el("dashboard-refresh").addEventListener("click", () => loadDashboard());

  el("dashboard-low-stock").addEventListener("click", async (event) => {
    const button = event.target.closest('button[data-action="restock"]');
    if (!button) return;
    const product = lowStock.find((p) => String(p.id) === button.dataset.id);
    if (!product) return;
    const updated = await openStockDialog(product);
    if (updated) loadDashboard();
  });

  el("dashboard-recent-orders").addEventListener("click", (event) => {
    const row = event.target.closest("tr[data-id]");
    if (row) window.location.hash = `#/orders/${encodeURIComponent(row.dataset.id)}`;
  });
}

export function showDashboard() {
  return loadDashboard();
}

async function loadDashboard() {
  const threshold = getLowStockThreshold();
  el("dashboard-threshold-label").textContent = `≤ ${threshold} units`;

  const containers = ["dashboard-stats", "dashboard-low-stock", "dashboard-order-status", "dashboard-recent-orders"];
  if (!el("dashboard-stats").children.length) {
    containers.forEach((id) => renderPlaceholder(el(id), "loading", "Loading…"));
  }

  let products;
  let orders;
  try {
    [products, lowStock, orders] = await Promise.all([
      productsApi.list(),
      productsApi.lowStock(threshold),
      ordersApi.list(),
    ]);
  } catch (error) {
    lowStock = [];
    containers.forEach((id) => renderPlaceholder(el(id), "error", "Unavailable"));
    showLoadError(el("dashboard-stats"), error, "Could not load the dashboard");
    return;
  }

  renderStats(products, orders);
  renderLowStock(threshold);
  renderOrderStatus(orders);
  renderRecentOrders(orders);
}

function renderStats(products, orders) {
  const units = products.reduce((sum, p) => sum + Number(p.quantity || 0), 0);
  const stockValue = products.reduce((sum, p) => sum + Number(p.price || 0) * Number(p.quantity || 0), 0);
  const pending = orders.filter((o) => o.status === "PENDING").length;
  const fulfilledValue = orders
    .filter((o) => o.status === "SHIPPED" || o.status === "DELIVERED")
    .reduce((sum, o) => sum + Number(o.totalAmount || 0), 0);

  const stats = [
    { label: "Products", value: formatNumber(products.length), href: "#/products" },
    { label: "Units in stock", value: formatNumber(units) },
    { label: "Stock value", value: formatMoney(stockValue) },
    { label: "Low stock", value: formatNumber(lowStock.length), warn: lowStock.length > 0 },
    { label: "Orders", value: formatNumber(orders.length), href: "#/orders" },
    { label: "Pending orders", value: formatNumber(pending) },
    { label: "Shipped + delivered value", value: formatMoney(fulfilledValue) },
  ];

  el("dashboard-stats").innerHTML = stats
    .map((s) => {
      const inner = `<span class="stat-label">${escapeHtml(s.label)}</span><span class="stat-value">${escapeHtml(s.value)}</span>`;
      const cls = `stat${s.warn ? " stat-warn" : ""}`;
      return s.href ? `<a class="${cls} stat-link" href="${s.href}">${inner}</a>` : `<div class="${cls}">${inner}</div>`;
    })
    .join("");
}

function renderLowStock(threshold) {
  const container = el("dashboard-low-stock");
  if (!lowStock.length) {
    renderPlaceholder(container, "empty", `No products at or below ${threshold} units.`);
    return;
  }
  const rows = lowStock
    .map(
      (p) => `
      <tr>
        <td><div class="cell-title">${escapeHtml(p.name)}</div><div class="cell-sub">${escapeHtml(p.sku)}</div></td>
        <td class="num">${stockBadge(p.quantity, threshold)}</td>
        <td class="row-actions">
          <button type="button" class="button button-small" data-action="restock" data-id="${escapeHtml(p.id)}">Restock</button>
        </td>
      </tr>`
    )
    .join("");
  container.innerHTML = `
    <div class="table-wrap">
      <table class="table table-compact">
        <thead><tr><th>Product</th><th class="num">Stock</th><th></th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>`;
}

function renderOrderStatus(orders) {
  const container = el("dashboard-order-status");
  if (!orders.length) {
    renderPlaceholder(container, "empty", "No orders yet.");
    return;
  }
  const counts = Object.fromEntries(ORDER_STATUSES.map((s) => [s, 0]));
  orders.forEach((o) => {
    if (o.status in counts) counts[o.status] += 1;
  });
  const max = Math.max(...Object.values(counts), 1);

  container.innerHTML = `
    <ul class="status-bars">
      ${ORDER_STATUSES.map(
        (s) => `
        <li>
          <a class="status-bar-row" href="#/orders">
            <span class="status-bar-label">${statusBadge(s)}</span>
            <span class="status-bar-track"><span class="status-bar-fill fill-${s.toLowerCase()}" data-ratio="${counts[s] / max}"></span></span>
            <span class="status-bar-count">${counts[s]}</span>
          </a>
        </li>`
      ).join("")}
    </ul>`;
  // Widths are set through the CSSOM because the CSP does not allow inline style attributes.
  container.querySelectorAll(".status-bar-fill").forEach((bar) => {
    bar.style.width = `${Math.round(Number(bar.dataset.ratio) * 100)}%`;
  });
}

function renderRecentOrders(orders) {
  const container = el("dashboard-recent-orders");
  const recent = [...orders].sort((a, b) => b.id - a.id).slice(0, RECENT_ORDER_COUNT);
  if (!recent.length) {
    renderPlaceholder(container, "empty", "No orders yet.");
    return;
  }
  const rows = recent
    .map(
      (o) => `
      <tr class="clickable" data-id="${escapeHtml(o.id)}">
        <td><strong>#${escapeHtml(o.id)}</strong></td>
        <td class="hide-narrow">${escapeHtml(formatDateTime(o.createdAt))}</td>
        <td class="num">${formatMoney(o.totalAmount)}</td>
        <td>${statusBadge(o.status)}</td>
      </tr>`
    )
    .join("");
  container.innerHTML = `
    <div class="table-wrap">
      <table class="table table-compact">
        <thead><tr><th>Order</th><th class="hide-narrow">Created</th><th class="num">Total</th><th>Status</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>`;
}
