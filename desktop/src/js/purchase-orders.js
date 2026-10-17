// Purchase orders view: list, create and edit drafts, order, receive (in parts), close, cancel, delete.

import {
  ApiError,
  LIST_PAGE_SIZE,
  PURCHASE_ORDER_STATUSES,
  PURCHASE_ORDER_TRANSITIONS,
  productsApi,
  purchaseOrdersApi,
  suppliersApi,
} from "./api.js";
import { countLabel, statusLabel } from "./helpers.js";
import { parseWholeNumber } from "./products.js";
import {
  confirmDialog,
  escapeHtml,
  formatDateTime,
  formatMoney,
  formatNumber,
  openDialog,
  renderPlaceholder,
  setFieldError,
  setFormError,
  showError,
  showLoadError,
  statusBadge,
  toast,
} from "./ui.js";

const STATUS_ACTIONS = {
  ORDERED: { label: "Mark as ordered", style: "button-primary" },
  CANCELLED: { label: "Cancel purchase order", style: "button-danger-ghost", confirm: true },
  CLOSED: { label: "Close (rest won't arrive)", style: "button", confirm: true },
};

let orders = [];
let total = 0;
let nextPage = 0;
let loadRequest = 0;
let statusFilter = "";
let supplierFilter = "";
let current = null;
let detailRequest = 0;

const listPanel = () => document.getElementById("po-list-panel");
const detailPanel = () => document.getElementById("po-detail-panel");
const tableEl = () => document.getElementById("po-table");
const countEl = () => document.getElementById("po-count");

export function initPurchaseOrders() {
  const status = document.getElementById("po-status-filter");
  status.innerHTML =
    `<option value="">All statuses</option>` +
    PURCHASE_ORDER_STATUSES.map((s) => `<option value="${s}">${escapeHtml(statusLabel(s))}</option>`).join("");
  status.addEventListener("change", () => {
    statusFilter = status.value;
    loadOrders();
  });
  const supplier = document.getElementById("po-supplier-filter");
  supplier.addEventListener("change", () => {
    supplierFilter = supplier.value;
    loadOrders();
  });

  document.getElementById("po-new").addEventListener("click", () => openPurchaseOrderDialog(null));
  tableEl().addEventListener("click", (event) => {
    if (event.target.closest('[data-action="more"]')) {
      loadOrders({ more: true });
      return;
    }
    const row = event.target.closest("tr[data-id]");
    if (row) goTo(row.dataset.id);
  });
  detailPanel().addEventListener("click", onDetailClick);
}

/** Shows the list, or one purchase order when id is given (#/purchase-orders/12). */
export function showPurchaseOrders(id) {
  if (id) {
    listPanel().hidden = true;
    detailPanel().hidden = false;
    return loadDetail(id);
  }
  current = null;
  detailRequest++;
  detailPanel().hidden = true;
  detailPanel().innerHTML = "";
  listPanel().hidden = false;
  loadSupplierFilter();
  return loadOrders();
}

function goTo(id) {
  window.location.hash = `#/purchase-orders/${encodeURIComponent(id)}`;
}

// ---------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------

async function loadSupplierFilter() {
  const select = document.getElementById("po-supplier-filter");
  try {
    const suppliers = await suppliersApi.list();
    select.innerHTML =
      `<option value="">All suppliers</option>` +
      suppliers.map((s) => `<option value="${escapeHtml(s.id)}">${escapeHtml(s.name)}</option>`).join("");
    select.value = supplierFilter;
  } catch {
    // The list still works without the filter; a network problem shows in the connection banner.
  }
}

async function loadOrders({ more = false } = {}) {
  const requestId = ++loadRequest;
  const page = more ? nextPage : 0;
  if (!more && !orders.length) renderPlaceholder(tableEl(), "loading", "Loading purchase orders…");
  try {
    const result = await purchaseOrdersApi.search({
      status: statusFilter || undefined,
      supplierId: supplierFilter || undefined,
      page,
      size: LIST_PAGE_SIZE,
    });
    if (requestId !== loadRequest) return;
    orders = more ? [...orders, ...result.items] : result.items;
    total = result.total;
    nextPage = page + 1;
    renderList();
  } catch (error) {
    if (requestId !== loadRequest) return;
    if (more) {
      showError(error, "Could not load more purchase orders");
      return;
    }
    orders = [];
    total = 0;
    countEl().textContent = "";
    showLoadError(tableEl(), error, "Could not load purchase orders");
  }
}

