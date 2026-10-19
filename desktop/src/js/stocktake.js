// Stocktake view: a count sheet of every product, where the counts typed in are booked as adjustments.

import { ApiError, productsApi, stocktakeApi } from "./api.js";
import { stocktakeLines } from "./helpers.js";
import {
  clearFieldErrors,
  confirmDialog,
  escapeHtml,
  formatNumber,
  renderPlaceholder,
  setFieldError,
  showError,
  showLoadError,
  toast,
} from "./ui.js";

let products = [];
const counts = new Map(); // product id -> the count as typed; kept while the sheet is filtered or reloaded
let filterText = "";
let loadRequest = 0;

const el = (id) => document.getElementById(id);

export function initStocktake() {
  el("stocktake-search").addEventListener("input", (event) => {
    filterText = event.target.value.trim().toLowerCase();
    renderSheet();
  });
  el("stocktake-reload").addEventListener("click", () => loadSheet());
  el("stocktake-table").addEventListener("input", (event) => {
    const input = event.target.closest("input[data-id]");
    if (!input) return;
    counts.set(input.dataset.id, input.value);
    input.classList.remove("invalid");
    updateRow(input.closest("tr"), input.dataset.id);
    updateSummary();
  });
  el("stocktake-form").addEventListener("submit", (event) => {
    event.preventDefault();
    submit(event.target);
  });
}

export function showStocktake() {
  return loadSheet();
}

async function loadSheet() {
  const requestId = ++loadRequest;
  if (!products.length) renderPlaceholder(el("stocktake-table"), "loading", "Loading products…");
  try {
    const list = await productsApi.list();
    if (requestId !== loadRequest) return;
    products = [...list].sort((a, b) => String(a.name).localeCompare(String(b.name)));
    renderSheet();
  } catch (error) {
    if (requestId !== loadRequest) return;
    products = [];
    el("stocktake-summary").textContent = "";
    showLoadError(el("stocktake-table"), error, "Could not load products");
  }
}

function differenceCell(product) {
  const text = String(counts.get(String(product.id)) ?? "").trim();
  if (!text) return `<span class="muted">—</span>`;
  if (!/^\d+$/.test(text)) return `<span class="text-danger">Whole number</span>`;
  const delta = Number(text) - Number(product.quantity ?? 0);
  if (delta === 0) return `<span class="muted">Matches</span>`;
  return `<strong class="${delta > 0 ? "text-success" : "text-danger"}">${delta > 0 ? "+" : "−"}${formatNumber(Math.abs(delta))}</strong>`;
}

function renderSheet() {
  updateSummary();
  if (!products.length) {
    renderPlaceholder(el("stocktake-table"), "empty", "No products yet. Add products before counting stock.");
    return;
  }
  const shown = filterText
    ? products.filter((p) => `${p.name} ${p.sku}`.toLowerCase().includes(filterText))
    : products;
  if (!shown.length) {
    renderPlaceholder(el("stocktake-table"), "empty", "No products match.");
    return;
  }
  const rows = shown
    .map(
      (p) => `
      <tr data-row="${escapeHtml(p.id)}">
        <td><div class="cell-title">${escapeHtml(p.name)}</div><div class="cell-sub">${escapeHtml(p.sku)}</div></td>
        <td class="num">${escapeHtml(formatNumber(p.quantity ?? 0))}</td>
        <td class="num">
          <input class="count-input" type="text" inputmode="numeric" data-id="${escapeHtml(p.id)}"
                 value="${escapeHtml(counts.get(String(p.id)) ?? "")}" aria-label="Counted ${escapeHtml(p.name)}">
        </td>
        <td class="num" data-diff>${differenceCell(p)}</td>
      </tr>`
    )
    .join("");
  el("stocktake-table").innerHTML = `
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>Product</th><th class="num">Recorded</th><th class="num">Counted</th><th class="num">Difference</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>`;
}

function updateRow(row, productId) {
  const product = products.find((p) => String(p.id) === productId);
  if (row && product) row.querySelector("[data-diff]").innerHTML = differenceCell(product);
}

function updateSummary() {
  const { lines, changed } = stocktakeLines(products, counts);
  el("stocktake-summary").textContent = products.length
    ? `${formatNumber(lines.length)} of ${formatNumber(products.length)} products counted · ${formatNumber(changed)} ${changed === 1 ? "difference" : "differences"}`
    : "";
}

async function submit(form) {
  clearFieldErrors(form);
  const note = form.elements.note.value.trim();
  const { lines, invalid, changed } = stocktakeLines(products, counts);
  if (invalid.length) {
    invalid.forEach((id) => el("stocktake-table").querySelector(`input[data-id="${CSS.escape(String(id))}"]`)?.classList.add("invalid"));
    toast("Counts must be whole numbers of 0 or more.", { type: "error" });
    return;
  }
  if (!lines.length) {
    toast("Type a count for at least one product.", { type: "error" });
    return;
  }
  if (!note) {
    setFieldError(form, "note", "A note is required for a stocktake");
    return;
  }
  const confirmed = await confirmDialog({
    title: "Book stocktake",
    message: changed
      ? `Set ${changed} ${changed === 1 ? "product" : "products"} to the counted stock? Each difference is recorded as an adjustment with the note “${note}”.`
      : `All ${lines.length} counted ${lines.length === 1 ? "product matches" : "products match"} the recorded stock, so nothing will change. Book it anyway?`,
    confirmLabel: changed ? "Book differences" : "Book",
  });
  if (!confirmed) return;
  const button = el("stocktake-submit");
  button.disabled = true;
  try {
    const result = await stocktakeApi.apply(note, lines);
    counts.clear();
    form.elements.note.value = "";
    toast(
      result.changes.length
        ? `Stocktake booked: ${result.changes.length} ${result.changes.length === 1 ? "product" : "products"} changed`
        : "Stocktake booked: everything matched",
      { type: "success" }
    );
    await loadSheet();
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      // Someone sold or received stock while counting: show the new recorded numbers and keep the counts.
      showError(error, "Stock changed while counting");
      await loadSheet();
    } else {
      showError(error, "Could not book the stocktake");
    }
  } finally {
    button.disabled = false;
  }
}
