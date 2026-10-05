// Orders view: list, create, details with items, add/change/remove items, status changes, delete.

import { ApiError, ORDER_STATUSES, STATUS_TRANSITIONS, ordersApi, productsApi } from "./api.js";
import { parseWholeNumber, productOptions } from "./products.js";
import {
  confirmDialog,
  escapeHtml,
  formatDateTime,
  formatMoney,
  formatNumber,
  openDialog,
  renderPlaceholder,
  setFieldError,
  showError,
  showLoadError,
  statusBadge,
  toast,
} from "./ui.js";

const STATUS_ACTIONS = {
  SHIPPED: {
    label: "Mark as shipped",
    style: "button-primary",
    confirm: "Mark this order as shipped? Its items can no longer be changed afterwards.",
  },
  DELIVERED: {
    label: "Mark as delivered",
    style: "button-primary",
    confirm: "Mark this order as delivered? This is a final status.",
  },
  CANCELLED: {
    label: "Cancel order",
    style: "button-danger-ghost",
    confirm: "Cancel this order? The stock for all its items goes back into inventory. This is a final status.",
  },
};

let orders = [];
let statusFilter = "";
let currentOrder = null;
let pickerProducts = [];
let detailRequest = 0;

const listPanel = () => document.getElementById("orders-list-panel");
const detailPanel = () => document.getElementById("order-detail-panel");
const tableEl = () => document.getElementById("orders-table");
const countEl = () => document.getElementById("orders-count");

export function initOrders() {
  const filter = document.getElementById("orders-status-filter");
  filter.innerHTML =
    `<option value="">All statuses</option>` +
    ORDER_STATUSES.map((s) => `<option value="${s}">${s.charAt(0)}${s.slice(1).toLowerCase()}</option>`).join("");
  filter.addEventListener("change", () => {
    statusFilter = filter.value;
    loadOrders();
  });

  document.getElementById("orders-new").addEventListener("click", () => openNewOrderDialog());
  document.getElementById("orders-refresh").addEventListener("click", () => loadOrders());

  tableEl().addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action]");
    if (button) {
      event.stopPropagation();
      const order = orders.find((o) => String(o.id) === button.dataset.id);
      if (!order) return;
      if (button.dataset.action === "view") goToOrder(order.id);
      else if (button.dataset.action === "delete") deleteOrder(order);
      return;
    }
    const row = event.target.closest("tr[data-id]");
    if (row) goToOrder(row.dataset.id);
  });

  detailPanel().addEventListener("click", onDetailClick);
  detailPanel().addEventListener("submit", onDetailSubmit);
}

/** Shows the order list, or the details of one order when orderId is given. */
export function showOrders(orderId) {
  if (orderId) {
    listPanel().hidden = true;
    detailPanel().hidden = false;
    return loadOrderDetail(orderId);
  }
  currentOrder = null;
  detailRequest++; // ignore any order details still loading
  detailPanel().hidden = true;
  detailPanel().innerHTML = "";
  listPanel().hidden = false;
  return loadOrders();
}

function goToOrder(id) {
  window.location.hash = `#/orders/${encodeURIComponent(id)}`;
}

// ---------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------

async function loadOrders() {
  if (!orders.length) renderPlaceholder(tableEl(), "loading", "Loading orders…");
  try {
    orders = await ordersApi.list(statusFilter || undefined);
    renderList();
  } catch (error) {
    orders = [];
    countEl().textContent = "";
    showLoadError(tableEl(), error, "Could not load orders");
  }
}

function itemSummary(order) {
  const items = order.orderItems || [];
  const units = items.reduce((sum, item) => sum + Number(item.quantity || 0), 0);
  if (!items.length) return `<span class="muted">No items</span>`;
  return `${items.length} ${items.length === 1 ? "line" : "lines"} · ${formatNumber(units)} ${units === 1 ? "unit" : "units"}`;
}