/** The order's total cost, or a dash when no line has a unit cost (so it doesn't read as free). */
function costCell(order) {
  const anyCost = (order.lines || []).some((l) => l.unitCost != null);
  return anyCost ? formatMoney(order.totalCost) : `<span class="muted">—</span>`;
}

function lineSummary(order) {
  const lines = order.lines || [];
  if (!lines.length) return `<span class="muted">No lines</span>`;
  const ordered = lines.reduce((sum, l) => sum + Number(l.quantityOrdered), 0);
  const received = lines.reduce((sum, l) => sum + Number(l.quantityReceived), 0);
  const units = received ? `${formatNumber(received)} of ${formatNumber(ordered)} units in` : `${formatNumber(ordered)} units`;
  return `${lines.length} ${lines.length === 1 ? "line" : "lines"} · ${units}`;
}

function renderList() {
  countEl().textContent = countLabel(orders.length, total, "purchase order");
  if (!orders.length) {
    renderPlaceholder(
      tableEl(),
      "empty",
      statusFilter || supplierFilter
        ? "No purchase orders match these filters."
        : "No purchase orders yet. Click “New purchase order”, or use Restock on the dashboard."
    );
    return;
  }
  const rows = orders
    .map(
      (o) => `
      <tr class="clickable" data-id="${escapeHtml(o.id)}">
        <td><strong>#${escapeHtml(o.id)}</strong></td>
        <td>${escapeHtml(o.supplierName)}</td>
        <td>${lineSummary(o)}</td>
        <td class="num">${costCell(o)}</td>
        <td>${statusBadge(o.status)}</td>
        <td class="hide-narrow">${escapeHtml(formatDateTime(o.orderedAt || o.createdAt))}</td>
      </tr>`
    )
    .join("");
  tableEl().innerHTML = `
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>PO</th><th>Supplier</th><th>Lines</th><th class="num">Cost</th><th>Status</th><th class="hide-narrow">Ordered / created</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>
    ${
      orders.length < total
        ? `<div class="list-more"><button type="button" class="button" data-action="more">Load more</button></div>`
        : ""
    }`;
}

// ---------------------------------------------------------------------------
// Detail
// ---------------------------------------------------------------------------

async function loadDetail(id, { quiet = false } = {}) {
  const requestId = ++detailRequest;
  if (!quiet || !current) renderPlaceholder(detailPanel(), "loading", `Loading purchase order #${id}…`);
  try {
    const order = await purchaseOrdersApi.get(id);
    if (requestId !== detailRequest) return;
    current = order;
    renderDetail();
  } catch (error) {
    if (requestId !== detailRequest) return;
    current = null;
    showLoadError(detailPanel(), error, `Could not load purchase order #${id}`);
    detailPanel().insertAdjacentHTML("afterbegin", `<a href="#/purchase-orders" class="back-link">← All purchase orders</a>`);
  }
}

