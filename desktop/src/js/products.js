// Products view: list/search, create, edit, delete, stock adjustments and stock history.

import { ApiError, MOVEMENTS_PAGE_SIZE, productsApi } from "./api.js";
import {
  NOTE_MAX_LENGTH,
  formatDelta,
  hasMorePages,
  movementReference,
  productRequestBody,
  reasonLabel,
  sortMovementsNewestFirst,
  validateNote,
} from "./helpers.js";
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
      <p class="hint" data-preview></p>
      <label class="field">
        <span class="field-label">Note <span class="req">*</span></span>
        <textarea name="note" rows="2" maxlength="${NOTE_MAX_LENGTH}" required
          placeholder="Why the stock changed, e.g. “Recount”, “Damaged in transit”, “Made 40 units”"></textarea>
        <span class="field-error" data-error-for="note"></span>
        <span class="hint">Saved in the product's stock history.</span>
      </label>`,
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
      const { note, error: noteError } = validateNote(form.elements.note.value);
      let ok = true;
      if (Number.isNaN(amount) || amount < 1) {
        setFieldError(form, "amount", "Enter a whole number of 1 or more");
        ok = false;
      } else if (amount > MAX_INT) {
        setFieldError(form, "amount", "That number is too large");
        ok = false;
      } else if (remove && amount > current) {
        setFieldError(form, "amount", `Only ${current} in stock`);
        ok = false;
      }
      if (noteError) {
        setFieldError(form, "note", noteError);
        ok = false;
      }
      if (!ok) return false;
      try {
        const updated = await productsApi.adjustStock(product.id, remove ? -amount : amount, note);
        toast(`Stock for ${updated.name} is now ${updated.quantity}`, { type: "success" });
        return updated;
      } catch (error) {
        // The backend names the amount "delta"; show that error next to the Units field.
        if (error instanceof ApiError) {
          const delta = error.fieldErrors.find((fe) => fe.field === "delta");
          if (delta) setFieldError(form, "amount", delta.message);
          error.fieldErrors = error.fieldErrors.filter((fe) => fe.field !== "delta");
        }
        throw error;
      }
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
    else if (button.dataset.action === "history") openMovementsDialog(product);
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
          <button type="button" class="button button-small" data-action="history" data-id="${escapeHtml(p.id)}">History</button>
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
      ${
        product
          ? `<label class="field">
        <span class="field-label">Quantity in stock</span>
        <input name="quantity" type="number" value="${value(p.quantity)}" readonly disabled>
        <span class="field-error" data-error-for="quantity"></span>
        <span class="hint">Change stock with the Stock button, so the change and its reason are recorded in the history.</span>
      </label>`
          : `<label class="field">
        <span class="field-label">Starting stock <span class="req">*</span></span>
        <input name="quantity" type="number" min="0" step="1" required value="${value(p.quantity ?? 0)}">
        <span class="field-error" data-error-for="quantity"></span>
        <span class="hint">Recorded in the stock history as “Initial stock”.</span>
      </label>`
      }
      <label class="field span-2">
        <span class="field-label">Description</span>
        <textarea name="description" maxlength="1000" rows="3">${value(p.description)}</textarea>
        <span class="field-error" data-error-for="description"></span>
      </label>
    </div>`;
}

/**
 * Validates the product form. Returns the field values, or null after marking the bad fields.
 * When editing, quantity is read-only and not checked: it is never sent on update.
 */
function readProductForm(form, editing) {
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

  if (!editing) {
    if (f.quantity.validity.badInput) fail("quantity", "Enter a whole number");
    else if (!quantityText) fail("quantity", "Quantity is required");
    else if (Number.isNaN(parseWholeNumber(quantityText))) fail("quantity", "Use a whole number of 0 or more");
    else if (Number(quantityText) > MAX_INT) fail("quantity", "Quantity is too large");
  }

  if (!ok) return null;
  return {
    name,
    sku,
    description,
    price: Number(priceText),
    quantity: editing ? undefined : Number(quantityText),
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
      const values = readProductForm(form, editing);
      if (!values) return false;
      const body = productRequestBody(values, { editing, version: loadedVersion });
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
              form.elements.quantity.value = latest.quantity;
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

// ---------------------------------------------------------------------------
// Stock history
// ---------------------------------------------------------------------------

function movementsTable(movements) {
  if (!movements.length) {
    return `<div class="placeholder placeholder-empty">No stock movements recorded yet.</div>`;
  }
  const rows = movements
    .map((m) => {
      const delta = Number(m.delta);
      const ref = movementReference(m);
      let refCell = `<span class="muted">—</span>`;
      if (ref && ref.href) refCell = `<a href="${escapeHtml(ref.href)}" data-close>${escapeHtml(ref.label)}</a>`;
      else if (ref) refCell = escapeHtml(ref.label);
      return `
        <tr>
          <td>${escapeHtml(formatDateTime(m.createdAt))}</td>
          <td><span class="reason reason-${escapeHtml(String(m.reason || "").toLowerCase())}">${escapeHtml(reasonLabel(m.reason))}</span></td>
          <td class="num"><span class="delta ${delta > 0 ? "delta-in" : "delta-out"}">${escapeHtml(formatDelta(delta))}</span></td>
          <td><div class="movement-note">${m.note ? escapeHtml(m.note) : `<span class="muted">—</span>`}</div></td>
          <td>${refCell}</td>
        </tr>`;
    })
    .join("");
  return `
    <div class="table-wrap">
      <table class="table table-compact">
        <thead><tr><th>Date</th><th>Reason</th><th class="num">Change</th><th>Note</th><th>Reference</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>`;
}

/** Shows a product's stock movements, newest first, one page at a time. */
function openMovementsDialog(product) {
  let movements = [];
  let nextPage = 0;
  return openDialog({
    title: `Stock history: ${product.name}`,
    cancelLabel: "Close",
    wide: true,
    body: `
      <p class="muted">SKU ${escapeHtml(product.sku)} · currently <strong>${escapeHtml(product.quantity)}</strong> in stock</p>
      <div data-movements></div>
      <div class="movements-more" hidden>
        <button type="button" class="button" data-action="more">Load older movements</button>
      </div>`,
    onOpen(form, dialog) {
      const listEl = form.querySelector("[data-movements]");
      const moreEl = form.querySelector(".movements-more");
      const moreButton = moreEl.querySelector("button");

      const load = async () => {
        moreButton.disabled = true;
        if (!movements.length) renderPlaceholder(listEl, "loading", "Loading stock history…");
        try {
          const rows = await productsApi.movements(product.id, { page: nextPage, size: MOVEMENTS_PAGE_SIZE });
          nextPage++;
          movements = sortMovementsNewestFirst([...movements, ...rows]);
          listEl.innerHTML = movementsTable(movements);
          moreEl.hidden = !hasMorePages(rows, MOVEMENTS_PAGE_SIZE);
        } catch (error) {
          if (movements.length) showError(error, "Could not load older movements");
          else showLoadError(listEl, error, "Could not load the stock history");
        } finally {
          moreButton.disabled = false;
        }
      };

      form.addEventListener("click", (event) => {
        if (event.target.closest('[data-action="more"]')) load();
        // An order link switches to the Orders view, so close the dialog on the way.
        else if (event.target.closest("a[data-close]")) dialog.close();
      });
      load();
    },
  });
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
