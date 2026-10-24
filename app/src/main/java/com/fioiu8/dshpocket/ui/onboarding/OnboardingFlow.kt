package com.fioiu8.dshpocket.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.fioiu8.dshpocket.R
import com.fioiu8.dshpocket.capability.Capabilities
import com.fioiu8.dshpocket.capability.CapabilitySnapshot
import com.fioiu8.dshpocket.engine.EngineController
import com.fioiu8.dshpocket.engine.EngineService
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.ui.readCapabilities
import com.fioiu8.dshpocket.ui.theme.DshMotion
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import com.fioiu8.dshpocket.ui.theme.dshControl
import com.fioiu8.dshpocket.ui.theme.dshSpring
import com.fioiu8.dshpocket.ui.theme.motionDuration
import com.fioiu8.dshpocket.ui.theme.systemAnimationsEnabled

/** Reactive inputs a page renders against; rebuilt on every change of its parts. */
data class OnboardingEnv(
    val engineState: EngineState,
    val agreed: Boolean,
    val capabilities: CapabilitySnapshot,
)

/** What a page can do; plain lambdas so pages stay decoupled from flow mechanics. */
class OnboardingActions(
    val env: OnboardingEnv,
    val advance: () -> Unit,
    val back: () -> Unit,
    val setAgreed: (Boolean) -> Unit,
    val startEngine: () -> Unit,
    val grantAllFiles: () -> Unit,
    val retryEngine: () -> Unit,
    val finish: () -> Unit,
)

/**
 * One page of the first-launch onboarding.
 *
 * Pages live in [onboardingPages] as a plain list: inserting an entry there is all it
 * takes to extend the flow — paging, transitions, step persistence, back handling and
 * the bottom engine progress bar are all owned by [OnboardingFlow].
 */
data class OnboardingPage(
    val key: String,
    /** Icon shown in the persistent header circle while this page is current. */
    val icon: ImageVector,
    /** Whether the bottom engine progress bar shows while this page is current. */
    val showsEngineProgress: Boolean = false,
    /** Gate for the continue button; the engine still boots while the user reads. */
    val canAdvance: (OnboardingEnv) -> Boolean = { true },
    val ctaLabel: Int = R.string.onboarding_next,
    val onCta: (OnboardingActions) -> Unit = { it.advance() },
    val content: @Composable (OnboardingActions) -> Unit,
)

/**
 * First-launch onboarding: welcome, agreement, optional permissions, done.
 *
 * Nothing is extracted or started until the flow reaches a page that asks for it
 * (the permissions page triggers the engine), so the early pages stay lightweight and
 * the user can leave at any point — system back on the first page exits the app.
 */
@Composable
fun OnboardingFlow(
    controller: EngineController,
    onStartEngine: () -> Unit,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val animationsEnabled = systemAnimationsEnabled()
    val pageDuration = motionDuration(DshMotion.pageDurationMillis)
    val exitFadeDuration = motionDuration(140)
    val exitSlideDuration = motionDuration(160)

    var agreed by rememberSaveable { mutableStateOf(false) }
    var capabilities by remember { mutableStateOf(readCapabilities(context)) }
    var step by rememberSaveable {
        mutableStateOf(OnboardingPreferences.readStep(context).coerceIn(0, onboardingPages.lastIndex))
    }

    LaunchedEffect(step) { OnboardingPreferences.saveStep(context, step) }

    // Returning from the system all-files page changes no navigation state, so the
    // grant is only picked up through a lifecycle resume — same as the main shell.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val fresh = readCapabilities(context)
            if (fresh != capabilities) {
                capabilities = fresh
                controller.refreshBriefing(fresh)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Exit-anytime on the first page: no back handler there, so system back finishes
    // the activity. Later pages walk back one step instead.
    BackHandler(enabled = step > 0) { step -= 1 }

    val env = OnboardingEnv(engineState = state, agreed = agreed, capabilities = capabilities)
    val page = onboardingPages[step]
    val retryEngine = {
        EngineService.start(context)
        controller.restart()
    }
    val actions = OnboardingActions(
        env = env,
        advance = { if (step < onboardingPages.lastIndex) step += 1 },
        back = { if (step > 0) step -= 1 },
        setAgreed = { agreed = it },
        startEngine = onStartEngine,
        grantAllFiles = {
            Capabilities.allFilesAccessIntent(context)?.let(context::startActivity)
        },
        retryEngine = retryEngine,
        finish = onComplete,
    )

    Scaffold(
        bottomBar = {
            if (page.showsEngineProgress) {
                EngineProgressFooter(state = state, onRetry = retryEngine)
            }
        },
    ) { padding ->
        // Scaffold's content padding already carries the status-bar inset (no top bar
        // here) and the footer's navigation inset; don't add either again.
        Column(Modifier.padding(padding).fillMaxSize()) {
            OnboardingHeader(step = step)
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    (
                        fadeIn(animationSpec = tween(pageDuration)) +
                            slideInHorizontally(
                                animationSpec = dshSpring(animationsEnabled),
                                initialOffsetX = { if (forward) it / 6 else -it / 6 },
                            )
                        ) togetherWith (
                        fadeOut(animationSpec = tween(exitFadeDuration)) +
                            slideOutHorizontally(
                                animationSpec = tween(exitSlideDuration),
                                targetOffsetX = { if (forward) -it / 12 else it / 12 },
                            )
                        ) using SizeTransform(clip = false)
                },
                modifier = Modifier.weight(1f),
                label = "onboarding-page",
            ) { index ->
                onboardingPages[index].content(actions)
            }
            PageControls(page = page, env = env, step = step, actions = actions)
        }
    }
}

