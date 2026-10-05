// Shared UI helpers: formatting, toasts, dialogs and form error display.

import { ApiError, NetworkError } from "./api.js";

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

const HTML_ESCAPES = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };

/** Escapes a value for use inside HTML text or a quoted attribute. */
export function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (ch) => HTML_ESCAPES[ch]);
}

// The API does not define a currency; amounts are shown as US dollars.
const moneyFormat = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });
const numberFormat = new Intl.NumberFormat("en-US");

export function formatMoney(value) {
  const number = Number(value);
  return Number.isFinite(number) ? moneyFormat.format(number) : "—";
}

export function formatNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? numberFormat.format(number) : "—";
}

/** Formats a server LocalDateTime such as "2026-10-04T10:15:30.123456" in the user's locale. */
export function formatDateTime(value) {
  if (!value) return "—";
  // Trim microseconds to milliseconds so every browser engine can parse it. No zone = local time.
  const date = new Date(String(value).replace(/(\.\d{3})\d+/, "$1"));
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

export function statusBadge(status) {
  const safe = escapeHtml(status || "UNKNOWN");
  return `<span class="badge badge-${safe.toLowerCase()}">${safe}</span>`;
}

export function stockBadge(quantity, threshold) {
  const qty = Number(quantity);
  let cls = "stock-ok";
  if (qty <= 0) cls = "stock-out";
  else if (qty <= threshold) cls = "stock-low";
  return `<span class="stock ${cls}">${escapeHtml(formatNumber(qty))}</span>`;
}

export function debounce(fn, wait = 200) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), wait);
  };
}

// ---------------------------------------------------------------------------
// Toasts
// ---------------------------------------------------------------------------

/**
 * Shows a toast in the corner. type: "info" | "success" | "error" | "warning".
 * details: optional list of extra lines (e.g. field errors).
 */
export function toast(message, { type = "info", details = [], timeout } = {}) {
  const container = document.getElementById("toasts");
  if (!container) return;

  const item = document.createElement("div");
  item.className = `toast toast-${type}`;
  item.setAttribute("role", type === "error" ? "alert" : "status");

  const text = document.createElement("div");
  text.className = "toast-message";
  text.textContent = message;
  item.appendChild(text);

  if (details.length) {
    const list = document.createElement("ul");
    list.className = "toast-details";
    details.forEach((line) => {
      const li = document.createElement("li");
      li.textContent = line;
      list.appendChild(li);
    });
    item.appendChild(list);
  }

  const close = document.createElement("button");
  close.type = "button";
  close.className = "toast-close";
  close.setAttribute("aria-label", "Dismiss");
  close.textContent = "×";
  close.addEventListener("click", () => item.remove());
  item.appendChild(close);

  container.appendChild(item);
  const lifetime = timeout ?? (type === "error" ? 8000 : 4000);
  if (lifetime > 0) setTimeout(() => item.remove(), lifetime);
}

/** Turns any error into a short message plus optional detail lines. */
export function describeError(error) {
  if (error instanceof NetworkError) {
    return {
      message: error.message,
      details: [
        "Check that the backend is running (.\\gradlew.bat bootRun) and that the URL in Settings is correct.",
        `If it is running, the request may be blocked by CORS: this page's origin (${window.location.origin}) must be allowed in WebConfig.java.`,
      ],
    };
  }
  if (error instanceof ApiError) {
    const details = error.fieldErrors.map((fe) => `${fe.field}: ${fe.message}`);
    const prefix = error.status ? `${error.status}${error.error ? " " + error.error : ""}: ` : "";
    return { message: prefix + error.message, details };
  }
  return { message: (error && error.message) || String(error), details: [] };
}

/** Shows an error as a toast. context is a short prefix such as "Could not save product". */
export function showError(error, context) {
  const { message, details } = describeError(error);
  toast(context ? `${context}. ${message}` : message, { type: "error", details });
}

// ---------------------------------------------------------------------------
// Form field errors
// ---------------------------------------------------------------------------

export function clearFieldErrors(form) {
  form.querySelectorAll(".invalid").forEach((el) => el.classList.remove("invalid"));
  form.querySelectorAll(".field-error").forEach((el) => {
    el.textContent = "";
  });
  const banner = form.querySelector(".form-error");
  if (banner) {
    banner.textContent = "";
    banner.hidden = true;
  }
}

/** Marks a field as invalid. Returns false if the form has no field with that name. */
export function setFieldError(form, name, message) {
  const target = form.querySelector(`.field-error[data-error-for="${CSS.escape(name)}"]`);
  if (!target) return false;
  target.textContent = message;
  const input = form.elements.namedItem(name);
  if (input && input.classList) input.classList.add("invalid");
  return true;
}

