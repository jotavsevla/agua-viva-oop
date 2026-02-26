(() => {
  const API_BASE_STORAGE_KEY = "aguaVivaApiBaseUrl";
  const DEFAULT_API_BASE = "http://localhost:8082";

  function sanitizeBaseUrl(value) {
    const trimmed = String(value || "").trim();
    return trimmed.replace(/\/+$/, "");
  }

  function readStoredApiBase() {
    try {
      const stored = window.localStorage.getItem(API_BASE_STORAGE_KEY);
      const sanitized = sanitizeBaseUrl(stored);
      if (sanitized) {
        return sanitized;
      }
      return DEFAULT_API_BASE;
    } catch (_) {
      return DEFAULT_API_BASE;
    }
  }

  function persistApiBase(baseUrl) {
    const sanitized = sanitizeBaseUrl(baseUrl);
    if (!sanitized) {
      return;
    }
    try {
      window.localStorage.setItem(API_BASE_STORAGE_KEY, sanitized);
    } catch (_) {
      // Sem persistencia local.
    }
  }

  function clearStoredApiBase() {
    try {
      window.localStorage.removeItem(API_BASE_STORAGE_KEY);
    } catch (_) {
      // Sem persistencia local.
    }
  }

  const storageApi = {
    API_BASE_STORAGE_KEY,
    DEFAULT_API_BASE,
    sanitizeBaseUrl,
    readStoredApiBase,
    persistApiBase,
    clearStoredApiBase
  };

  if (typeof window !== "undefined") {
    window.AguaVivaStorage = storageApi;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = storageApi;
  }
})();