function renderDetail() {
  const o = current;
  const canReceive = o.status === "ORDERED" || o.status === "PARTIALLY_RECEIVED";
  const buttons = [
    o.status === "DRAFT" ? `<button type="button" class="button" data-action="edit">Edit</button>` : "",
    canReceive ? `<button type="button" class="button button-primary" data-action="receive">Receive stock</button>` : "",
    ...(PURCHASE_ORDER_TRANSITIONS[o.status] || []).map((status) => {
      const action = STATUS_ACTIONS[status];
      return `<button type="button" class="button ${action.style}" data-action="status" data-status="${status}">${escapeHtml(action.label)}</button>`;
    }),
    o.status === "DRAFT" || o.status === "CANCELLED"
      ? `<button type="button" class="button button-danger-ghost" data-action="delete">Delete</button>`
      : "",
  ].join("");

  const rows = (o.lines || [])
    .map(
      (l) => `
      <tr>
        <td><div class="cell-title">${escapeHtml(l.productName)}</div><div class="cell-sub">${escapeHtml(l.productSku)}</div></td>
        <td class="num">${escapeHtml(formatNumber(l.quantityOrdered))}</td>
        <td class="num">${escapeHtml(formatNumber(l.quantityReceived))}</td>
        <td class="num">${l.quantityRemaining > 0 ? `<strong>${escapeHtml(formatNumber(l.quantityRemaining))}</strong>` : "0"}</td>
        <td class="num">${l.unitCost == null ? `<span class="muted">—</span>` : formatMoney(l.unitCost)}</td>
        <td class="num">${l.lineTotal == null ? `<span class="muted">—</span>` : formatMoney(l.lineTotal)}</td>
      </tr>`
    )
    .join("");
  const linesTable = o.lines && o.lines.length
    ? `<div class="table-wrap">
         <table class="table">
           <thead><tr><th>Product</th><th class="num">Ordered</th><th class="num">Received</th><th class="num">Still to come</th><th class="num">Unit cost</th><th class="num">Line total</th></tr></thead>
           <tbody>${rows}</tbody>
           <tfoot><tr><td colspan="5" class="num">Total cost</td><td class="num total">${costCell(o)}</td></tr></tfoot>
         </table>
       </div>`
    : `<div class="placeholder placeholder-empty">No lines yet. Click Edit to add products.</div>`;

  detailPanel().innerHTML = `
    <a href="#/purchase-orders" class="back-link">← All purchase orders</a>
    <div class="view-header">
      <div>
        <h1>Purchase order #${escapeHtml(o.id)} ${statusBadge(o.status)}</h1>
        <p class="muted">${escapeHtml(o.supplierName)} · Created ${escapeHtml(formatDateTime(o.createdAt))}${
          o.orderedAt ? ` · Ordered ${escapeHtml(formatDateTime(o.orderedAt))}` : ""
        }</p>
      </div>
      <div class="actions">${buttons}</div>
    </div>
    ${o.notes ? `<div class="card"><p class="po-notes">${escapeHtml(o.notes)}</p></div>` : ""}
    <div class="card card-flush">${linesTable}</div>`;
}

async function onDetailClick(event) {
  const button = event.target.closest("button[data-action]");
  if (!button || !current) return;
  const o = current;
  const action = button.dataset.action;
  if (action === "edit") {
    const saved = await openPurchaseOrderDialog(o);
    if (saved) {
      current = saved;
      renderDetail();
    }
  } else if (action === "receive") {
    const updated = await openReceiveDialog(o);
    if (updated) {
      current = updated;
      renderDetail();
    }
  } else if (action === "status") {
    const status = button.dataset.status;
    if (STATUS_ACTIONS[status].confirm) {
      const ok = await confirmDialog({
        title: STATUS_ACTIONS[status].label,
        message: status === "CLOSED"
          ? `Close purchase order #${o.id}? What hasn't arrived yet will no longer be expected.`
          : `Cancel purchase order #${o.id}?`,
        confirmLabel: STATUS_ACTIONS[status].label,
        danger: status === "CANCELLED",
      });
      if (!ok) return;
    }
    button.disabled = true;
    try {
      current = await purchaseOrdersApi.setStatus(o.id, status);
      toast(`Purchase order #${o.id} is now ${statusLabel(current.status).toLowerCase()}`, { type: "success" });
      renderDetail();
    } catch (error) {
      button.disabled = false;
      showError(error, `Could not update purchase order #${o.id}`);
    }
  } else if (action === "delete") {
    const ok = await confirmDialog({
      title: "Delete purchase order",
      message: `Delete purchase order #${o.id}? This cannot be undone.`,
      confirmLabel: "Delete",
      danger: true,
    });
    if (!ok) return;
    try {
      await purchaseOrdersApi.remove(o.id);
      toast(`Deleted purchase order #${o.id}`, { type: "success" });
      window.location.hash = "#/purchase-orders";
    } catch (error) {
      showError(error, `Could not delete purchase order #${o.id}`);
    }
  }
}

// ---------------------------------------------------------------------------
// Create / edit a draft
// ---------------------------------------------------------------------------

