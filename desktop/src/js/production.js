// Production view: plan, edit, complete (adds stock), cancel and delete production runs.

import { LIST_PAGE_SIZE, PRODUCTION_RUN_STATUSES, productionRunsApi, productsApi } from "./api.js";
import { countLabel, statusLabel } from "./helpers.js";
import { parseWholeNumber } from "./products.js";
import { productChoices } from "./purchase-orders.js";
import {
  confirmDialog,
  escapeHtml,
  formatDateTime,
  formatNumber,
  openDialog,
  renderPlaceholder,
  setFieldError,
  showError,
  showLoadError,
  statusBadge,
  toast,
} from "./ui.js";

let runs = [];
let total = 0;
let nextPage = 0;
let loadRequest = 0;
let statusFilter = "";

const tableEl = () => document.getElementById("production-table");
const countEl = () => document.getElementById("production-count");

export function initProduction() {
  const filter = document.getElementById("production-status-filter");
  filter.innerHTML =
    `<option value="">All statuses</option>` +
    PRODUCTION_RUN_STATUSES.map((s) => `<option value="${s}">${escapeHtml(statusLabel(s))}</option>`).join("");
  filter.addEventListener("change", () => {
    statusFilter = filter.value;
    loadRuns();
  });
  document.getElementById("production-new").addEventListener("click", () => openRunDialog(null));
  tableEl().addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    if (button.dataset.action === "more") {
      loadRuns({ more: true });
      return;
    }
    const run = runs.find((r) => String(r.id) === button.dataset.id);
    if (!run) return;
    if (button.dataset.action === "complete") completeRun(run, button);
    else if (button.dataset.action === "edit") openRunDialog(run);
    else if (button.dataset.action === "cancel") cancelRun(run);
    else if (button.dataset.action === "delete") deleteRun(run);
  });
}

export function showProduction() {
  return loadRuns();
}

async function loadRuns({ more = false } = {}) {
  const requestId = ++loadRequest;
  const page = more ? nextPage : 0;
  if (!more && !runs.length) renderPlaceholder(tableEl(), "loading", "Loading production runs…");
  try {
    const result = await productionRunsApi.search({ status: statusFilter || undefined, page, size: LIST_PAGE_SIZE });
    if (requestId !== loadRequest) return;
    runs = more ? [...runs, ...result.items] : result.items;
    total = result.total;
    nextPage = page + 1;
    renderTable();
  } catch (error) {
    if (requestId !== loadRequest) return;
    if (more) {
      showError(error, "Could not load more production runs");
      return;
    }
    runs = [];
    total = 0;
    countEl().textContent = "";
    showLoadError(tableEl(), error, "Could not load production runs");
  }
}

function actions(run) {
  const id = escapeHtml(run.id);
  if (run.status === "PLANNED") {
    return `
      <button type="button" class="button button-small button-primary" data-action="complete" data-id="${id}">Complete</button>
      <button type="button" class="button button-small" data-action="edit" data-id="${id}">Edit</button>
      <button type="button" class="button button-small button-danger-ghost" data-action="cancel" data-id="${id}">Cancel</button>`;
  }
  if (run.status === "CANCELLED") {
    return `<button type="button" class="button button-small button-danger-ghost" data-action="delete" data-id="${id}">Delete</button>`;
  }
  return "";
}

function renderTable() {
  countEl().textContent = countLabel(runs.length, total, "production run");
  if (!runs.length) {
    renderPlaceholder(
      tableEl(),
      "empty",
      statusFilter ? "No production runs with this status." : "No production runs yet. Click “New production run”, or use Restock on the dashboard."
    );
    return;
  }
  const rows = runs
    .map(
      (r) => `
      <tr>
        <td><strong>#${escapeHtml(r.id)}</strong></td>
        <td>
          <div class="cell-title">${escapeHtml(r.productName)}</div>
          <div class="cell-sub">${escapeHtml(r.productSku)}${r.notes ? ` · ${escapeHtml(r.notes)}` : ""}</div>
        </td>
        <td class="num">${escapeHtml(formatNumber(r.quantity))}</td>
        <td>${statusBadge(r.status)}</td>
        <td class="hide-narrow">${escapeHtml(formatDateTime(r.completedAt || r.createdAt))}</td>
        <td class="row-actions">${actions(r)}</td>
      </tr>`
    )
    .join("");
  tableEl().innerHTML = `
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>Run</th><th>Product</th><th class="num">Quantity</th><th>Status</th><th class="hide-narrow">Completed / planned</th><th class="row-actions"><span class="sr-only">Actions</span></th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>
    ${
      runs.length < total
        ? `<div class="list-more"><button type="button" class="button" data-action="more">Load more</button></div>`
        : ""
    }`;
}