/** Shows a message in the form's .form-error banner. */
export function setFormError(form, message, details = []) {
  const banner = form.querySelector(".form-error");
  if (!banner) {
    toast(message, { type: "error", details });
    return;
  }
  banner.textContent = "";
  const text = document.createElement("div");
  text.textContent = message;
  banner.appendChild(text);
  if (details.length) {
    const list = document.createElement("ul");
    details.forEach((line) => {
      const li = document.createElement("li");
      li.textContent = line;
      list.appendChild(li);
    });
    banner.appendChild(list);
  }
  banner.hidden = false;
}

/** Shows a backend error inside a form: field errors next to fields, the rest in the banner. */
export function showFormError(form, error) {
  if (error instanceof ApiError && error.fieldErrors.length) {
    const unmatched = error.fieldErrors
      .filter((fe) => !setFieldError(form, fe.field, fe.message))
      .map((fe) => `${fe.field}: ${fe.message}`);
    setFormError(form, error.message, unmatched);
    return;
  }
  const { message, details } = describeError(error);
  setFormError(form, message, details);
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

/**
 * Opens a modal dialog containing a form.
 *
 * options.title       dialog title
 * options.body        HTML string for the form fields (caller escapes any data)
 * options.submitLabel label of the primary button (omit for no primary button)
 * options.danger      style the primary button as destructive
 * options.wide        use a wider dialog
 * options.onOpen(form, dialog)   called after the dialog is shown
 * options.onSubmit(form)         may be async; throw to keep the dialog open and show the error,
 *                                return false to keep it open silently, anything else closes it.
 *
 * Returns a promise that resolves with onSubmit's result, or null if the dialog was cancelled.
 */
export function openDialog({
  title,
  body,
  submitLabel,
  cancelLabel = "Cancel",
  danger = false,
  wide = false,
  onOpen,
  onSubmit,
}) {
  return new Promise((resolve) => {
    const dialog = document.createElement("dialog");
    dialog.className = wide ? "modal modal-wide" : "modal";
    dialog.innerHTML = `
      <form class="modal-form" novalidate>
        <header class="modal-header">
          <h2>${escapeHtml(title)}</h2>
          <button type="button" class="icon-button" data-action="cancel" aria-label="Close">×</button>
        </header>
        <div class="modal-body">
          <div class="form-error" role="alert" hidden></div>
          ${body}
        </div>
        <footer class="modal-footer">
          <button type="button" class="button" data-action="cancel">${escapeHtml(cancelLabel)}</button>
          ${
            submitLabel
              ? `<button type="submit" class="button ${danger ? "button-danger" : "button-primary"}">${escapeHtml(submitLabel)}</button>`
              : ""
          }
        </footer>
      </form>`;
    document.body.appendChild(dialog);

    const form = dialog.querySelector("form");
    let result = null;
    let busy = false;

    dialog.addEventListener("close", () => {
      dialog.remove();
      resolve(result);
    });
    // Escape key: ignore while a request is running.
    dialog.addEventListener("cancel", (event) => {
      if (busy) event.preventDefault();
    });
    dialog.querySelectorAll('[data-action="cancel"]').forEach((button) =>
      button.addEventListener("click", () => {
        if (!busy) dialog.close();
      })
    );

    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      if (busy || !onSubmit) return;
      clearFieldErrors(form);
      const submit = form.querySelector('button[type="submit"]');
      busy = true;
      if (submit) submit.disabled = true;
      try {
        const value = await onSubmit(form);
        if (value !== false) {
          result = value === undefined ? true : value;
          busy = false;
          dialog.close();
          return;
        }
      } catch (error) {
        showFormError(form, error);
      }
      busy = false;
      if (submit) submit.disabled = false;
    });

    dialog.showModal();
    if (onOpen) onOpen(form, dialog);
    const first = form.querySelector("input:not([type=hidden]), select, textarea");
    if (first) first.focus();
  });
}

/** Asks a yes/no question. Resolves true if confirmed. */
export async function confirmDialog({ title, message, confirmLabel = "Confirm", danger = false }) {
  const result = await openDialog({
    title,
    body: `<p class="confirm-message">${escapeHtml(message)}</p>`,
    submitLabel: confirmLabel,
    danger,
    onSubmit: () => true,
  });
  return result === true;
}

/** Renders a loading / empty / error placeholder into a container. */
export function renderPlaceholder(container, kind, message) {
  container.innerHTML = `<div class="placeholder placeholder-${escapeHtml(kind)}">${escapeHtml(message)}</div>`;
}

/**
 * Shows a load failure in place of the content. Network failures are already announced by the
 * connection banner, so only backend errors also get a toast.
 */
export function showLoadError(container, error, context) {
  const { message } = describeError(error);
  renderPlaceholder(container, "error", message);
  if (!(error instanceof NetworkError)) showError(error, context);
}
