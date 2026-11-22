package __PACOTE__

import android.service.notification.NotificationListenerService

/**
 * Não faz nada sozinho. Existir como "Acesso às notificações" é o que dá ao HAVEN o direito de ler as sessões de mídia
 * (música tocando) via MediaSessionManager — ver HavenAtividadePlugin.musicaAtual().
 */
class HavenMediaListener : NotificationListenerService()