/** Product choices for purchasing and production: every product, out of stock included. */
export function productChoices(products, selectedId) {
  const sorted = [...products].sort((a, b) => String(a.name).localeCompare(String(b.name)));
  return (
    `<option value="">Select a product…</option>` +
    sorted
      .map((p) => {
        const selected = String(p.id) === String(selectedId) ? " selected" : "";
        return `<option value="${escapeHtml(p.id)}"${selected}>${escapeHtml(`${p.name} (${p.sku}) · ${p.quantity} in stock`)}</option>`;
      })
      .join("")
  );
}

function lineRow(products, line = {}) {
  return `
    <div class="po-line" data-line>
      <label class="field">
        <span class="field-label">Product</span>
        <select name="productId">${productChoices(products, line.productId)}</select>
      </label>
      <label class="field">
        <span class="field-label">Quantity</span>
        <input name="quantity" type="number" min="1" step="1" value="${escapeHtml(line.quantityOrdered ?? "")}">
      </label>
      <label class="field">
        <span class="field-label">Unit cost</span>
        <input name="unitCost" type="number" min="0" step="0.01" value="${escapeHtml(line.unitCost ?? "")}" placeholder="Optional">
      </label>
      <button type="button" class="button button-small button-danger-ghost" data-action="remove-line" aria-label="Remove line">Remove</button>
    </div>`;
}

/** Validates the line rows. Returns the lines, or null after showing what is wrong. */
function readLines(form) {
  const lines = [];
  const problems = [];
  const seen = new Set();
  form.querySelectorAll("[data-line]").forEach((row, index) => {
    const productId = row.querySelector('[name="productId"]').value;
    const quantityText = row.querySelector('[name="quantity"]').value.trim();
    const costText = row.querySelector('[name="unitCost"]').value.trim();
    const n = index + 1;
    if (!productId && !quantityText && !costText) return; // an untouched empty row
    if (!productId) problems.push(`Line ${n}: choose a product`);
    else if (seen.has(productId)) problems.push(`Line ${n}: this product is already on another line`);
    seen.add(productId);
    const quantity = parseWholeNumber(quantityText);
    if (Number.isNaN(quantity) || quantity < 1) problems.push(`Line ${n}: quantity must be a whole number of 1 or more`);
    if (costText && !/^\d+(\.\d{1,2})?$/.test(costText)) problems.push(`Line ${n}: unit cost must be a number with at most 2 decimals`);
    lines.push({ productId: Number(productId), quantity, unitCost: costText ? Number(costText) : null });
  });
  if (problems.length) {
    setFormError(form, "Please fix the lines below.", problems);
    return null;
  }
  return lines;
}

/** Creates a draft (order null) or edits one. Resolves with the saved purchase order, or null. */
async function openPurchaseOrderDialog(order) {
  let suppliers;
  let products;
  try {
    [suppliers, products] = await Promise.all([suppliersApi.list(), productsApi.list()]);
  } catch (error) {
    showError(error, "Could not load suppliers and products");
    return null;
  }
  if (!suppliers.length) {
    toast("Add a supplier first (Suppliers in the menu).", { type: "error" });
    return null;
  }
  const editing = Boolean(order);
  const existing = editing && order.lines.length ? order.lines : [{}];
  const saved = await openDialog({
    title: editing ? `Edit purchase order #${order.id}` : "New purchase order",
    submitLabel: editing ? "Save draft" : "Create draft",
    wide: true,
    body: `
      <div class="form-grid">
        <label class="field">
          <span class="field-label">Supplier <span class="req">*</span></span>
          <select name="supplierId">
            <option value="">Select a supplier…</option>
            ${suppliers
              .map((s) => `<option value="${escapeHtml(s.id)}" ${editing && s.id === order.supplierId ? "selected" : ""}>${escapeHtml(s.name)}</option>`)
              .join("")}
          </select>
          <span class="field-error" data-error-for="supplierId"></span>
        </label>
        <label class="field">
          <span class="field-label">Notes</span>
          <input name="notes" maxlength="1000" value="${escapeHtml(editing ? order.notes ?? "" : "")}">
        </label>
      </div>
      <h3 class="section-title">Lines</h3>
      <div class="po-lines" data-lines>${existing.map((line) => lineRow(products, line)).join("")}</div>
      <button type="button" class="button button-small" data-action="add-line">Add line</button>
      <p class="hint">Saved as a draft. Nothing is ordered until you click “Mark as ordered”.</p>`,
    onOpen(form) {
      const container = form.querySelector("[data-lines]");
      form.addEventListener("click", (event) => {
        if (event.target.closest('[data-action="add-line"]')) {
          container.insertAdjacentHTML("beforeend", lineRow(products));
        } else if (event.target.closest('[data-action="remove-line"]')) {
          event.target.closest("[data-line]").remove();
        }
      });
    },
    async onSubmit(form) {
      const supplierId = form.elements.supplierId.value;
      if (!supplierId) {
        setFieldError(form, "supplierId", "Choose a supplier");
        return false;
      }
      const lines = readLines(form);
      if (!lines) return false;
      const body = { supplierId: Number(supplierId), notes: form.elements.notes.value.trim() || null, lines };
      if (editing) body.version = order.version;
      try {
        return editing ? await purchaseOrdersApi.update(order.id, body) : await purchaseOrdersApi.create(body);
      } catch (error) {
        if (editing && error instanceof ApiError && error.status === 409) {
          throw new Error("This purchase order was changed elsewhere. Close the dialog and refresh to see the latest version.");
        }
        throw error;
      }
    },
  });
  if (saved) {
    toast(editing ? `Saved purchase order #${saved.id}` : `Created draft purchase order #${saved.id}`, { type: "success" });
    if (!editing) goTo(saved.id);
  }
  return saved;
}