async function openRunDialog(run) {
  let products;
  try {
    products = await productsApi.list();
  } catch (error) {
    showError(error, "Could not load products");
    return;
  }
  // Products made in-house first, as they are the usual choice.
  const made = products.filter((p) => p.source === "MAKE");
  const choices = made.length && !run ? made : products;
  const editing = Boolean(run);
  const saved = await openDialog({
    title: editing ? `Edit production run #${run.id}` : "New production run",
    submitLabel: editing ? "Save" : "Plan run",
    body: `
      <div class="form-grid">
        <label class="field span-2">
          <span class="field-label">Product <span class="req">*</span></span>
          <select name="productId">${productChoices(choices, run?.productId)}</select>
          <span class="field-error" data-error-for="productId"></span>
          ${!editing && made.length ? `<span class="hint">Showing products marked “Made in-house”.</span>` : ""}
        </label>
        <label class="field">
          <span class="field-label">Quantity <span class="req">*</span></span>
          <input name="quantity" type="number" min="1" step="1" value="${escapeHtml(run?.quantity ?? "")}">
          <span class="field-error" data-error-for="quantity"></span>
        </label>
        <label class="field span-2">
          <span class="field-label">Notes</span>
          <input name="notes" maxlength="1000" value="${escapeHtml(run?.notes ?? "")}">
        </label>
      </div>
      <p class="hint">Stock goes up only when you click Complete.</p>`,
    async onSubmit(form) {
      const productId = form.elements.productId.value;
      const quantity = parseWholeNumber(form.elements.quantity.value);
      let ok = true;
      if (!productId) {
        setFieldError(form, "productId", "Choose a product");
        ok = false;
      }
      if (Number.isNaN(quantity) || quantity < 1) {
        setFieldError(form, "quantity", "Enter a whole number of 1 or more");
        ok = false;
      }
      if (!ok) return false;
      const body = { productId: Number(productId), quantity, notes: form.elements.notes.value.trim() || null };
      if (editing) body.version = run.version;
      return editing ? productionRunsApi.update(run.id, body) : productionRunsApi.create(body);
    },
  });
  if (saved) {
    toast(editing ? `Saved production run #${saved.id}` : `Planned production run #${saved.id}`, { type: "success" });
    loadRuns();
  }
}

async function completeRun(run, button) {
  const ok = await confirmDialog({
    title: "Complete production run",
    message: `Add ${run.quantity} × ${run.productName} to stock and mark run #${run.id} as completed?`,
    confirmLabel: "Complete",
  });
  if (!ok) return;
  button.disabled = true;
  try {
    await productionRunsApi.setStatus(run.id, "COMPLETED");
    toast(`Added ${formatNumber(run.quantity)} × ${run.productName} to stock`, { type: "success" });
    loadRuns();
  } catch (error) {
    button.disabled = false;
    showError(error, `Could not complete production run #${run.id}`);
  }
}

async function cancelRun(run) {
  const ok = await confirmDialog({
    title: "Cancel production run",
    message: `Cancel production run #${run.id}? Stock doesn't change.`,
    confirmLabel: "Cancel run",
    danger: true,
  });
  if (!ok) return;
  try {
    await productionRunsApi.setStatus(run.id, "CANCELLED");
    toast(`Cancelled production run #${run.id}`, { type: "success" });
    loadRuns();
  } catch (error) {
    showError(error, `Could not cancel production run #${run.id}`);
  }
}

async function deleteRun(run) {
  const ok = await confirmDialog({
    title: "Delete production run",
    message: `Delete cancelled production run #${run.id}? This cannot be undone.`,
    confirmLabel: "Delete",
    danger: true,
  });
  if (!ok) return;
  try {
    await productionRunsApi.remove(run.id);
    toast(`Deleted production run #${run.id}`, { type: "success" });
    loadRuns();
  } catch (error) {
    showError(error, `Could not delete production run #${run.id}`);
  }
}
