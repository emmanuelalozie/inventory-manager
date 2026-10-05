// Products view: list/search, create, edit, delete and stock adjustments.

import { ApiError, productsApi } from "./api.js";
import { getLowStockThreshold } from "./settings.js";
import {
  confirmDialog,
  debounce,
  escapeHtml,
  formatDateTime,
  formatMoney,
  openDialog,
  renderPlaceholder,
  setFieldError,
  showError,
  showLoadError,
  stockBadge,
  toast,
} from "./ui.js";

const MAX_INT = 2147483647; // Java Integer.MAX_VALUE
const MAX_PRICE = 9999999999.99; // DECIMAL(12, 2)

let products = [];
let searchTerm = "";

const tableEl = () => document.getElementById("products-table");
const countEl = () => document.getElementById("products-count");

// ---------------------------------------------------------------------------
// Shared helpers (also used by the orders and dashboard views)
// ---------------------------------------------------------------------------

/** Parses a whole number from a form input. Returns NaN unless the text is digits only. */
export function parseWholeNumber(value) {
  const text = String(value ?? "").trim();
  return /^\d+$/.test(text) ? Number(text) : NaN;
}

/** <option> list for picking a product. Out-of-stock products are disabled. */
export function productOptions(list, selectedId) {
  const sorted = [...list].sort((a, b) => String(a.name).localeCompare(String(b.name)));
  const options = sorted.map((p) => {
    const out = Number(p.quantity) <= 0;
    const label = `${p.name} (${p.sku}) · ${formatMoney(p.price)} · ${out ? "out of stock" : `${p.quantity} in stock`}`;
    const selected = String(p.id) === String(selectedId) ? " selected" : "";
    return `<option value="${escapeHtml(p.id)}"${out ? " disabled" : ""}${selected}>${escapeHtml(label)}</option>`;
  });
  return `<option value="">Select a product…</option>${options.join("")}`;
}

/** Opens the stock adjustment dialog. Resolves with the updated product, or null if cancelled. */
export function openStockDialog(product) {
  const current = Number(product.quantity);
  return openDialog({
    title: `Adjust stock: ${product.name}`,
    submitLabel: "Apply",
    body: `
      <p class="muted">SKU ${escapeHtml(product.sku)} · currently <strong>${escapeHtml(current)}</strong> in stock</p>
      <fieldset class="segmented">
        <legend class="field-label">Action</legend>
        <label><input type="radio" name="direction" value="add" checked> Add stock</label>
        <label><input type="radio" name="direction" value="remove"> Remove stock</label>
      </fieldset>
      <label class="field">
        <span class="field-label">Units</span>
        <input name="amount" type="number" min="1" step="1" value="1" required>
        <span class="field-error" data-error-for="amount"></span>
      </label>
      <p class="hint" data-preview></p>`,
    onOpen(form) {
      const preview = form.querySelector("[data-preview]");
      const update = () => {
        const amount = parseWholeNumber(form.elements.amount.value);
        if (Number.isNaN(amount)) {
          preview.textContent = "";
          return;
        }
        const sign = form.elements.direction.value === "remove" ? -1 : 1;
        preview.textContent = `New stock level: ${current + sign * amount}`;
      };
      form.addEventListener("input", update);
      form.addEventListener("change", update);
      update();
    },
    async onSubmit(form) {
      const amount = parseWholeNumber(form.elements.amount.value);
      const remove = form.elements.direction.value === "remove";
      if (Number.isNaN(amount) || amount < 1) {
        setFieldError(form, "amount", "Enter a whole number of 1 or more");
        return false;
      }
      if (amount > MAX_INT) {
        setFieldError(form, "amount", "That number is too large");
        return false;
      }
      if (remove && amount > current) {
        setFieldError(form, "amount", `Only ${current} in stock`);
        return false;
      }
      const updated = await productsApi.adjustStock(product.id, remove ? -amount : amount);
      toast(`Stock for ${updated.name} is now ${updated.quantity}`, { type: "success" });
      return updated;
    },
  });
}

// ---------------------------------------------------------------------------
// View
// ---------------------------------------------------------------------------

