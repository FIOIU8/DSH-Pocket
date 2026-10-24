package com.fioiu8.dshpocket

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fioiu8.dshpocket.engine.EngineController
import com.fioiu8.dshpocket.engine.EngineService
import com.fioiu8.dshpocket.ui.AppShell
import com.fioiu8.dshpocket.ui.AppLanguagePreferences
import com.fioiu8.dshpocket.ui.onboarding.OnboardingFlow
import com.fioiu8.dshpocket.ui.onboarding.OnboardingPreferences
import com.fioiu8.dshpocket.ui.theme.DSHPocketTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguagePreferences.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // First launch shows the onboarding instead of the shell, and the engine is
        // deliberately NOT started here: extraction + boot only begin once onboarding
        // reaches the permissions page, so the welcome/agreement pages stay light.
        var onboardingDone by mutableStateOf(OnboardingPreferences.isCompleted(this))
        if (onboardingDone) EngineService.start(this)
        // One engine per process; the shell only renders its state.
        val controller = EngineController.get(application)
        setContent {
            DSHPocketTheme {
                Crossfade(targetState = onboardingDone, label = "onboarding-gate") { done ->
                    if (done) {
                        AppShell(
                            controller = controller,
                            language = AppLanguagePreferences.selectedLanguage(this@MainActivity),
                            onLanguageChange = { language ->
                                AppLanguagePreferences.save(this@MainActivity, language)
                                recreate()
                            },
                        )
                    } else {
                        OnboardingFlow(
                            controller = controller,
                            onStartEngine = {
                                EngineService.start(this@MainActivity)
                                // Fallback if the foreground service was rejected: the
                                // controller boots on its own, mirroring AppShell.
                                controller.start()
                            },
                            onComplete = {
                                OnboardingPreferences.setCompleted(this@MainActivity, true)
                                onboardingDone = true
                            },
                        )
                    }
                }
            }
        }
    }
}
