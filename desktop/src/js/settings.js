// UI preferences kept in localStorage. The backend URL lives in api.js.

const THRESHOLD_KEY = "inventix.lowStockThreshold";
export const DEFAULT_LOW_STOCK_THRESHOLD = 10;

export function getLowStockThreshold() {
  try {
    const value = Number.parseInt(localStorage.getItem(THRESHOLD_KEY), 10);
    return Number.isInteger(value) && value >= 0 ? value : DEFAULT_LOW_STOCK_THRESHOLD;
  } catch {
    return DEFAULT_LOW_STOCK_THRESHOLD;
  }
}

export function setLowStockThreshold(value) {
  const number = Number(value);
  if (!Number.isInteger(number) || number < 0) {
    throw new Error("The low-stock threshold must be a whole number of 0 or more");
  }
  try {
    localStorage.setItem(THRESHOLD_KEY, String(number));
  } catch {
    // Ignore: the value then only lasts for this session.
  }
  return number;
}