// ---------------------------------------------------------------------------
// Receive
// ---------------------------------------------------------------------------

/** Books received quantities. Resolves with the updated purchase order, or null. */
function openReceiveDialog(order) {
  const open = order.lines.filter((l) => l.quantityRemaining > 0);
  const rows = open
    .map(
      (l) => `
      <tr>
        <td><div class="cell-title">${escapeHtml(l.productName)}</div><div class="cell-sub">${escapeHtml(l.productSku)}</div></td>
        <td class="num">${escapeHtml(formatNumber(l.quantityRemaining))}</td>
        <td class="num">
          <input name="line-${escapeHtml(l.id)}" data-line-id="${escapeHtml(l.id)}" data-max="${escapeHtml(l.quantityRemaining)}"
                 type="number" min="0" max="${escapeHtml(l.quantityRemaining)}" step="1" value="${escapeHtml(l.quantityRemaining)}"
                 aria-label="Quantity received for ${escapeHtml(l.productName)}">
        </td>
      </tr>`
    )
    .join("");
  return openDialog({
    title: `Receive stock: purchase order #${order.id}`,
    submitLabel: "Book receipt",
    wide: true,
    body: `
      <p class="muted">Enter what arrived. Leave a line at 0 if nothing came for it; you can receive the rest later.</p>
      <div class="table-wrap">
        <table class="table receive-table">
          <thead><tr><th>Product</th><th class="num">Still to come</th><th class="num">Received now</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
      <label class="field">
        <span class="field-label">Note</span>
        <input name="note" maxlength="500" placeholder="Optional, e.g. delivery note number">
      </label>`,
    async onSubmit(form) {
      const lines = [];
      const problems = [];
      form.querySelectorAll("input[data-line-id]").forEach((input) => {
        const text = input.value.trim() || "0";
        const quantity = parseWholeNumber(text);
        const max = Number(input.dataset.max);
        if (Number.isNaN(quantity)) problems.push("Quantities must be whole numbers of 0 or more");
        else if (quantity > max) problems.push(`At most ${max} can still arrive for one of the lines`);
        else if (quantity > 0) lines.push({ lineId: Number(input.dataset.lineId), quantity });
      });
      if (problems.length) {
        setFormError(form, "Please fix the quantities.", [...new Set(problems)]);
        return false;
      }
      if (!lines.length) {
        setFormError(form, "Enter at least one quantity above 0.");
        return false;
      }
      const updated = await purchaseOrdersApi.receive(order.id, lines, form.elements.note.value.trim() || null);
      const units = lines.reduce((sum, l) => sum + l.quantity, 0);
      toast(`Received ${formatNumber(units)} units on purchase order #${order.id}`, { type: "success" });
      return updated;
    },
  });
}
