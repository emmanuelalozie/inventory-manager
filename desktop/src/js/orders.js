// Orders view: list, create, details with items, add/change/remove items, customer, status changes, printing,
// customer returns, delete.

import {
  ApiError,
  ORDER_STATUSES,
  STATUS_TRANSITIONS,
  customersApi,
  newIdempotencyKey,
  ordersApi,
  productsApi,
} from "./api.js";
import { countLabel } from "./helpers.js";
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

// The orders loaded so far (newest first, one or more pages) and the number of matches on the backend.
let orders = [];
let total = 0;
let nextPage = 0;
let loadRequest = 0; // ignores responses to a filter that was replaced by a newer one
let statusFilter = "";
let customerFilter = ""; // a customer id, or "" for all
let customerList = []; // for the filter and the pickers, by name
let currentOrder = null;
let pickerProducts = [];
let currentReturns = null; // what was sold and returned per product, for shipped and delivered orders
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

  const customerSelect = document.getElementById("orders-customer-filter");
  customerSelect.addEventListener("change", () => {
    customerFilter = customerSelect.value;
    loadOrders();
  });

  document.getElementById("orders-new").addEventListener("click", () => openNewOrderDialog());
  document.getElementById("orders-refresh").addEventListener("click", () => loadOrders());

  tableEl().addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action]");
    if (button) {
      event.stopPropagation();
      if (button.dataset.action === "more") {
        loadOrders({ more: true });
        return;
      }
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

/** Makes the order list show only this customer's orders the next time it is shown. */
export function filterOrdersByCustomer(customerId) {
  customerFilter = String(customerId);
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
  loadCustomerFilter();
  return loadOrders();
}

function customerOptions(selectedId, emptyLabel) {
  const selected = selectedId == null ? "" : String(selectedId);
  return (
    `<option value="">${escapeHtml(emptyLabel)}</option>` +
    customerList
      .map((c) => {
        const detail = c.email || c.phone;
        const label = detail ? `${c.name} (${detail})` : c.name;
        return `<option value="${escapeHtml(c.id)}"${String(c.id) === selected ? " selected" : ""}>${escapeHtml(label)}</option>`;
      })
      .join("")
  );
}

async function loadCustomers() {
  customerList = await customersApi.list();
  return customerList;
}

// Fills the customer filter. A failure leaves the filter as it was; the list itself still loads.
async function loadCustomerFilter() {
  const select = document.getElementById("orders-customer-filter");
  try {
    await loadCustomers();
  } catch {
    return;
  }
  if (customerFilter && !customerList.some((c) => String(c.id) === customerFilter)) customerFilter = "";
  select.innerHTML = customerOptions(customerFilter, "All customers");
}

function goToOrder(id) {
  window.location.hash = `#/orders/${encodeURIComponent(id)}`;
}

// ---------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------

/** Loads the first page for the current filter, or with more, the next page after the orders already shown. */
async function loadOrders({ more = false } = {}) {
  const requestId = ++loadRequest;
  const page = more ? nextPage : 0;
  const moreButton = tableEl().querySelector('[data-action="more"]');
  if (more && moreButton) moreButton.disabled = true;
  if (!more && !orders.length) renderPlaceholder(tableEl(), "loading", "Loading orders…");
  try {
    const result = await ordersApi.search({
      status: statusFilter || undefined,
      customerId: customerFilter || undefined,
      page,
    });
    if (requestId !== loadRequest) return;
    orders = more ? [...orders, ...result.items] : result.items;
    total = result.total;
    nextPage = page + 1;
    renderList();
  } catch (error) {
    if (requestId !== loadRequest) return;
    if (more) {
      if (moreButton) moreButton.disabled = false;
      showError(error, "Could not load more orders");
      return;
    }
    orders = [];
    total = 0;
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
  countEl().textContent = countLabel(orders.length, total, "order");

  if (!sorted.length) {
    renderPlaceholder(
      tableEl(),
      "empty",
      statusFilter || customerFilter
        ? "No orders match the filters."
        : "No orders yet. Click “New order” to create one."
    );
    return;
  }

  const rows = sorted
    .map(
      (o) => `
      <tr class="clickable" data-id="${escapeHtml(o.id)}">
        <td><strong>#${escapeHtml(o.id)}</strong></td>
        <td>${o.customerName ? escapeHtml(o.customerName) : `<span class="muted">—</span>`}</td>
        <td class="hide-narrow">${escapeHtml(formatDateTime(o.createdAt))}</td>
        <td>${itemSummary(o)}</td>
        <td class="num">${formatMoney(o.totalAmount)}</td>
        <td>${statusBadge(o.status)}</td>
        <td class="row-actions">
          <button type="button" class="button button-small" data-action="view" data-id="${escapeHtml(o.id)}">View</button>
          ${
            canDelete(o)
              ? `<button type="button" class="button button-small button-danger-ghost" data-action="delete" data-id="${escapeHtml(o.id)}">Delete</button>`
              : ""
          }
        </td>
      </tr>`
    )
    .join("");

  tableEl().innerHTML = `
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>Order</th><th>Customer</th><th class="hide-narrow">Created</th><th>Items</th><th class="num">Total</th><th>Status</th>
            <th class="row-actions"><span class="sr-only">Actions</span></th>
          </tr>
        </thead>
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
// New order
// ---------------------------------------------------------------------------

async function openNewOrderDialog() {
  let available;
  try {
    [available] = await Promise.all([productsApi.list(), loadCustomers()]);
  } catch (error) {
    showError(error, "Could not load products for the new order");
    return;
  }

  const byId = new Map(available.map((p) => [String(p.id), p]));
  const lines = new Map(); // productId -> quantity
  // Resubmitting the same order (e.g. after a timeout) reuses the key, so the backend can't create it twice.
  let submitted = { order: null, key: null };

  const created = await openDialog({
    title: "New order",
    submitLabel: "Create order",
    wide: true,
    body: `
      <label class="field field-spaced">
        <span class="field-label">Customer</span>
        <select name="customerId">${customerOptions(customerFilter, "No customer")}</select>
      </label>
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
      const customerId = form.elements.customerId.value ? Number(form.elements.customerId.value) : undefined;
      const orderJson = JSON.stringify({ customerId, items });
      if (submitted.order !== orderJson) {
        submitted = { order: orderJson, key: newIdempotencyKey() };
      }
      return ordersApi.create(items, submitted.key, customerId);
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
    const returns = takesReturns(order) ? await ordersApi.getReturns(order.id) : null;
    if (requestId !== detailRequest) return; // the user navigated elsewhere meanwhile
    currentOrder = order;
    currentReturns = returns;
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

  const returnedByProduct = new Map((currentReturns?.lines || []).map((l) => [String(l.productId), l.quantityReturned]));
  const anyReturned = [...returnedByProduct.values()].some((n) => n > 0);
  const canReturn = (currentReturns?.lines || []).some((l) => l.quantityReturnable > 0);

  // Items only carry the product's id, name and SKU; the current stock comes from the product list.
  const stockById = new Map(pickerProducts.map((p) => [String(p.id), Number(p.quantity)]));
  const itemRows = items
    .map((item) => {
      const stock = stockById.get(String(item.productId));
      const maxQuantity = Number(item.quantity) + Math.max(0, stock || 0);
      const quantityCell = editable
        ? `<form class="inline-qty" data-form="item-qty" data-item-id="${escapeHtml(item.id)}" novalidate>
             <input name="quantity" type="number" min="1" max="${escapeHtml(maxQuantity)}" step="1" value="${escapeHtml(item.quantity)}" aria-label="Quantity">
             <button type="submit" class="button button-small">Save</button>
           </form>
           <div class="cell-sub">max ${escapeHtml(maxQuantity)}</div>`
        : escapeHtml(item.quantity);
      return `
        <tr>
          <td><div class="cell-title">${escapeHtml(item.productName)}</div><div class="cell-sub">${escapeHtml(item.productSku)}</div></td>
          <td class="num">${formatMoney(item.pricePerUnit)}</td>
          <td class="num">${quantityCell}</td>
          <td class="num">${formatMoney(item.subtotal)}</td>
          ${anyReturned ? `<td class="num">${returnedCell(item, returnedByProduct)}</td>` : ""}
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
             <tr><th>Product</th><th class="num">Unit price</th><th class="num">Qty</th><th class="num">Subtotal</th>${anyReturned ? `<th class="num">Returned</th>` : ""}${editable ? "<th></th>" : ""}</tr>
           </thead>
           <tbody>${itemRows}</tbody>
           <tfoot>
             <tr><td colspan="3" class="num">Total</td><td class="num total">${formatMoney(order.totalAmount)}</td>${anyReturned ? "<td></td>" : ""}${editable ? "<td></td>" : ""}</tr>
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
    : `<div class="card"><p class="muted">This order is ${escapeHtml(order.status.toLowerCase())}, so its items can no longer be changed.${
        takesReturns(order) ? " If the customer sends something back, use “Return items” to put it back in stock." : ""
      }</p></div>`;

  detailPanel().innerHTML = `
    <a href="#/orders" class="back-link">← All orders</a>
    <div class="view-header">
      <div>
        <h1>Order #${escapeHtml(order.id)} ${statusBadge(order.status)}</h1>
        <p class="order-customer">
          ${order.customerName ? `For <strong>${escapeHtml(order.customerName)}</strong>` : `<span class="muted">No customer</span>`}
          <button type="button" class="button button-small" data-action="change-customer">${order.customerId ? "Change" : "Set customer"}</button>
        </p>
        <p class="muted">Created ${escapeHtml(formatDateTime(order.createdAt))}${
          order.updatedAt ? ` · Updated ${escapeHtml(formatDateTime(order.updatedAt))}` : ""
        }</p>
      </div>
      <div class="actions">
        ${statusButtons}
        ${
          items.length
            ? `<button type="button" class="button" data-action="print-slip">Packing slip</button>
               <button type="button" class="button" data-action="print-order">Print order</button>`
            : ""
        }
        ${canReturn ? `<button type="button" class="button" data-action="return-items">Return items</button>` : ""}
        <button type="button" class="button" data-action="refresh">Refresh</button>
        ${canDelete(order) ? `<button type="button" class="button button-danger" data-action="delete-order">Delete</button>` : ""}
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
    case "change-customer":
      changeCustomer(order);
      break;
    case "print-slip":
      printOrder(order, { prices: false });
      break;
    case "print-order":
      printOrder(order, { prices: true });
      break;
    case "return-items":
      returnItems(order);
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
    toast(`Updated ${item.productName || "item"} to ${quantity}`, { type: "success" });
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
  const name = item.productName || `item ${item.id}`;
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

async function changeCustomer(order) {
  try {
    await loadCustomers();
  } catch (error) {
    showError(error, "Could not load customers");
    return;
  }
  if (!customerList.length) {
    toast("Add a customer in Customers first.");
    return;
  }
  const updated = await openDialog({
    title: `Customer for order #${order.id}`,
    submitLabel: "Save",
    body: `
      <label class="field">
        <span class="field-label">Customer</span>
        <select name="customerId">${customerOptions(order.customerId, "No customer")}</select>
      </label>
      <p class="hint">Changing the customer doesn't change the items, stock or status.</p>`,
    onSubmit(form) {
      const value = form.elements.customerId.value;
      return ordersApi.setCustomer(order.id, value ? Number(value) : null);
    },
  });
  if (updated) {
    toast(updated.customerName ? `Order #${order.id} is now for ${updated.customerName}` : `Removed the customer from order #${order.id}`, {
      type: "success",
    });
    loadOrderDetail(order.id, { quiet: true });
  }
}

/**
 * Prints the order: a packing slip (products and quantities, to go in the box) or, with prices, a copy of the
 * order with unit prices and the total, for the customer.
 */
async function printOrder(order, { prices }) {
  let customer = null;
  if (order.customerId) {
    try {
      customer = await customersApi.get(order.customerId);
    } catch {
      // Print with the name only.
    }
  }
  const items = order.orderItems || [];
  const units = items.reduce((sum, item) => sum + Number(item.quantity || 0), 0);
  const rows = items
    .map(
      (item) => `
      <tr>
        <td>${escapeHtml(item.productSku)}</td>
        <td>${escapeHtml(item.productName)}</td>
        <td class="num">${escapeHtml(formatNumber(item.quantity))}</td>
        ${prices ? `<td class="num">${formatMoney(item.pricePerUnit)}</td><td class="num">${formatMoney(item.subtotal)}</td>` : `<td class="check"></td>`}
      </tr>`
    )
    .join("");
  const contact = customer
    ? [customer.address, customer.email, customer.phone].filter(Boolean).map((line) => escapeHtml(line))
    : [];
  const customerBlock = order.customerName
    ? `<h2>${prices ? "Customer" : "Ship to"}</h2>
       <p class="print-address"><strong>${escapeHtml(order.customerName)}</strong>${contact.map((line) => `<br>${line}`).join("")}</p>`
    : "";
  const area = document.getElementById("print-area");
  area.innerHTML = `
    <h1>${prices ? "Order" : "Packing slip"} #${escapeHtml(order.id)}</h1>
    <p>Date: ${escapeHtml(formatDateTime(order.createdAt))} · Status: ${escapeHtml(order.status.charAt(0) + order.status.slice(1).toLowerCase())}</p>
    ${customerBlock}
    <table>
      <thead>
        <tr><th>SKU</th><th>Product</th><th class="num">Quantity</th>${
          prices ? `<th class="num">Unit price</th><th class="num">Subtotal</th>` : `<th class="check">Packed</th>`
        }</tr>
      </thead>
      <tbody>${rows}</tbody>
      <tfoot>
        ${
          prices
            ? `<tr><td colspan="4" class="num">Total</td><td class="num">${formatMoney(order.totalAmount)}</td></tr>`
            : `<tr><td colspan="2" class="num">Total units</td><td class="num">${escapeHtml(formatNumber(units))}</td><td></td></tr>`
        }
      </tfoot>
    </table>`;
  window.print();
}

function takesReturns(order) {
  return order.status === "SHIPPED" || order.status === "DELIVERED";
}

// Returns are kept per product, so a product listed on two lines shows its returns on the first one.
function returnedCell(item, returnedByProduct) {
  const key = String(item.productId);
  const first = (currentOrder.orderItems || []).find((i) => String(i.productId) === key);
  const returned = first === item ? returnedByProduct.get(key) || 0 : 0;
  return returned ? escapeHtml(formatNumber(returned)) : `<span class="muted">—</span>`;
}

async function returnItems(order) {
  const lines = (currentReturns?.lines || []).filter((l) => l.quantityReturnable > 0);
  const rows = lines
    .map(
      (l) => `
      <tr>
        <td><div class="cell-title">${escapeHtml(l.productName)}</div><div class="cell-sub">${escapeHtml(l.productSku)}</div></td>
        <td class="num">${escapeHtml(formatNumber(l.quantitySold))}</td>
        <td class="num">${escapeHtml(formatNumber(l.quantityReturned))}</td>
        <td class="num">
          <input class="return-input" name="return-${escapeHtml(l.productId)}" type="number" min="0" max="${escapeHtml(l.quantityReturnable)}"
                 step="1" placeholder="0" aria-label="Return ${escapeHtml(l.productName)}">
          <span class="field-error" data-error-for="return-${escapeHtml(l.productId)}"></span>
        </td>
      </tr>`
    )
    .join("");
  const result = await openDialog({
    title: `Return items from order #${order.id}`,
    submitLabel: "Put back in stock",
    wide: true,
    body: `
      <div class="table-wrap">
        <table class="table table-compact">
          <thead><tr><th>Product</th><th class="num">Sold</th><th class="num">Returned</th><th class="num">Returning now</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
      <label class="field">
        <span class="field-label">Note</span>
        <input name="note" maxlength="400" placeholder="e.g. Wrong size" autocomplete="off">
      </label>
      <p class="hint">Returned units go back into stock as a customer return. The order's total and status don't change. If something came back damaged, take it out again with Stock on the product.</p>`,
    onSubmit(form) {
      const request = [];
      for (const l of lines) {
        const name = `return-${l.productId}`;
        const text = form.elements[name].value.trim();
        if (!text || text === "0") continue;
        const quantity = parseWholeNumber(text);
        if (Number.isNaN(quantity) || quantity > l.quantityReturnable) {
          setFieldError(form, name, `0 to ${l.quantityReturnable}`);
          return false;
        }
        request.push({ productId: l.productId, quantity });
      }
      if (!request.length) {
        setFieldError(form, `return-${lines[0].productId}`, "Enter how many came back");
        return false;
      }
      return ordersApi.returnItems(order.id, request, form.elements.note.value.trim());
    },
  });
  if (result) {
    toast(`Returned items from order #${order.id} to stock`, { type: "success" });
    loadOrderDetail(order.id, { quiet: true });
  }
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

/** Shipped and delivered orders are kept as a record; the backend refuses to delete them. */
function canDelete(order) {
  return order.status !== "SHIPPED" && order.status !== "DELIVERED";
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
    total = Math.max(0, total - 1);
    if (!listPanel().hidden) renderList();
    return true;
  } catch (error) {
    showError(error, `Could not delete order #${order.id}`);
    return false;
  }
}
