package dev.locus.core.designsystem

import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The search pill at the top of a list: a search icon, the query, and Clear once there is one. One
 * line, the Search IME action. [onSearch] runs on that action (by default the keyboard just closes);
 * [requestFocus] puts the cursor in the field when it first appears, for a screen opened to search.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = false,
    onSearch: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) { if (requestFocus) requester.requestFocus() }
    Row(
        modifier.fillMaxWidth()
            .background(Locus.colors.card, Radius.pill)
            .border(Stroke.hairline, Locus.colors.hairline, Radius.pill)
            .padding(start = Spacing.lg, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LocusIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.medium))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = LocusType.body.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch?.invoke() }),
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.md, vertical = Spacing.md).focusRequester(requester),
            decorationBox = { inner ->
                if (query.isEmpty()) Text(placeholder, style = LocusType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                inner()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) { Icon(LocusIcons.Close, contentDescription = "Clear search") }
        }
    }
}

/**
 * A browser's address bar, in the same pill as [SearchField]. At rest it shows the page's [address]
 * (muted [placeholder] when there is none); a tap starts [editing] with an empty field, so whatever is
 * submitted is what the person typed, never the page's own address (the browser counts a typed host as
 * one they named). One line, a URL keyboard without autocorrect, and Go runs [onGo]. Leaving the field
 * ends editing. [enabled] false shows the address read-only (someone else is driving).
 */
@Composable
fun AddressField(
    address: String,
    query: String,
    onQueryChange: (String) -> Unit,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Search or type an address",
    security: SiteSecurity = SiteSecurity.Unknown,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    // Focus once the field is composed; then losing it (tapping the page, Back) ends editing.
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(editing) { if (editing) requester.requestFocus() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = Sizes.touchTarget)
            .background(Locus.colors.card, Radius.pill)
            .border(Stroke.hairline, if (editing && enabled) MaterialTheme.colorScheme.primary else Locus.colors.hairline, Radius.pill)
            .padding(start = Spacing.md, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val editingNow = editing && enabled
        // While typing it's a search or an address yet to open, so the globe; at rest, how the page arrived.
        val glyph = if (editingNow) SiteSecurity.Unknown else security
        Icon(
            glyph.icon,
            contentDescription = null,
            tint = if (glyph == SiteSecurity.NotSecure) Tone.Caution.onContainer() else muted,
            modifier = Modifier.size(IconSize.small),
        )
        if (editingNow) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocusType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo(); focus.clearFocus() }),
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm, vertical = Spacing.sm)
                    .focusRequester(requester)
                    .onFocusChanged {
                        if (focused && !it.isFocused) onEditingChange(false)
                        focused = it.isFocused
                    }
                    .semantics { contentDescription = "Address" },
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(placeholder, style = LocusType.body, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(LocusIcons.Close, contentDescription = "Clear address") }
            }
        } else {
            val color = if (address.isBlank() || !enabled) muted else MaterialTheme.colorScheme.onSurface
            val style = LocusType.body
            BasicText(
                address.ifBlank { placeholder },
                Modifier.weight(1f)
                    .pressable({ onEditingChange(true) }, enabled = enabled)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.md)
                    .semantics {
                        contentDescription = if (address.isBlank()) placeholder else "Address: $address" + security.spoken.let { if (it.isEmpty()) "" else ", $it" }
                    },
                style = style.copy(color = color),
                maxLines = 1,
                // A host reads from its end (the site), so one too long loses its first labels: "…account.booking.com".
                overflow = if (address.isBlank()) TextOverflow.Ellipsis else TextOverflow.StartEllipsis,
                // At a large font a short host would lose its site ("…oking.com"): the text steps down, at
                // most to three quarters of the reading size (still 150% at a 200% font), before it's cut.
                autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * ADDRESS_MIN_SCALE, maxFontSize = style.fontSize),
            )
            trailing?.invoke(this)
        }
    }
}

