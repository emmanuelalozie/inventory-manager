// Suppliers view: list, create, edit, delete.

import { ApiError, suppliersApi } from "./api.js";
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

let suppliers = [];

const tableEl = () => document.getElementById("suppliers-table");
const countEl = () => document.getElementById("suppliers-count");

export function initSuppliers() {
  document.getElementById("suppliers-new").addEventListener("click", () => openSupplierDialog(null));
  tableEl().addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const supplier = suppliers.find((s) => String(s.id) === button.dataset.id);
    if (!supplier) return;
    if (button.dataset.action === "edit") openSupplierDialog(supplier);
    else if (button.dataset.action === "delete") deleteSupplier(supplier);
  });
}

export function showSuppliers() {
  return loadSuppliers();
}

async function loadSuppliers() {
  if (!suppliers.length) renderPlaceholder(tableEl(), "loading", "Loading suppliers…");
  try {
    suppliers = await suppliersApi.list();
    renderTable();
  } catch (error) {
    suppliers = [];
    countEl().textContent = "";
    showLoadError(tableEl(), error, "Could not load suppliers");
  }
}

function renderTable() {
  countEl().textContent = `${suppliers.length} ${suppliers.length === 1 ? "supplier" : "suppliers"}`;
  if (!suppliers.length) {
    renderPlaceholder(tableEl(), "empty", "No suppliers yet. Click “New supplier” to add one.");
    return;
  }
  const rows = suppliers
    .map(
      (s) => `
      <tr>
        <td>
          <div class="cell-title">${escapeHtml(s.name)}</div>
          ${s.notes ? `<div class="cell-sub">${escapeHtml(s.notes)}</div>` : ""}
        </td>
        <td>${s.email ? `<a href="mailto:${escapeHtml(s.email)}">${escapeHtml(s.email)}</a>` : `<span class="muted">—</span>`}</td>
        <td>${s.phone ? escapeHtml(s.phone) : `<span class="muted">—</span>`}</td>
        <td class="row-actions">
          <button type="button" class="button button-small" data-action="edit" data-id="${escapeHtml(s.id)}">Edit</button>
          <button type="button" class="button button-small button-danger-ghost" data-action="delete" data-id="${escapeHtml(s.id)}">Delete</button>
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

function supplierFormBody(supplier) {
  const s = supplier || {};
  const value = (v) => escapeHtml(v ?? "");
  return `
    <div class="form-grid">
      <label class="field span-2">
        <span class="field-label">Name <span class="req">*</span></span>
        <input name="name" maxlength="255" required value="${value(s.name)}" autocomplete="off">
        <span class="field-error" data-error-for="name"></span>
      </label>
      <label class="field">
        <span class="field-label">Email</span>
        <input name="email" type="email" maxlength="255" value="${value(s.email)}" autocomplete="off">
        <span class="field-error" data-error-for="email"></span>
      </label>
      <label class="field">
        <span class="field-label">Phone</span>
        <input name="phone" maxlength="50" value="${value(s.phone)}" autocomplete="off">
        <span class="field-error" data-error-for="phone"></span>
      </label>
      <label class="field span-2">
        <span class="field-label">Notes</span>
        <textarea name="notes" maxlength="1000" rows="3">${value(s.notes)}</textarea>
        <span class="field-error" data-error-for="notes"></span>
      </label>
    </div>`;
}

async function openSupplierDialog(supplier) {
  const editing = Boolean(supplier);
  const saved = await openDialog({
    title: editing ? `Edit ${supplier.name}` : "New supplier",
    submitLabel: editing ? "Save changes" : "Create supplier",
    body: supplierFormBody(supplier),
    async onSubmit(form) {
      const f = form.elements;
      const body = {
        name: f.name.value.trim(),
        email: f.email.value.trim() || null,
        phone: f.phone.value.trim() || null,
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
      if (editing) body.version = supplier.version;
      try {
        return editing ? await suppliersApi.update(supplier.id, body) : await suppliersApi.create(body);
      } catch (error) {
        if (error instanceof ApiError && error.status === 409 && /named/.test(error.message)) {
          setFieldError(form, "name", error.message);
          return false;
        }
        throw error;
      }
    },
  });
  if (saved) {
    toast(editing ? `Saved ${saved.name}` : `Created ${saved.name}`, { type: "success" });
    loadSuppliers();
  }
}

async function deleteSupplier(supplier) {
  const confirmed = await confirmDialog({
    title: "Delete supplier",
    message: `Delete “${supplier.name}”? This cannot be undone.`,
    confirmLabel: "Delete",
    danger: true,
  });
  if (!confirmed) return;
  try {
    await suppliersApi.remove(supplier.id);
    toast(`Deleted ${supplier.name}`, { type: "success" });
    loadSuppliers();
  } catch (error) {
    showError(error, `Could not delete ${supplier.name}`);
  }
}
