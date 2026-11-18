package __PACOTE__

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(ScreenSharePlugin::class.java)   // plugin de tela (nativo)
        super.onCreate(savedInstanceState)
    }
}
