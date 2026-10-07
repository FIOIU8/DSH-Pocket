package com.fioiu8.dshpocket

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fioiu8.dshpocket.engine.EngineController
import com.fioiu8.dshpocket.engine.EngineService
import com.fioiu8.dshpocket.ui.AppShell
import com.fioiu8.dshpocket.ui.AppLanguagePreferences
import com.fioiu8.dshpocket.ui.theme.DSHPocketTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguagePreferences.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        EngineService.start(this)
        // One engine per process; the shell only renders its state.
        val controller = EngineController.get(applicationContext)
        setContent {
            DSHPocketTheme {
                AppShell(
                    controller = controller,
                    language = AppLanguagePreferences.selectedLanguage(this@MainActivity),
                    onLanguageChange = { language ->
                        AppLanguagePreferences.save(this@MainActivity, language)
                        recreate()
                    },
                )
            }
        }
    }
}