/**
 * The welcome page's big circular hero icon, promoted to a persistent header: every
 * page has one at the top (different glyph per page), and on page change the old
 * glyph slides out to the left while the new one slides in from the right — the same
 * direction the pages themselves move.
 */
@Composable
private fun OnboardingHeader(step: Int) {
    val animationsEnabled = systemAnimationsEnabled()
    val pageDuration = motionDuration(DshMotion.pageDurationMillis)
    val exitFadeDuration = motionDuration(140)
    val exitSlideDuration = motionDuration(160)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            // Fixed distance below the status bar: the header is laid out before the
            // page area, so content length never shifts the icon's position.
            .padding(top = 48.dp, bottom = DshSpacing.section),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    (
                        fadeIn(animationSpec = tween(pageDuration)) +
                            slideInHorizontally(
                                animationSpec = dshSpring(animationsEnabled),
                                initialOffsetX = { if (forward) it / 2 else -it / 2 },
                            )
                        ) togetherWith (
                        fadeOut(animationSpec = tween(exitFadeDuration)) +
                            slideOutHorizontally(
                                animationSpec = tween(exitSlideDuration),
                                targetOffsetX = { if (forward) -it / 2 else it / 2 },
                            )
                        )
                },
                label = "onboarding-header-icon",
            ) { index ->
                Icon(
                    onboardingPages[index].icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
    }
}

/** Step dots plus the back/continue row pinned below the page content. */
@Composable
private fun PageControls(
    page: OnboardingPage,
    env: OnboardingEnv,
    step: Int,
    actions: OnboardingActions,
) {
    val stepPages = stringResource(R.string.onboarding_step_pages, step + 1, onboardingPages.size)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.page, vertical = DshSpacing.section),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .semantics { contentDescription = stepPages },
        ) {
            repeat(onboardingPages.size) { index ->
                val selected = index == step
                Box(
                    Modifier
                        .size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                )
            }
        }
        Spacer(Modifier.height(DshSpacing.section))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) {
                TextButton(onClick = actions.back) {
                    Text(stringResource(R.string.onboarding_back))
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { page.onCta(actions) },
                enabled = page.canAdvance(env),
                modifier = Modifier.dshControl(),
            ) {
                Text(stringResource(page.ctaLabel), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Bootstrap progress pinned to the bottom: a real percentage while the runtime is
 * extracting, an indeterminate indicator while the engine binary starts (nothing
 * measurable happens between spawn and the launch URL), and an error row with a retry
 * if the boot failed.
 */
@Composable
private fun EngineProgressFooter(state: EngineState, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = DshSpacing.page, vertical = DshSpacing.section),
    ) {
        when (state) {
            is EngineState.Busy -> {
                val fraction = state.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FooterCaption(stringResource(R.string.onboarding_progress_extracting, (fraction * 100).toInt()))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    FooterCaption(stringResource(R.string.onboarding_progress_starting))
                }
            }
            is EngineState.Ready -> {
                LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                FooterCaption(stringResource(R.string.onboarding_progress_ready))
            }
            is EngineState.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(DshSpacing.compact))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.engine_failed_to_start),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.restart))
                    }
                }
            }
            EngineState.Idle -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                FooterCaption(stringResource(R.string.engine_preparing))
            }
        }
    }
}

@Composable
private fun FooterCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = DshSpacing.compact),
    )
}
