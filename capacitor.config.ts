import type { CapacitorConfig } from '@capacitor/cli';
// eslint-disable-next-line @typescript-eslint/no-var-requires
const cfg = require('./calls.config.json');
const base = String(cfg.baseUrl).replace(/\/$/, '');
const host = new URL(base).host;

const config: CapacitorConfig = {
  appId: cfg.appId,
  appName: cfg.appName,
  webDir: 'www',
  backgroundColor: '#12131b',     // fundo do WebView enquanto o site carrega (sem flash branco/cinza entre a abertura e o H animado)
  server: {
    // O app abre o site da call como página principal. Só assim o Capacitor injeta a ponte nativa
    // na página (sem isso o site não enxerga o plugin de transmissão de tela).
    url: base + '/call/',
    // páginas que o app pode abrir dentro dele (site da call + login do Discord + mídia)
    allowNavigation: [host, 'discord.com', '*.discord.com', '*.livekit.cloud',
      // login das conexões do perfil (Steam, GitHub, Twitch, Spotify, Reddit, Roblox, Xbox/Minecraft, Battle.net)
      // abre DENTRO do app, igual ao Discord. O Google (YouTube) bloqueia login em app embutido e continua abrindo no navegador.
      'steamcommunity.com', '*.steamcommunity.com', 'github.com', '*.github.com', '*.twitch.tv', 'accounts.spotify.com',
      '*.spotify.com', '*.reddit.com', '*.roblox.com', '*.live.com', '*.microsoftonline.com', '*.microsoft.com', '*.xbox.com',
      '*.battle.net'],
  },
  android: { allowMixedContent: false, appendUserAgent: 'CallsFFZApp' },
};
export default config;