function renderList() {
  const sorted = [...orders].sort((a, b) => b.id - a.id);
  countEl().textContent = `${orders.length} ${orders.length === 1 ? "order" : "orders"}`;

  if (!sorted.length) {
    renderPlaceholder(
      tableEl(),
      "empty",
      statusFilter ? `No ${statusFilter.toLowerCase()} orders.` : "No orders yet. Click “New order” to create one."
    );
    return;
  }

  const rows = sorted
    .map(
      (o) => `
      <tr class="clickable" data-id="${escapeHtml(o.id)}">
        <td><strong>#${escapeHtml(o.id)}</strong></td>
        <td class="hide-narrow">${escapeHtml(formatDateTime(o.createdAt))}</td>
        <td>${itemSummary(o)}</td>
        <td class="num">${formatMoney(o.totalAmount)}</td>
        <td>${statusBadge(o.status)}</td>
        <td class="row-actions">
          <button type="button" class="button button-small" data-action="view" data-id="${escapeHtml(o.id)}">View</button>
          <button type="button" class="button button-small button-danger-ghost" data-action="delete" data-id="${escapeHtml(o.id)}">Delete</button>
        </td>
      </tr>`
    )
    .join("");

  tableEl().innerHTML = `
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>Order</th><th class="hide-narrow">Created</th><th>Items</th><th class="num">Total</th><th>Status</th>
            <th class="row-actions"><span class="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    </div>`;
}

// ---------------------------------------------------------------------------
// New order
// ---------------------------------------------------------------------------

async function openNewOrderDialog() {
  let available;
  try {
    available = await productsApi.list();
  } catch (error) {
    showError(error, "Could not load products for the new order");
    return;
  }

  const byId = new Map(available.map((p) => [String(p.id), p]));
  const lines = new Map(); // productId -> quantity

  const created = await openDialog({
    title: "New order",
    submitLabel: "Create order",
    wide: true,
    body: `
      <div class="line-builder">
        <label class="field grow">
          <span class="field-label">Product</span>
          <select name="productId">${productOptions(available)}</select>
          <span class="field-error" data-error-for="productId"></span>
        </label>
        <label class="field qty">
          <span class="field-label">Quantity</span>
          <input name="quantity" type="number" min="1" step="1" value="1">
          <span class="field-error" data-error-for="quantity"></span>
        </label>
        <button type="button" class="button" data-action="add-line">Add to order</button>
      </div>
      <div data-lines></div>
      <p class="hint">Stock is reserved when the order is created. You can also create an empty order and add items later.</p>`,
    onOpen(form) {
      const linesEl = form.querySelector("[data-lines]");

      const renderLines = () => {
        if (!lines.size) {
          linesEl.innerHTML = `<div class="placeholder placeholder-empty">No items added yet.</div>`;
          return;
        }
        let total = 0;
        const rows = [...lines.entries()]
          .map(([productId, quantity]) => {
            const p = byId.get(productId);
            const subtotal = Number(p.price) * quantity;
            total += subtotal;
            return `
              <tr>
                <td><div class="cell-title">${escapeHtml(p.name)}</div><div class="cell-sub">${escapeHtml(p.sku)}</div></td>
                <td class="num">${formatMoney(p.price)}</td>
                <td class="num">${escapeHtml(quantity)}</td>
                <td class="num">${formatMoney(subtotal)}</td>
                <td class="row-actions">
                  <button type="button" class="button button-small button-danger-ghost" data-action="remove-line" data-id="${escapeHtml(productId)}">Remove</button>
                </td>
              </tr>`;
          })
          .join("");
        linesEl.innerHTML = `
          <div class="table-wrap">
            <table class="table table-compact">
              <thead><tr><th>Product</th><th class="num">Unit price</th><th class="num">Qty</th><th class="num">Subtotal</th><th></th></tr></thead>
              <tbody>${rows}</tbody>
              <tfoot><tr><td colspan="3" class="num">Estimated total</td><td class="num"><strong>${formatMoney(total)}</strong></td><td></td></tr></tfoot>
            </table>
          </div>`;
      };

      const addLine = () => {
        form.querySelectorAll(".field-error").forEach((el) => (el.textContent = ""));
        form.querySelectorAll(".invalid").forEach((el) => el.classList.remove("invalid"));
        const productId = form.elements.productId.value;
        const quantity = parseWholeNumber(form.elements.quantity.value);
        const product = byId.get(productId);
        if (!product) {
          setFieldError(form, "productId", "Choose a product");
          return;
        }
        if (Number.isNaN(quantity) || quantity < 1) {
          setFieldError(form, "quantity", "Enter a whole number of 1 or more");
          return;
        }
        const combined = (lines.get(productId) || 0) + quantity;
        if (combined > Number(product.quantity)) {
          setFieldError(form, "quantity", `Only ${product.quantity} in stock`);
          return;
        }
        lines.set(productId, combined);
        form.elements.productId.value = "";
        form.elements.quantity.value = "1";
        renderLines();
        form.elements.productId.focus();
      };

      form.addEventListener("click", (event) => {
        const button = event.target.closest("button[data-action]");
        if (!button) return;
        if (button.dataset.action === "add-line") addLine();
        else if (button.dataset.action === "remove-line") {
          lines.delete(button.dataset.id);
          renderLines();
        }
      });
      // Enter in the picker adds a line instead of submitting the whole order.
      form.querySelector(".line-builder").addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
          event.preventDefault();
          addLine();
        }
      });
      renderLines();
    },
    async onSubmit(form) {
      // A product chosen but not added yet would be lost silently, so ask the user to add it first.
      if (form.elements.productId.value) {
        setFieldError(form, "productId", "Click “Add to order” to include this product, or clear the selection");
        return false;
      }
      const items = [...lines.entries()].map(([productId, quantity]) => ({
        productId: Number(productId),
        quantity,
      }));
      return ordersApi.create(items);
    },
  });

  if (created) {
    toast(`Created order #${created.id}`, { type: "success" });
    goToOrder(created.id);
  }
}

