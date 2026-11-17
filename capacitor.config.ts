import type { CapacitorConfig } from '@capacitor/cli';
// eslint-disable-next-line @typescript-eslint/no-var-requires
const cfg = require('./calls.config.json');
const host = new URL(cfg.baseUrl).host;

const config: CapacitorConfig = {
  appId: cfg.appId,
  appName: cfg.appName,
  webDir: 'www',
  server: {
    // páginas que o app pode abrir dentro dele (site da call + login do Discord + mídia)
    allowNavigation: [host, 'discord.com', '*.discord.com', '*.livekit.cloud'],
  },
  android: { allowMixedContent: false, appendUserAgent: 'CallsFFZApp' },
};
export default config;
