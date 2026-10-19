// Customers view: list and search, create, edit, delete, and a shortcut to a customer's orders.

import { customersApi } from "./api.js";
import { filterOrdersByCustomer } from "./orders.js";
import {
  confirmDialog,
  escapeHtml,
  openDialog,
  renderPlaceholder,
  setFieldError,
  showError,
  showLoadError,
  toast,
} from "./ui.js";

let customers = [];
let searchText = "";
let loadRequest = 0; // ignores responses to a search that was replaced by a newer one
let searchTimer = null;

const tableEl = () => document.getElementById("customers-table");
const countEl = () => document.getElementById("customers-count");

export function initCustomers() {
  document.getElementById("customers-new").addEventListener("click", () => openCustomerDialog(null));
  const search = document.getElementById("customers-search");
  search.addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      searchText = search.value.trim();
      loadCustomers();
    }, 250);
  });
  tableEl().addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const customer = customers.find((c) => String(c.id) === button.dataset.id);
    if (!customer) return;
    if (button.dataset.action === "orders") {
      filterOrdersByCustomer(customer.id);
      window.location.hash = "#/orders";
    } else if (button.dataset.action === "edit") openCustomerDialog(customer);
    else if (button.dataset.action === "delete") deleteCustomer(customer);
  });
}

export function showCustomers() {
  return loadCustomers();
}

async function loadCustomers() {
  const requestId = ++loadRequest;
  if (!customers.length) renderPlaceholder(tableEl(), "loading", "Loading customers…");
  try {
    const result = await customersApi.list(searchText || undefined);
    if (requestId !== loadRequest) return;
    customers = result;
    renderTable();
  } catch (error) {
    if (requestId !== loadRequest) return;
    customers = [];
    countEl().textContent = "";
    showLoadError(tableEl(), error, "Could not load customers");
  }
}

function renderTable() {
  countEl().textContent = `${customers.length} ${customers.length === 1 ? "customer" : "customers"}`;
  if (!customers.length) {
    renderPlaceholder(
      tableEl(),
      "empty",
      searchText ? `No customers match “${searchText}”.` : "No customers yet. Click “New customer” to add one."
    );
    return;
  }
  const dash = `<span class="muted">—</span>`;
  const rows = customers
    .map(
      (c) => `
      <tr>
        <td>
          <div class="cell-title">${escapeHtml(c.name)}</div>
          ${c.address ? `<div class="cell-sub">${escapeHtml(c.address)}</div>` : ""}
        </td>
        <td>${c.email ? `<a href="mailto:${escapeHtml(c.email)}">${escapeHtml(c.email)}</a>` : dash}</td>
        <td>${c.phone ? escapeHtml(c.phone) : dash}</td>
        <td class="row-actions">
          <button type="button" class="button button-small" data-action="orders" data-id="${escapeHtml(c.id)}">Orders</button>
          <button type="button" class="button button-small" data-action="edit" data-id="${escapeHtml(c.id)}">Edit</button>
          <button type="button" class="button button-small button-danger-ghost" data-action="delete" data-id="${escapeHtml(c.id)}">Delete</button>
        </td>
      </tr>`
    )
    .join("");
  tableEl().innerHTML = `
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>Name</th><th>Email</th><th>Phone</th><th class="row-actions"><span class="sr-only">Actions</span></th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>`;
}

function customerFormBody(customer) {
  const c = customer || {};
  const value = (v) => escapeHtml(v ?? "");
  return `
    <div class="form-grid">
      <label class="field span-2">
        <span class="field-label">Name <span class="req">*</span></span>
        <input name="name" maxlength="255" required value="${value(c.name)}" autocomplete="off">
        <span class="field-error" data-error-for="name"></span>
      </label>
      <label class="field">
        <span class="field-label">Email</span>
        <input name="email" type="email" maxlength="255" value="${value(c.email)}" autocomplete="off">
        <span class="field-error" data-error-for="email"></span>
      </label>
      <label class="field">
        <span class="field-label">Phone</span>
        <input name="phone" maxlength="50" value="${value(c.phone)}" autocomplete="off">
        <span class="field-error" data-error-for="phone"></span>
      </label>
      <label class="field span-2">
        <span class="field-label">Address</span>
        <textarea name="address" maxlength="1000" rows="3">${value(c.address)}</textarea>
        <span class="field-error" data-error-for="address"></span>
      </label>
      <label class="field span-2">
        <span class="field-label">Notes</span>
        <textarea name="notes" maxlength="1000" rows="2">${value(c.notes)}</textarea>
        <span class="field-error" data-error-for="notes"></span>
      </label>
    </div>`;
}

/** Opens the new or edit customer dialog and resolves with the saved customer, or null when cancelled. */
export async function openCustomerDialog(customer) {
  const editing = Boolean(customer);
  const saved = await openDialog({
    title: editing ? `Edit ${customer.name}` : "New customer",
    submitLabel: editing ? "Save changes" : "Create customer",
    body: customerFormBody(customer),
    async onSubmit(form) {
      const f = form.elements;
      const body = {
        name: f.name.value.trim(),
        email: f.email.value.trim() || null,
        phone: f.phone.value.trim() || null,
        address: f.address.value.trim() || null,
        notes: f.notes.value.trim() || null,
      };
      if (!body.name) {
        setFieldError(form, "name", "Name is required");
        return false;
      }
      if (f.email.value && !f.email.validity.valid) {
        setFieldError(form, "email", "Enter a valid email address");
        return false;
      }
      if (editing) body.version = customer.version;
      return editing ? customersApi.update(customer.id, body) : customersApi.create(body);
    },
  });
  if (saved) {
    toast(editing ? `Saved ${saved.name}` : `Created ${saved.name}`, { type: "success" });
    if (document.querySelector('.view[data-view="customers"]:not([hidden])')) loadCustomers();
  }
  return saved;
}

async function deleteCustomer(customer) {
  const confirmed = await confirmDialog({
    title: "Delete customer",
    message: `Delete “${customer.name}”? This cannot be undone.`,
    confirmLabel: "Delete",
    danger: true,
  });
  if (!confirmed) return;
  try {
    await customersApi.remove(customer.id);
    toast(`Deleted ${customer.name}`, { type: "success" });
    loadCustomers();
  } catch (error) {
    showError(error, `Could not delete ${customer.name}`);
  }
}
