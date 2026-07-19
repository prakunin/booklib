export const environment = {
  production: false,
  API_CONFIG: {
    BASE_URL: globalThis.location.origin,
    BROKER_URL:
      globalThis.location.protocol === 'https:'
        ? `wss://${globalThis.location.host}/ws`
        : `ws://${globalThis.location.host}/ws`,
  },
};
