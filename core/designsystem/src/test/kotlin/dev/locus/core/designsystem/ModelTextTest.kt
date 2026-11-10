package dev.locus.core.designsystem

import android.content.Context
import android.content.res.Resources
import android.text.format.DateUtils
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.model.ChatMode
import dev.locus.core.model.Effort
import dev.locus.core.model.HelperPrompt
import dev.locus.core.model.HelperTimeLimit
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.PauseReason
import dev.locus.core.model.ScheduleIntervals
import dev.locus.core.model.SettingsDestination
import dev.locus.core.model.SubagentStatus
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.ThermalLevel
import dev.locus.core.model.ToolGroup
import dev.locus.core.model.TriggerDecision
import dev.locus.core.model.Verification
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The screens' words for core/model's labels (ModelText.kt) and the design system's formatters. In
 * English each says exactly what the core English says, so English screens don't change while the
 * model keeps reading the core text (docs/design/localization.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelTextTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun s(id: Int) = res.getString(id)

    @Test
    fun `every enum value has English words equal to its core label`() {
        ChatMode.entries.forEach { assertEquals(it.label, s(it.labelRes)); assertEquals(it.description, s(it.descriptionRes)) }
        Effort.entries.forEach { assertEquals(it.label, s(it.labelRes)); assertEquals(it.description, s(it.descriptionRes)) }
        ToolGroup.entries.forEach { assertEquals(it.label, s(it.labelRes)); assertEquals(it.description, s(it.descriptionRes)) }
        MemoryScope.entries.forEach { assertEquals(it.label, s(it.labelRes)) }
        PauseReason.entries.forEach { assertEquals(it.chip, s(it.chipRes)) }
        ThermalLevel.entries.forEach { assertEquals(it.label, s(it.labelRes)) }
        TriggerDecision.entries.forEach { assertEquals(it.label, s(it.labelRes)); assertEquals(it.label.lowercase(), s(it.inlineRes)) }
        HelperPrompt.entries.forEach { assertEquals(it.label, s(it.labelRes)) }
        SubagentStatus.entries.forEach { assertEquals(it.label, s(it.labelRes)) }
    }

    @Test
    fun `a check's title matches the core title for every kind and verdict`() {
        Verification.Kind.entries.forEach { kind ->
            listOf(
                outcome(kind, passed = true),
                outcome(kind, passed = false),
                outcome(kind, passed = null),
                outcome(kind, passed = true, exitTrusted = false),
            ).forEach { o -> assertEquals(Verification.title(o), s(o.titleRes())) }
        }
        assertEquals(Verification.Verdict.entries.toSet(), Verification.Kind.entries.flatMap { k -> listOf(true, false, null).map { outcome(k, it).verdict } + outcome(k, true, exitTrusted = false).verdict }.toSet())
    }

    @Test
    fun `schedules read as the core labels`() {
        ScheduleIntervals.all.forEach {
            assertEquals(it.label, s(it.labelRes))
            assertEquals(it.short, s(it.shortRes))
        }
        listOf(45L, 60, 120, 180, 360, 1_440, 2_880, 4_320, 10_080, 20_160, 90).forEach { minutes ->
            assertEquals(ScheduleIntervals.label(minutes), scheduleLabel(res, minutes), "$minutes")
            assertEquals(ScheduleIntervals.label(minutes), scheduleText(minutes).asString(res), "$minutes")
        }
        HelperTimeLimit.CHOICES.forEach { assertEquals(HelperTimeLimit.label(it), helperTimeLimitLabel(res, it)) }
    }

    @Test
    fun `settings links name the page as the English path does`() {
        SettingsDestination.entries.forEach { d ->
            assertEquals(settingsPath(d), settingsPath(res, d), d.name)
            assertEquals("Open ${d.title}", settingsActionLabel(res, d), d.name)
        }
        assertEquals("Open SSH machines", settingsActionLabel(res, SettingsDestination.Ssh))
    }

    @Test
    fun `budgets, counts and percentages read as before`() {
        val budgets = listOf(
            TaskBudget(),
            TaskBudget(tokens = 200_000),
            TaskBudget(tokens = 1_000_000, costUsd = 2.0, minutes = 30),
            TaskBudget(tokens = 900, minutes = 120),
            TaskBudget(costUsd = 0.25, minutes = 45),
        )
        budgets.forEach { assertEquals(TaskBudgetPresets.summary(it), TaskBudgetPresets.summary(res, it)) }
        TaskBudgetPresets.tokens.forEach { assertEquals(TaskBudgetPresets.tokens(it), TaskBudgetPresets.tokens(res, it)) }
        TaskBudgetPresets.minutes.forEach { assertEquals(TaskBudgetPresets.minutes(it), TaskBudgetPresets.minutes(res, it)) }
        assertEquals("200k tokens · $2 · 30 min", TaskBudgetPresets.summary(res, TaskBudget(tokens = 200_000, costUsd = 2.0, minutes = 30)))

        listOf(0L, 942, 1_000, 12_000, 12_400, 99_960, 100_000, 999_999, 1_000_000, 1_250_000, 40_000_000).forEach {
            assertEquals(compactCountEnglish(it), compactCount(res, it), "$it")
        }
        assertEquals("12.4k", compactCount(res, 12_400))
    }

    @Test
    fun `a meter reads as a whole percentage`() {
        assertEquals("42 percent", meterPercent(res, 0.421f))
        assertEquals("100 percent", meterPercent(res, 3f))
        assertEquals("0 percent", meterPercent(res, Float.NaN))
    }

    @Test
    fun `counts cap so a chip never grows past two digits and a plus`() {
        assertEquals("0", chipCount(res, -3))
        assertEquals("7", chipCount(res, 7))
        assertEquals("99", chipCount(res, 99))
        assertEquals("99+", chipCount(res, 100))
        assertEquals("9+", chipCount(res, 12, max = 9))
    }

    @Test
    fun `times read short and say just now under a minute`() {
        val now = 10 * DateUtils.DAY_IN_MILLIS
        assertEquals("Just now", relativeTime(res, now - 20_000, now))
        assertEquals("Just now", shortRelativeTime(res, now - 20_000, now))
        assertEquals("5m ago", shortRelativeTime(res, now - 5 * DateUtils.MINUTE_IN_MILLIS, now))
        assertEquals("2h ago", shortRelativeTime(res, now - 2 * DateUtils.HOUR_IN_MILLIS, now))
        assertEquals("3d ago", shortRelativeTime(res, now - 3 * DateUtils.DAY_IN_MILLIS, now))
    }

    @Test
    @Config(qualifiers = "es")
    fun `in Spanish the same values read in Spanish`() {
        assertEquals("Solo lectura", s(ChatMode.READ_ONLY.labelRes))
        assertEquals("Cada 2 días", scheduleLabel(res, 2_880))
        assertEquals("Ajustes → Acerca de y privacidad → Diagnóstico", settingsPath(res, SettingsDestination.Diagnostics))
        assertEquals("Abrir Máquinas SSH", settingsActionLabel(res, SettingsDestination.Ssh))
        assertEquals("Ahora mismo", relativeTime(res, 1_000, 2_000))
        // Every listed value has its own Spanish words, not the English fallback.
        ChatMode.entries.forEach { assertNotEquals(it.label, s(it.labelRes)) }
        TriggerDecision.entries.forEach { assertNotEquals(it.label, s(it.labelRes)) }
        Verification.Kind.entries.forEach { k -> assertNotEquals(Verification.title(outcome(k, true)), s(outcome(k, true).titleRes())) }
    }

    private fun outcome(kind: Verification.Kind, passed: Boolean?, exitTrusted: Boolean = true) = Verification.Outcome(
        command = Verification.Command(kind, "run_command"),
        passed = passed,
        exitCode = 0,
        durationMs = 1_000,
        summary = null,
        failures = emptyList(),
        failureCount = 0,
        errors = emptyList(),
        exitTrusted = exitTrusted,
    )
}