// ---------------------------------------------------------------------------
// Details
// ---------------------------------------------------------------------------

async function loadOrderDetail(orderId, { quiet = false } = {}) {
  const requestId = ++detailRequest;
  if (!quiet || !currentOrder) {
    renderPlaceholder(detailPanel(), "loading", `Loading order #${orderId}…`);
  }
  try {
    const [order, productList] = await Promise.all([ordersApi.get(orderId), productsApi.list()]);
    if (requestId !== detailRequest) return; // the user navigated elsewhere meanwhile
    currentOrder = order;
    pickerProducts = productList;
    renderDetail();
  } catch (error) {
    if (requestId !== detailRequest) return;
    currentOrder = null;
    showLoadError(detailPanel(), error, `Could not load order #${orderId}`);
    detailPanel().insertAdjacentHTML(
      "afterbegin",
      `<a href="#/orders" class="back-link">← All orders</a>`
    );
  }
}

function renderDetail() {
  const order = currentOrder;
  const items = order.orderItems || [];
  const editable = order.status === "PENDING";
  const transitions = STATUS_TRANSITIONS[order.status] || [];

  const statusButtons = transitions
    .map((status) => {
      const action = STATUS_ACTIONS[status];
      return `<button type="button" class="button ${action.style}" data-action="status" data-status="${status}">${escapeHtml(action.label)}</button>`;
    })
    .join("");

  const itemRows = items
    .map((item) => {
      const product = item.product || {};
      const maxQuantity = Number(item.quantity) + Math.max(0, Number(product.quantity) || 0);
      const quantityCell = editable
        ? `<form class="inline-qty" data-form="item-qty" data-item-id="${escapeHtml(item.id)}" novalidate>
             <input name="quantity" type="number" min="1" max="${escapeHtml(maxQuantity)}" step="1" value="${escapeHtml(item.quantity)}" aria-label="Quantity">
             <button type="submit" class="button button-small">Save</button>
           </form>
           <div class="cell-sub">max ${escapeHtml(maxQuantity)}</div>`
        : escapeHtml(item.quantity);
      return `
        <tr>
          <td><div class="cell-title">${escapeHtml(product.name)}</div><div class="cell-sub">${escapeHtml(product.sku)}</div></td>
          <td class="num">${formatMoney(item.pricePerUnit)}</td>
          <td class="num">${quantityCell}</td>
          <td class="num">${formatMoney(item.subtotal)}</td>
          ${
            editable
              ? `<td class="row-actions"><button type="button" class="button button-small button-danger-ghost" data-action="remove-item" data-item-id="${escapeHtml(item.id)}">Remove</button></td>`
              : ""
          }
        </tr>`;
    })
    .join("");

  const itemsTable = items.length
    ? `<div class="table-wrap">
         <table class="table">
           <thead>
             <tr><th>Product</th><th class="num">Unit price</th><th class="num">Qty</th><th class="num">Subtotal</th>${editable ? "<th></th>" : ""}</tr>
           </thead>
           <tbody>${itemRows}</tbody>
           <tfoot>
             <tr><td colspan="3" class="num">Total</td><td class="num total">${formatMoney(order.totalAmount)}</td>${editable ? "<td></td>" : ""}</tr>
           </tfoot>
         </table>
       </div>`
    : `<div class="placeholder placeholder-empty">This order has no items yet.</div>`;

  const addItemCard = editable
    ? `<div class="card">
         <div class="card-header"><h2>Add item</h2></div>
         <form class="line-builder" data-form="add-item" novalidate>
           <label class="field grow">
             <span class="field-label">Product</span>
             <select name="productId">${productOptions(pickerProducts)}</select>
             <span class="field-error" data-error-for="productId"></span>
           </label>
           <label class="field qty">
             <span class="field-label">Quantity</span>
             <input name="quantity" type="number" min="1" step="1" value="1">
             <span class="field-error" data-error-for="quantity"></span>
           </label>
           <button type="submit" class="button button-primary">Add item</button>
         </form>
         <p class="hint">Adding an item reserves its stock right away. Removing it gives the stock back.</p>
       </div>`
    : `<div class="card"><p class="muted">This order is ${escapeHtml(order.status.toLowerCase())}, so its items can no longer be changed.</p></div>`;

  detailPanel().innerHTML = `
    <a href="#/orders" class="back-link">← All orders</a>
    <div class="view-header">
      <div>
        <h1>Order #${escapeHtml(order.id)} ${statusBadge(order.status)}</h1>
        <p class="muted">Created ${escapeHtml(formatDateTime(order.createdAt))}${
          order.updatedAt ? ` · Updated ${escapeHtml(formatDateTime(order.updatedAt))}` : ""
        }</p>
      </div>
      <div class="actions">
        ${statusButtons}
        <button type="button" class="button" data-action="refresh">Refresh</button>
        <button type="button" class="button button-danger" data-action="delete-order">Delete</button>
      </div>
    </div>
    <div class="stats stats-compact">
      <div class="stat"><span class="stat-label">Total</span><span class="stat-value">${formatMoney(order.totalAmount)}</span></div>
      <div class="stat"><span class="stat-label">Lines</span><span class="stat-value">${formatNumber(items.length)}</span></div>
      <div class="stat"><span class="stat-label">Units</span><span class="stat-value">${formatNumber(
        items.reduce((sum, item) => sum + Number(item.quantity || 0), 0)
      )}</span></div>
    </div>
    <div class="card">
      <div class="card-header"><h2>Items</h2></div>
      ${itemsTable}
    </div>
    ${addItemCard}`;
}

