// Reports view: stock value (at price and at cost, per category), stock changes and top sellers for a date range,
// and CSV downloads.

import { exportApi, reportsApi } from "./api.js";
import { defaultReportRange, reasonLabel } from "./helpers.js";
import {
  escapeHtml,
  formatMoney,
  formatNumber,
  renderPlaceholder,
  saveFile,
  setFieldError,
  showError,
  showLoadError,
  toast,
} from "./ui.js";

const el = (id) => document.getElementById(id);
let range = defaultReportRange();
let loadRequest = 0;

export function initReports() {
  const form = el("reports-range");
  form.elements.from.value = range.from;
  form.elements.to.value = range.to;
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const from = form.elements.from.value;
    const to = form.elements.to.value;
    setFieldError(form, "to", "");
    if (from && to && from > to) {
      setFieldError(form, "to", "The end date must be on or after the start date");
      return;
    }
    range = { from: from || undefined, to: to || undefined };
    loadReports();
  });
  el("view-reports").addEventListener("click", (event) => {
    const button = event.target.closest("button[data-export]");
    if (button) download(button);
  });
}

export function showReports() {
  return loadReports();
}

async function loadReports() {
  const requestId = ++loadRequest;
  ["reports-stock-value", "reports-movements", "reports-top-sellers"].forEach((id) => {
    if (!el(id).children.length) renderPlaceholder(el(id), "loading", "Loading…");
  });
  try {
    const [value, movements, sellers] = await Promise.all([
      reportsApi.stockValue(),
      reportsApi.movements(range),
      reportsApi.topSellers({ ...range, limit: 10 }),
    ]);
    if (requestId !== loadRequest) return;
    renderStockValue(value);
    renderMovements(movements);
    renderTopSellers(sellers);
    el("reports-range-label").textContent = describeRange();
  } catch (error) {
    if (requestId !== loadRequest) return;
    ["reports-stock-value", "reports-movements", "reports-top-sellers"].forEach((id) =>
      renderPlaceholder(el(id), "error", "Unavailable")
    );
    showLoadError(el("reports-stock-value"), error, "Could not load the reports");
  }
}

function describeRange() {
  if (range.from && range.to) return `${range.from} to ${range.to}`;
  if (range.from) return `from ${range.from}`;
  if (range.to) return `up to ${range.to}`;
  return "all time";
}

function renderStockValue(value) {
  const t = value.total;
  const rows = value.categories
    .map(
      (c) => `
      <tr>
        <td>${c.categoryName == null ? `<span class="muted">No category</span>` : escapeHtml(c.categoryName)}</td>
        <td class="num">${escapeHtml(formatNumber(c.products))}</td>
        <td class="num">${escapeHtml(formatNumber(c.units))}</td>
        <td class="num">${formatMoney(c.valueAtPrice)}</td>
        <td class="num">${formatMoney(c.valueAtCost)}${c.productsWithoutCost ? ` <span class="muted" title="Products without a unit cost">*</span>` : ""}</td>
      </tr>`
    )
    .join("");
  el("reports-stock-value").innerHTML = `
    <div class="stats stats-compact">
      <div class="stat"><span class="stat-label">Units in stock</span><span class="stat-value">${escapeHtml(formatNumber(t.units))}</span></div>
      <div class="stat"><span class="stat-label">Value at selling price</span><span class="stat-value">${escapeHtml(formatMoney(t.valueAtPrice))}</span></div>
      <div class="stat"><span class="stat-label">Value at cost</span><span class="stat-value">${escapeHtml(formatMoney(t.valueAtCost))}</span></div>
    </div>
    ${
      t.productsWithoutCost
        ? `<p class="hint">* ${escapeHtml(formatNumber(t.productsWithoutCost))} ${t.productsWithoutCost === 1 ? "product has" : "products have"} no unit cost and count as 0 at cost.</p>`
        : ""
    }
    ${
      rows
        ? `<div class="table-wrap"><table class="table table-compact">
             <thead><tr><th>Category</th><th class="num">Products</th><th class="num">Units</th><th class="num">At price</th><th class="num">At cost</th></tr></thead>
             <tbody>${rows}</tbody>
           </table></div>`
        : `<div class="placeholder placeholder-empty">No products yet.</div>`
    }`;
}

function renderMovements(summary) {
  if (!summary.length) {
    renderPlaceholder(el("reports-movements"), "empty", "No stock changes in this period.");
    return;
  }
  el("reports-movements").innerHTML = `
    <div class="table-wrap"><table class="table table-compact">
      <thead><tr><th>Reason</th><th class="num">Changes</th><th class="num">Units in</th><th class="num">Units out</th></tr></thead>
      <tbody>${summary
        .map(
          (s) => `
          <tr>
            <td>${escapeHtml(reasonLabel(s.reason))}</td>
            <td class="num">${escapeHtml(formatNumber(s.movements))}</td>
            <td class="num">${s.unitsIn ? `+${escapeHtml(formatNumber(s.unitsIn))}` : "0"}</td>
            <td class="num">${s.unitsOut ? `−${escapeHtml(formatNumber(s.unitsOut))}` : "0"}</td>
          </tr>`
        )
        .join("")}</tbody>
    </table></div>`;
}

function renderTopSellers(sellers) {
  if (!sellers.length) {
    renderPlaceholder(el("reports-top-sellers"), "empty", "No shipped or delivered orders in this period.");
    return;
  }
  el("reports-top-sellers").innerHTML = `
    <div class="table-wrap"><table class="table table-compact">
      <thead><tr><th>#</th><th>Product</th><th class="num">Units sold</th><th class="num">Revenue</th></tr></thead>
      <tbody>${sellers
        .map(
          (s, i) => `
          <tr>
            <td>${i + 1}</td>
            <td><div class="cell-title">${escapeHtml(s.name)}</div><div class="cell-sub">${escapeHtml(s.sku)}</div></td>
            <td class="num">${escapeHtml(formatNumber(s.unitsSold))}</td>
            <td class="num">${formatMoney(s.revenue)}</td>
          </tr>`
        )
        .join("")}</tbody>
    </table></div>`;
}

async function download(button) {
  const name = button.dataset.export;
  // Products and stock value are "now"; movements and top sellers use the chosen range.
  const params = name === "stock-movements" || name === "top-sellers" ? { ...range } : {};
  if (name === "top-sellers") params.limit = 100;
  button.disabled = true;
  try {
    const { blob, fileName } = await exportApi.csv(name, params);
    saveFile(blob, fileName);
    toast(`Downloaded ${fileName}`, { type: "success" });
  } catch (error) {
    showError(error, "Could not download the CSV file");
  } finally {
    button.disabled = false;
  }
}
