package dev.locus.core.designsystem

import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/*
 * Dates, times and money as the user's language writes them (docs/design/localization.md, "Formats").
 * A screen says *what* to show with a skeleton ([DateSkeleton]); the locale picks the order, the
 * separators and the words ("Tue 10 Nov, 09:00", "mar, 10 nov, 9:00", "11月10日周二 09:00"). `j` in a
 * skeleton is the locale's hour; with a Context it follows the phone's 12/24-hour setting instead.
 * Machine formats (file names, ISO stamps, logs, what the model reads) don't belong here.
 */

/** What a date or time shows, as a CLDR skeleton for [DateFormat.getBestDateTimePattern]. */
object DateSkeleton {
    /** "09:00", "9:00 AM". */
    const val TIME = "jmm"

    /** "Tue 10 Nov, 09:00". */
    const val WEEKDAY_DAY_MONTH_TIME = "EEEdMMMjmm"

    /** "10 Nov, 09:00". */
    const val DAY_MONTH_TIME = "dMMMjmm"

    /** "10 November 2026, 09:00". */
    const val FULL_DATE_TIME = "yMMMMdjmm"

    /** "10 November 2026". */
    const val FULL_DATE = "yMMMMd"

    /** "10 Nov" ("11月10日" in Chinese). */
    const val DAY_MONTH = "MMMd"

    /** "November", standing alone (a section header). */
    const val MONTH = "MMMM"

    /** "November 2026". */
    const val MONTH_YEAR = "yMMMM"
}

/** The skeleton with its hour forced to 24-hour (`H`) or 12-hour (`h`); unchanged when [is24Hour] is null. */
internal fun hourCycle(skeleton: String, is24Hour: Boolean?): String = when (is24Hour) {
    null -> skeleton
    true -> skeleton.replace('j', 'H').replace('h', 'H')
    false -> skeleton.replace('j', 'h').replace('H', 'h')
}

/**
 * [millis] in [zone], showing what [skeleton] asks for, the way [locale] writes it. [is24Hour]: the
 * phone's 12/24-hour setting ([DateFormat.is24HourFormat]); null keeps the locale's own hour.
 */
fun formatDateTime(millis: Long, skeleton: String, locale: Locale, zone: ZoneId = ZoneId.systemDefault(), is24Hour: Boolean? = null): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, hourCycle(skeleton, is24Hour))
    // ICU's formatter: the best pattern can hold fields (day periods, standalone months) java.time doesn't take.
    val format = android.icu.text.SimpleDateFormat(pattern, locale)
    format.timeZone = android.icu.util.TimeZone.getTimeZone(java.util.TimeZone.getTimeZone(zone).id)
    return format.format(Date(millis))
}

/** [formatDateTime] in the app's language ([context]'s resources) and the phone's 12/24-hour setting. */
fun formatDateTime(context: Context, millis: Long, skeleton: String, zone: ZoneId = ZoneId.systemDefault()): String =
    formatDateTime(millis, skeleton, context.resources.configuration.locales[0], zone, DateFormat.is24HourFormat(context))

/** [formatDateTime] in the composition's language, so it follows a language change. */
@Composable
@ReadOnlyComposable
fun formatDateTime(millis: Long, skeleton: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val context = LocalContext.current
    return formatDateTime(millis, skeleton, LocalResources.current.configuration.locales[0], zone, DateFormat.is24HourFormat(context))
}

/**
 * An amount in US dollars as [locale] writes money: "$1.24", "1,24 US$", "US$ 1,24", "US$1.24", with
 * exactly [fractionDigits] decimals. Prices are in dollars (models.dev), so the currency stays USD.
 */
fun usdAmount(locale: Locale, amount: Double, fractionDigits: Int = 2): String {
    val format = android.icu.text.NumberFormat.getCurrencyInstance(locale)
    format.currency = android.icu.util.Currency.getInstance("USD")
    format.minimumFractionDigits = fractionDigits
    format.maximumFractionDigits = fractionDigits
    return format.format(amount)
}

/** The language [resources] are in: what money and dates follow. */
private val Resources.locale: Locale get() = configuration.locales[0]

/** "$0.42", "$12.50", "<$0.01" under a cent, "$0.00" for nothing; in the app's language (the core's `RunBudget.usd` in English). */
fun usdText(resources: Resources, amount: Double): String = when {
    amount <= 0.0 -> usdAmount(resources.locale, 0.0)
    amount < 0.01 -> resources.getString(R.string.designsystem_money_under, usdAmount(resources.locale, 0.01))
    else -> usdAmount(resources.locale, amount)
}

/**
 * An estimate: "≈$0.04", "<$0.01", "≈$1.20+" when some requests had no price ([partial], a floor); in
 * the app's language (the core's `HelperSpend.costLabel` in English).
 */
fun usdEstimate(resources: Resources, usd: Double, partial: Boolean): String {
    val amount = if (usd > 0 && usd < 0.01) {
        resources.getString(R.string.designsystem_money_under, usdAmount(resources.locale, 0.01))
    } else {
        resources.getString(R.string.designsystem_money_about, usdAmount(resources.locale, usd.coerceAtLeast(0.0)))
    }
    return if (partial) resources.getString(R.string.designsystem_money_or_more, amount) else amount
}