function clearInlineErrors(form) {
  form.querySelectorAll(".field-error").forEach((el) => (el.textContent = ""));
  form.querySelectorAll(".invalid").forEach((el) => el.classList.remove("invalid"));
}

async function onDetailClick(event) {
  const button = event.target.closest("button[data-action]");
  if (!button || !currentOrder) return;
  const order = currentOrder;

  switch (button.dataset.action) {
    case "refresh":
      loadOrderDetail(order.id, { quiet: true });
      break;
    case "delete-order":
      if (await deleteOrder(order)) window.location.hash = "#/orders";
      break;
    case "status":
      changeStatus(order, button.dataset.status);
      break;
    case "remove-item":
      removeItem(order, button.dataset.itemId);
      break;
    default:
      break;
  }
}

async function onDetailSubmit(event) {
  const form = event.target.closest("form[data-form]");
  if (!form || !currentOrder) return;
  event.preventDefault();
  if (form.dataset.form === "add-item") addItem(form);
  else if (form.dataset.form === "item-qty") updateItemQuantity(form);
}

async function addItem(form) {
  clearInlineErrors(form);
  const order = currentOrder;
  const productId = form.elements.productId.value;
  const quantity = parseWholeNumber(form.elements.quantity.value);
  const product = pickerProducts.find((p) => String(p.id) === productId);
  if (!product) {
    setFieldError(form, "productId", "Choose a product");
    return;
  }
  if (Number.isNaN(quantity) || quantity < 1) {
    setFieldError(form, "quantity", "Enter a whole number of 1 or more");
    return;
  }
  if (quantity > Number(product.quantity)) {
    setFieldError(form, "quantity", `Only ${product.quantity} in stock`);
    return;
  }
  const submit = form.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    await ordersApi.addItem(order.id, Number(productId), quantity);
    toast(`Added ${quantity} × ${product.name}`, { type: "success" });
    await loadOrderDetail(order.id, { quiet: true });
  } catch (error) {
    submit.disabled = false;
    showError(error, "Could not add the item");
    if (error instanceof ApiError && error.status === 409) loadOrderDetail(order.id, { quiet: true });
  }
}

