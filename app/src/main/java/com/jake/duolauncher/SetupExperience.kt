package com.jake.duolauncher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal enum class SetupEntryDecision { SHOW, ALREADY_FINISHED, EXISTING_INSTALL }

internal fun setupEntryDecision(
    finished: Boolean,
    started: Boolean,
    hadLauncherState: Boolean,
): SetupEntryDecision = when {
    finished -> SetupEntryDecision.ALREADY_FINISHED
    started -> SetupEntryDecision.SHOW
    hadLauncherState -> SetupEntryDecision.EXISTING_INSTALL
    else -> SetupEntryDecision.SHOW
}

/** Setup is intentionally separate from launcher state so it cannot rewrite an upgraded layout. */
internal class SetupExperience(context: Context, prefsName: String = PREFS) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun entryDecision(hadLauncherState: Boolean): SetupEntryDecision {
        val decision = setupEntryDecision(
            finished = prefs.getBoolean(FINISHED, false),
            started = prefs.getBoolean(STARTED, false),
            hadLauncherState = hadLauncherState,
        )
        // A fresh launch writes only setup state. This keeps the cohort stable after the
        // launcher creates its normal layout preferences or Android recreates the activity.
        if (decision == SetupEntryDecision.SHOW && !prefs.getBoolean(STARTED, false))
            prefs.edit().putBoolean(STARTED, true).commit()
        return decision
    }

    fun finish() {
        // Finish before dismissing the sheet so process death cannot make it reappear.
        prefs.edit().putBoolean(FINISHED, true).commit()
    }

    companion object {
        private const val PREFS = "setup_experience"
        private const val FINISHED = "finished"
        private const val STARTED = "started"

        fun hadLauncherState(context: Context): Boolean =
            context.getSharedPreferences("launcher", Context.MODE_PRIVATE).all.isNotEmpty() ||
                context.getSharedPreferences("app_catalog", Context.MODE_PRIVATE).all.isNotEmpty()
    }
}

@Composable
private fun LegacyFirstRunSetupSheet(
    isDefaultHome: Boolean,
    onMakeDefault: () -> Unit,
    onAddWidget: () -> Unit,
    onExplore: () -> Unit,
    onSkip: () -> Unit,
) {
    val maxHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * .9f).toDp()
    }
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(54.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Home, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Welcome to Duo", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "A quiet Home screen built for both sides of your foldable.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSkip, Modifier.testTag("setup-close")) {
                Icon(Icons.Rounded.Close, "Close welcome")
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .52f),
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SetupGuideRow(
                    icon = if (isDefaultHome) Icons.Rounded.Check else Icons.Rounded.Home,
                    title = if (isDefaultHome) "Duo is your Home app" else "Choose Duo as your Home app",
                    detail = if (isDefaultHome) "The Home button returns here."
                        else "Android will show the Home app chooser. You can switch back anytime.",
                )
                if (!isDefaultHome) Button(
                    onClick = onMakeDefault,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-make-default"),
                ) { Text("Choose Home app") }
                HorizontalDivider()
                SetupGuideRow(
                    icon = Icons.Rounded.Widgets,
                    title = "Make the space useful",
                    detail = "Add a widget now, or long-press empty space later to customize any page.",
                )
                OutlinedButton(
                    onClick = onAddWidget,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-add-widget"),
                ) { Text("Add a widget") }
            }
        }

        Button(
            onClick = onExplore,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("setup-explore"),
        ) { Text("Explore Home") }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag("setup-skip"),
        ) { Text("Not now") }
    }
}

@Composable
private fun SetupGuideRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