export function initProducts() {
  const search = document.getElementById("products-search");
  search.addEventListener(
    "input",
    debounce(() => {
      searchTerm = search.value.trim().toLowerCase();
      renderTable();
    }, 150)
  );

  document.getElementById("products-new").addEventListener("click", () => openProductDialog(null));
  document.getElementById("products-refresh").addEventListener("click", () => loadProducts());

  tableEl().addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const product = products.find((p) => String(p.id) === button.dataset.id);
    if (!product) return;
    if (button.dataset.action === "edit") openProductDialog(product);
    else if (button.dataset.action === "stock") adjustStock(product);
    else if (button.dataset.action === "delete") deleteProduct(product);
  });
}

export function showProducts() {
  return loadProducts();
}

async function loadProducts() {
  if (!products.length) renderPlaceholder(tableEl(), "loading", "Loading products…");
  try {
    products = await productsApi.list();
    renderTable();
  } catch (error) {
    products = [];
    countEl().textContent = "";
    showLoadError(tableEl(), error, "Could not load products");
  }
}

function matches(product) {
  if (!searchTerm) return true;
  return [product.name, product.sku, product.description]
    .filter(Boolean)
    .some((value) => String(value).toLowerCase().includes(searchTerm));
}

function renderTable() {
  const threshold = getLowStockThreshold();
  const visible = products.filter(matches);
  countEl().textContent = searchTerm
    ? `${visible.length} of ${products.length} products`
    : `${products.length} products`;

  if (!products.length) {
    renderPlaceholder(tableEl(), "empty", "No products yet. Click “New product” to add one.");
    return;
  }
  if (!visible.length) {
    renderPlaceholder(tableEl(), "empty", "No products match your search.");
    return;
  }

  const rows = visible
    .map(
      (p) => `
      <tr>
        <td>
          <div class="cell-title">${escapeHtml(p.name)}</div>
          ${p.description ? `<div class="cell-sub">${escapeHtml(p.description)}</div>` : ""}
        </td>
        <td><code>${escapeHtml(p.sku)}</code></td>
        <td class="num">${formatMoney(p.price)}</td>
        <td class="num">${stockBadge(p.quantity, threshold)}</td>
        <td class="hide-narrow">${escapeHtml(formatDateTime(p.updatedAt || p.createdAt))}</td>
        <td class="row-actions">
          <button type="button" class="button button-small" data-action="stock" data-id="${escapeHtml(p.id)}">Stock</button>
          <button type="button" class="button button-small" data-action="edit" data-id="${escapeHtml(p.id)}">Edit</button>
          <button type="button" class="button button-small button-danger-ghost" data-action="delete" data-id="${escapeHtml(p.id)}">Delete</button>
        </td>
      </tr>`
    )
    .join("");

  tableEl().innerHTML = `
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>Name</th><th>SKU</th><th class="num">Price</th><th class="num">Stock</th>
            <th class="hide-narrow">Last changed</th><th class="row-actions"><span class="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    </div>`;
}

function replaceProduct(updated) {
  const index = products.findIndex((p) => p.id === updated.id);
  if (index >= 0) products[index] = updated;
  else products.push(updated);
  renderTable();
}

// ---------------------------------------------------------------------------
// Create / edit
// ---------------------------------------------------------------------------

function productFormBody(product) {
  const p = product || {};
  const value = (v) => escapeHtml(v ?? "");
  return `
    <div class="form-grid">
      <label class="field span-2">
        <span class="field-label">Name <span class="req">*</span></span>
        <input name="name" maxlength="255" required value="${value(p.name)}" autocomplete="off">
        <span class="field-error" data-error-for="name"></span>
      </label>
      <label class="field">
        <span class="field-label">SKU <span class="req">*</span></span>
        <input name="sku" maxlength="64" required value="${value(p.sku)}" autocomplete="off">
        <span class="field-error" data-error-for="sku"></span>
      </label>
      <label class="field">
        <span class="field-label">Price <span class="req">*</span></span>
        <input name="price" type="number" min="0" step="0.01" inputmode="decimal" required value="${value(p.price)}">
        <span class="field-error" data-error-for="price"></span>
      </label>
      <label class="field">
        <span class="field-label">Quantity in stock <span class="req">*</span></span>
        <input name="quantity" type="number" min="0" step="1" required value="${value(p.quantity ?? 0)}">
        <span class="field-error" data-error-for="quantity"></span>
        ${product ? `<span class="hint">To add or remove a few units, use the Stock button instead.</span>` : ""}
      </label>
      <label class="field span-2">
        <span class="field-label">Description</span>
        <textarea name="description" maxlength="1000" rows="3">${value(p.description)}</textarea>
        <span class="field-error" data-error-for="description"></span>
      </label>
    </div>`;
}

