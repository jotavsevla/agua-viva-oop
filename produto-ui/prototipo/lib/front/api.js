(() => {
  function buildApiError(responseStatus, payload) {
    const message = payload?.erro || payload?.message || `HTTP ${responseStatus}`;
    const error = new Error(String(message));
    error.status = responseStatus;
    error.payload = payload;
    return error;
  }

  async function requestJson(baseUrl, path, options = {}) {
    const url = `${String(baseUrl || "").replace(/\/+$/, "")}${path}`;
    const response = await fetch(url, {
      method: options.method || "GET",
      headers: {
        "Content-Type": "application/json",
        ...(options.headers || {})
      },
      body: options.body ? JSON.stringify(options.body) : undefined
    });

    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw buildApiError(response.status, payload);
    }
    return {
      payload,
      status: response.status
    };
  }

  const apiClient = {
    requestJson,
    buildApiError
  };

  if (typeof window !== "undefined") {
    window.AguaVivaApi = apiClient;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = apiClient;
  }
})();