async function updateItemQuantity(form) {
  const order = currentOrder;
  const itemId = form.dataset.itemId;
  const item = (order.orderItems || []).find((i) => String(i.id) === itemId);
  if (!item) return;
  const input = form.elements.quantity;
  const quantity = parseWholeNumber(input.value);
  input.classList.remove("invalid");
  if (Number.isNaN(quantity) || quantity < 1) {
    input.classList.add("invalid");
    toast("Quantity must be a whole number of 1 or more. To drop the item, use Remove.", { type: "warning" });
    return;
  }
  if (quantity === Number(item.quantity)) return;
  const submit = form.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    await ordersApi.updateItem(order.id, item.id, quantity);
    toast(`Updated ${item.product ? item.product.name : "item"} to ${quantity}`, { type: "success" });
    await loadOrderDetail(order.id, { quiet: true });
  } catch (error) {
    submit.disabled = false;
    input.classList.add("invalid");
    showError(error, "Could not change the quantity");
  }
}

async function removeItem(order, itemId) {
  const item = (order.orderItems || []).find((i) => String(i.id) === itemId);
  if (!item) return;
  const name = item.product ? item.product.name : `item ${item.id}`;
  const confirmed = await confirmDialog({
    title: "Remove item",
    message: `Remove ${item.quantity} × ${name} from order #${order.id}? The stock goes back into inventory.`,
    confirmLabel: "Remove",
    danger: true,
  });
  if (!confirmed) return;
  try {
    await ordersApi.removeItem(order.id, item.id);
    toast(`Removed ${name}`, { type: "success" });
  } catch (error) {
    showError(error, "Could not remove the item");
  }
  await loadOrderDetail(order.id, { quiet: true });
}

async function changeStatus(order, status) {
  const action = STATUS_ACTIONS[status];
  const confirmed = await confirmDialog({
    title: action.label,
    message: action.confirm,
    confirmLabel: action.label,
    danger: status === "CANCELLED",
  });
  if (!confirmed) return;
  try {
    currentOrder = await ordersApi.setStatus(order.id, status);
    toast(`Order #${order.id} is now ${status.toLowerCase()}`, { type: "success" });
    await loadOrderDetail(order.id, { quiet: true });
  } catch (error) {
    showError(error, "Could not change the status");
    loadOrderDetail(order.id, { quiet: true });
  }
}

/** Deletes an order after confirmation. Resolves true if it was deleted. */
async function deleteOrder(order) {
  const stockNote =
    order.status === "PENDING"
      ? "Its reserved stock goes back into inventory."
      : "Stock levels will not change.";
  const confirmed = await confirmDialog({
    title: "Delete order",
    message: `Delete order #${order.id}? ${stockNote} This cannot be undone.`,
    confirmLabel: "Delete",
    danger: true,
  });
  if (!confirmed) return false;
  try {
    await ordersApi.remove(order.id);
    toast(`Deleted order #${order.id}`, { type: "success" });
    orders = orders.filter((o) => o.id !== order.id);
    if (!listPanel().hidden) renderList();
    return true;
  } catch (error) {
    showError(error, `Could not delete order #${order.id}`);
    return false;
  }
}