/** Validates the product form. Returns the request body, or null after marking the bad fields. */
function readProductForm(form) {
  const f = form.elements;
  const name = f.name.value.trim();
  const sku = f.sku.value.trim();
  const description = f.description.value.trim();
  const priceText = f.price.value.trim();
  const quantityText = f.quantity.value.trim();
  let ok = true;
  const fail = (field, message) => {
    setFieldError(form, field, message);
    ok = false;
  };

  if (!name) fail("name", "Name is required");
  else if (name.length > 255) fail("name", "Name must be at most 255 characters");

  if (!sku) fail("sku", "SKU is required");
  else if (sku.length > 64) fail("sku", "SKU must be at most 64 characters");

  if (description.length > 1000) fail("description", "Description must be at most 1000 characters");

  if (f.price.validity.badInput) fail("price", "Enter a number, e.g. 24.99");
  else if (!priceText) fail("price", "Price is required");
  else if (!/^\d+(\.\d{1,2})?$/.test(priceText)) fail("price", "Use a number of 0 or more with at most 2 decimals");
  else if (Number(priceText) > MAX_PRICE) fail("price", "Price is too large");

  if (f.quantity.validity.badInput) fail("quantity", "Enter a whole number");
  else if (!quantityText) fail("quantity", "Quantity is required");
  else if (Number.isNaN(parseWholeNumber(quantityText))) fail("quantity", "Use a whole number of 0 or more");
  else if (Number(quantityText) > MAX_INT) fail("quantity", "Quantity is too large");

  if (!ok) return null;
  return {
    name,
    sku,
    description: description || null,
    price: Number(priceText),
    quantity: Number(quantityText),
  };
}

async function openProductDialog(product) {
  const editing = Boolean(product);
  // Sent back on save so the backend rejects the edit if someone else changed the product meanwhile.
  let loadedVersion = editing ? product.version : null;
  const saved = await openDialog({
    title: editing ? `Edit ${product.name}` : "New product",
    submitLabel: editing ? "Save changes" : "Create product",
    body: productFormBody(product),
    async onSubmit(form) {
      const body = readProductForm(form);
      if (!body) return false;
      if (loadedVersion != null) body.version = loadedVersion;
      try {
        return editing
          ? await productsApi.update(product.id, body)
          : await productsApi.create(body);
      } catch (error) {
        // A 409 here means either the SKU is taken or the product changed since it was loaded.
        if (error instanceof ApiError && error.status === 409) {
          if (/sku/i.test(error.message)) {
            setFieldError(form, "sku", error.message);
          } else if (editing) {
            // Pick up the latest version so a second save is a deliberate overwrite.
            try {
              const latest = await productsApi.get(product.id);
              loadedVersion = latest.version;
              replaceProduct(latest);
            } catch {
              // Keep the error below; the user can close the dialog and refresh.
            }
            throw new Error(
              "Someone else changed this product while you were editing it. " +
                "Save again to overwrite their changes, or cancel to keep them."
            );
          }
        }
        throw error;
      }
    },
  });
  if (saved) {
    toast(editing ? `Saved ${saved.name}` : `Created ${saved.name}`, { type: "success" });
    replaceProduct(saved);
  }
}

async function adjustStock(product) {
  const updated = await openStockDialog(product);
  if (updated) replaceProduct(updated);
}

async function deleteProduct(product) {
  const confirmed = await confirmDialog({
    title: "Delete product",
    message: `Delete “${product.name}” (${product.sku})? This cannot be undone.`,
    confirmLabel: "Delete",
    danger: true,
  });
  if (!confirmed) return;
  try {
    await productsApi.remove(product.id);
    products = products.filter((p) => p.id !== product.id);
    renderTable();
    toast(`Deleted ${product.name}`, { type: "success" });
  } catch (error) {
    showError(error, `Could not delete ${product.name}`);
    if (error instanceof ApiError && error.status === 404) loadProducts();
  }
}