/** The smallest an [AddressField]'s address steps down to, as a share of [LocusType.body]. */
private const val ADDRESS_MIN_SCALE = 0.75f

/**
 * How the page in an [AddressField] arrived, shown as the pill's leading glyph and read by TalkBack:
 * a padlock over HTTPS, a caution triangle over plain HTTP, a globe for anything else (nothing open
 * yet, a local page, about:blank).
 */
enum class SiteSecurity(val icon: ImageVector, val spoken: String) {
    Secure(LocusIcons.Lock, "secure connection"),
    NotSecure(LocusIcons.Alert, "not secure"),
    Unknown(LocusIcons.Globe, ""),
}

/** How tall a [TextArea] starts: a few lines of instructions, or a whole prompt to edit. */
enum class TextAreaSize(val minHeight: Dp) {
    /** Custom instructions, a note: about four lines. */
    Regular(96.dp),

    /** A prompt or template the user edits as a whole: about seven lines. */
    Tall(160.dp),
}

/**
 * A borderless multi-line editor, for a [LocusCard] that is the field: custom instructions, a helper
 * prompt. [placeholder] shows muted while it's empty. [mono] sets the text in [LocusType.code], for
 * prompts and templates with placeholders. The card around it is the caller's, so a Save or Reset row
 * can sit under the text in the same card.
 */
@Composable
fun TextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    size: TextAreaSize = TextAreaSize.Regular,
    mono: Boolean = false,
    enabled: Boolean = true,
) {
    val style = if (mono) LocusType.code else LocusType.body
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = size.minHeight).padding(Spacing.xs),
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            if (value.isEmpty() && placeholder != null) Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            inner()
        },
    )
}

/**
 * A password or app-password field: masked, with a show/hide button, a password keyboard that doesn't
 * learn or correct what's typed, and the autofill hint for a password. On Android 15+ the screen is
 * marked sensitive while it shows, so screen sharing and recording hide it.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val before = view.contentSensitivity
            view.contentSensitivity = View.CONTENT_SENSITIVITY_SENSITIVE
            onDispose { view.contentSensitivity = before }
        } else {
            onDispose { }
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = keyboardActions,
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(
                    if (shown) LocusIcons.EyeOff else LocusIcons.Eye,
                    contentDescription = if (shown) "Hide password" else "Show password",
                    modifier = Modifier.size(IconSize.medium),
                )
            }
        },
        modifier = modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
    )
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun SearchFieldPreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SearchField("", {}, "Search actions and chats")
                SearchField("gradle", {}, "Search actions and chats")
                AddressField("", "", {}, editing = false, onEditingChange = {}, onGo = {})
                AddressField("booking.com/hotel/pt/casa-do-alfama.en-gb.html", "", {}, editing = false, onEditingChange = {}, onGo = {})
                AddressField("booking.com/hotel/pt/casa-do-alfama.en-gb.html", "", {}, editing = false, onEditingChange = {}, onGo = {}, enabled = false)
                AddressField("booking.com", "github.com/springmusk026", {}, editing = true, onEditingChange = {}, onGo = {})
                AddressField("booking.com", "", {}, editing = false, onEditingChange = {}, onGo = {}, security = SiteSecurity.Secure)
                AddressField("neverssl.com", "", {}, editing = false, onEditingChange = {}, onGo = {}, security = SiteSecurity.NotSecure)
                AddressField("secure.checkout.payments.account.booking.com", "", {}, editing = false, onEditingChange = {}, onGo = {}, security = SiteSecurity.Secure)
                PasswordField("", {}, "App password", supportingText = "Leave blank to keep the saved password")
                PasswordField("abcdefghijklmnop", {}, "App password")
                PasswordField("x", {}, "App password", supportingText = "Mail server turned it down", isError = true)
                LocusCard { TextArea("", {}, placeholder = "e.g. Reply briefly. Keep notes in notes/.") }
                LocusCard { TextArea("Title this chat in five words:\n{{chat}}", {}, size = TextAreaSize.Tall, mono = true) }
            }
        }
    }
}
