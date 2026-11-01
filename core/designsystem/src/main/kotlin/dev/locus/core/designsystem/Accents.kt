package dev.locus.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Category hues that give the UI some life beyond the base palette. Each kind of tool and section
 * keeps one colour everywhere (chat, activity log, home), so colour carries meaning.
 */
enum class Accent(private val light: Long, private val dark: Long) {
    Files(0xFF3B6FD8, 0xFF8FB4FF),
    Web(0xFF0E8C86, 0xFF6FD6CF),
    Linux(0xFFB36B00, 0xFFF2B65A),
    Memory(0xFF7A4FD0, 0xFFC2A8FF),
    Device(0xFFD0533F, 0xFFFF9C8A),
    Connector(0xFFC2427A, 0xFFFF9CC6),
    Agent(0xFF2E6B5E, 0xFF8CCBBB),
    ;

    @Composable
    fun color(): Color = Color(if (isSystemInDarkTheme()) dark else light)

    @Composable
    fun container(): Color = color().copy(alpha = if (isSystemInDarkTheme()) 0.18f else 0.12f)
}

/** Which accent and icon a tool gets, by its name. */
fun accentFor(toolName: String): Pair<Accent, ImageVector> = when {
    toolName.startsWith("browser_") || toolName in setOf("fetch_url", "web_search", "web_read", "wikipedia", "weather") -> Accent.Web to LocusIcons.Globe
    toolName == "run_command" || toolName.startsWith("linux_") || toolName == "git_inspect" || toolName.startsWith("ssh_") || toolName.startsWith("sftp_") -> Accent.Linux to LocusIcons.Terminal
    toolName.startsWith("memory_") || toolName.startsWith("skill_") || toolName.startsWith("chat_history_") -> Accent.Memory to LocusIcons.Library
    toolName.startsWith("device_") || toolName == "notify_me" -> Accent.Device to LocusIcons.Phone
    toolName.startsWith("agent_") || toolName == "todo_write" || toolName == "plan_propose" || toolName == "ask_user" || toolName == "task_budget" -> Accent.Agent to LocusIcons.Sparkle
    toolName.startsWith("email_") -> Accent.Connector to LocusIcons.Plug
    toolName.startsWith("connector_") || toolName.startsWith("mcp_") || toolName.substringBefore('_') in setOf("github", "vercel", "netlify", "cloudflare", "supabase", "render") -> Accent.Connector to LocusIcons.Plug
    else -> Accent.Files to LocusIcons.File
}

@Composable
fun AccentTile(accent: Accent, icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Box(
        modifier.size(size).background(accent.container(), RoundedCornerShape(size / 3.5f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = accent.color(), modifier = Modifier.size(size * 0.6f)) }
}

/**
 * A provider's identity: its logo when known ([BrandLogo], from LobeHub Icons, see NOTICE.md) and a
 * coloured monogram as the fallback for providers without one.
 */
data class ProviderIdentity(val monogram: String, val colors: List<Color>, val logo: BrandLogo? = null)

fun providerIdentity(name: String, baseUrl: String, onDevice: Boolean): ProviderIdentity {
    val n = (name + " " + baseUrl).lowercase()
    fun solid(c: Long) = listOf(Color(c), Color(c))
    val logo = providerLogo(name, baseUrl, onDevice)
    return when {
        onDevice -> ProviderIdentity("◉", listOf(Color(0xFF2E6B5E), Color(0xFF6FD6CF)))
        // Hosts first: Gemini's and Groq's OpenAI-compatible endpoints have "openai" in their path.
        "generativelanguage" in n || "gemini" in n -> ProviderIdentity("G", listOf(Color(0xFF4285F4), Color(0xFF9B72CB)), logo)
        "anthropic" in n || "claude" in n -> ProviderIdentity("A", solid(0xFFD97757), logo)
        "openrouter" in n -> ProviderIdentity("R", listOf(Color(0xFF6467F2), Color(0xFF9C6BF2)), logo)
        "groq" in n -> ProviderIdentity("Q", solid(0xFFF55036), logo)
        "openai" in n -> ProviderIdentity("O", solid(0xFF10A37F), logo)
        "ollama" in n || ":11434" in n -> ProviderIdentity("O", solid(0xFF3A3A3A), logo)
        "lm studio" in n || "llama" in n || ":1234" in n -> ProviderIdentity("L", solid(0xFF4B5BE6), logo)
        else -> ProviderIdentity(name.trim().firstOrNull()?.uppercase() ?: "?", solid(0xFF5B6360), logo)
    }
}

/**
 * The provider's mark. With [model] (the model id in use) and a known family, the model family's logo
 * is shown instead: an "OpenAI"-compatible provider serving claude-opus-… shows Claude. Otherwise the
 * provider's logo, otherwise its coloured monogram. Decorative: the name is always shown next to it.
 */
@Composable
fun ProviderBadge(
    name: String,
    baseUrl: String,
    onDevice: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    model: String? = null,
) {
    val id = providerIdentity(name, baseUrl, onDevice)
    val logo = model?.let(::modelLogo) ?: id.logo
    if (logo != null) {
        LogoTile(logo, modifier, size)
        return
    }
    Box(
        // Decorative: the provider's name is always shown next to it, so TalkBack shouldn't read "O".
        modifier.size(size).background(Brush.linearGradient(id.colors), CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(id.monogram, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp)
    }
}

/** Three softly pulsing dots for "the agent is thinking". */
@Composable
fun ThinkingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 180), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(alpha).background(color, CircleShape))
        }
    }
}
