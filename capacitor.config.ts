import type { CapacitorConfig } from '@capacitor/cli';
// eslint-disable-next-line @typescript-eslint/no-var-requires
const cfg = require('./calls.config.json');
const base = String(cfg.baseUrl).replace(/\/$/, '');
const host = new URL(base).host;

const config: CapacitorConfig = {
  appId: cfg.appId,
  appName: cfg.appName,
  webDir: 'www',
  server: {
    // O app abre o site da call como página principal. Só assim o Capacitor injeta a ponte nativa
    // na página (sem isso o site não enxerga o plugin de transmissão de tela).
    url: base + '/call/',
    // páginas que o app pode abrir dentro dele (site da call + login do Discord + mídia)
    allowNavigation: [host, 'discord.com', '*.discord.com', '*.livekit.cloud'],
  },
  android: { allowMixedContent: false, appendUserAgent: 'CallsFFZApp' },
};
export default config;
