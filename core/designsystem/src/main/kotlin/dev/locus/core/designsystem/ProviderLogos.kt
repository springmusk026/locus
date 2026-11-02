package dev.locus.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Provider and model-family marks, converted from LobeHub Icons (MIT). See core/designsystem/NOTICE.md
 * for the source, licence and trademark note. [mono] marks are single-colour in the source and get
 * tinted with the theme's content colour, so black marks (OpenAI, Anthropic, Ollama…) invert in dark mode.
 */
enum class BrandLogo(@DrawableRes val res: Int, val mono: Boolean) {
    OpenAI(R.drawable.logo_openai, mono = true),
    Anthropic(R.drawable.logo_anthropic, mono = true),
    Claude(R.drawable.logo_claude, mono = false),
    Gemini(R.drawable.logo_gemini, mono = false),
    Gemma(R.drawable.logo_gemma, mono = false),
    OpenRouter(R.drawable.logo_openrouter, mono = true),
    Groq(R.drawable.logo_groq, mono = true),
    Ollama(R.drawable.logo_ollama, mono = true),
    LmStudio(R.drawable.logo_lmstudio, mono = true),
    Mistral(R.drawable.logo_mistral, mono = false),
    DeepSeek(R.drawable.logo_deepseek, mono = false),
    Meta(R.drawable.logo_meta, mono = false),
    Qwen(R.drawable.logo_qwen, mono = false),
    XAI(R.drawable.logo_xai, mono = true),
    Grok(R.drawable.logo_grok, mono = true),

    // Services the agent can use (Settings → Integrations). Netlify's mark is from Material Icon Theme (MIT).
    Firecrawl(R.drawable.logo_firecrawl, mono = false),
    Tavily(R.drawable.logo_tavily, mono = false),
    Brave(R.drawable.logo_brave, mono = false),
    Exa(R.drawable.logo_exa, mono = true),
    GitHub(R.drawable.logo_github, mono = true),
    Vercel(R.drawable.logo_vercel, mono = true),
    Netlify(R.drawable.logo_netlify, mono = false),
    Cloudflare(R.drawable.logo_cloudflare, mono = false),

    // Simple Icons (CC0), for services LobeHub Icons doesn't cover.
    Telegram(R.drawable.logo_telegram, mono = false),
    Supabase(R.drawable.logo_supabase, mono = false),
    Render(R.drawable.logo_render, mono = true),
    Ntfy(R.drawable.logo_ntfy, mono = false),

    // MCP server templates (Library → Connectors). Simple Icons (CC0) unless noted.
    GitLab(R.drawable.logo_gitlab, mono = false),
    Sentry(R.drawable.logo_sentry, mono = true),
    Linear(R.drawable.logo_linear, mono = false),
    Notion(R.drawable.logo_notion, mono = true),
    Atlassian(R.drawable.logo_atlassian, mono = false),
    Stripe(R.drawable.logo_stripe, mono = false),
    Neon(R.drawable.logo_neon, mono = false),
    Prisma(R.drawable.logo_prisma, mono = true),
    HuggingFace(R.drawable.logo_huggingface, mono = false),
    Postman(R.drawable.logo_postman, mono = false),
    PostHog(R.drawable.logo_posthog, mono = true),
    Grafana(R.drawable.logo_grafana, mono = false),
    Railway(R.drawable.logo_railway, mono = true),
    MongoDB(R.drawable.logo_mongodb, mono = false),

    // LobeHub Icons (MIT): Google (for Stitch), Microsoft (Learn) and AWS (Knowledge).
    Google(R.drawable.logo_google, mono = false),
    Microsoft(R.drawable.logo_microsoft, mono = false),
    Aws(R.drawable.logo_aws, mono = true),
}

/** An integration's logo by its id ("github", "vercel"…), or null for ones without a brand mark (email). */
fun integrationLogo(id: String): BrandLogo? = when (id.lowercase()) {
    "firecrawl" -> BrandLogo.Firecrawl
    "tavily" -> BrandLogo.Tavily
    "brave" -> BrandLogo.Brave
    "exa" -> BrandLogo.Exa
    "github" -> BrandLogo.GitHub
    "vercel" -> BrandLogo.Vercel
    "netlify" -> BrandLogo.Netlify
    "cloudflare" -> BrandLogo.Cloudflare
    "telegram" -> BrandLogo.Telegram
    "supabase" -> BrandLogo.Supabase
    "render" -> BrandLogo.Render
    "ntfy" -> BrandLogo.Ntfy
    else -> null
}

/** An integration's leading tile: its logo, or a plain icon for ones without a brand mark (email). */
@Composable
fun IntegrationIcon(id: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val logo = integrationLogo(id)
    if (logo != null) LogoTile(logo, modifier, size = size)
    // Round like the logo tiles beside it.
    else Box(
        modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).border(0.5.dp, Locus.colors.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (id.lowercase() == "email") LocusIcons.Mail else LocusIcons.Plug,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.56f),
        )
    }
}

/**
 * An MCP template's logo by its id (see McpTemplates in core/data): the integration marks, plus the
 * developer services only offered as MCP servers. Null for ones drawn with a plain icon.
 */
fun connectorLogo(id: String): BrandLogo? = integrationLogo(id) ?: when (id.lowercase()) {
    "gitlab" -> BrandLogo.GitLab
    "sentry" -> BrandLogo.Sentry
    "linear" -> BrandLogo.Linear
    "notion" -> BrandLogo.Notion
    "atlassian" -> BrandLogo.Atlassian
    "stripe" -> BrandLogo.Stripe
    "neon" -> BrandLogo.Neon
    "prisma" -> BrandLogo.Prisma
    "huggingface" -> BrandLogo.HuggingFace
    "postman" -> BrandLogo.Postman
    "posthog" -> BrandLogo.PostHog
    "grafana" -> BrandLogo.Grafana
    "railway" -> BrandLogo.Railway
    "mongodb" -> BrandLogo.MongoDB
    "stitch" -> BrandLogo.Google
    "microsoft-learn" -> BrandLogo.Microsoft
    "aws-knowledge" -> BrandLogo.Aws
    "cloudflare-docs" -> BrandLogo.Cloudflare
    "brave-local" -> BrandLogo.Brave
    else -> null
}

/** Ids drawn with a plain icon because they have no brand mark here. */
val CONNECTOR_PLAIN_ICONS = setOf("custom", "context7", "deepwiki", "filesystem", "git", "fetch", "memory")

/** The plain icon for a connector without a brand mark. */
private fun connectorFallbackIcon(id: String) = when (id.lowercase()) {
    "filesystem" -> LocusIcons.Folder
    "git" -> LocusIcons.Branch
    "fetch" -> LocusIcons.Globe
    "memory" -> LocusIcons.Note
    "context7", "deepwiki" -> LocusIcons.Library
    else -> LocusIcons.Server
}

/** A connector or template's leading tile: its logo, or a plain icon on the same round tile. */
@Composable
fun ConnectorIcon(id: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val logo = connectorLogo(id)
    if (logo != null) LogoTile(logo, modifier, size = size)
    else Box(
        modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).border(0.5.dp, Locus.colors.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(connectorFallbackIcon(id), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.56f))
    }
}

/**
 * The provider's logo from its name and base URL, or null (callers fall back to the monogram).
 * Hosts that serve an OpenAI-compatible path ("…/openai/…": Gemini, Groq) are checked before OpenAI.
 */
fun providerLogo(name: String, baseUrl: String, onDevice: Boolean): BrandLogo? {
    if (onDevice) return null
    val n = (name + " " + baseUrl).lowercase()
    return when {
        "generativelanguage" in n || "gemini" in n -> BrandLogo.Gemini
        "anthropic" in n || "claude" in n -> BrandLogo.Anthropic
        "openrouter" in n -> BrandLogo.OpenRouter
        "groq" in n -> BrandLogo.Groq
        "deepseek" in n -> BrandLogo.DeepSeek
        "mistral" in n -> BrandLogo.Mistral
        "x.ai" in n || "xai" in n || "grok" in n -> BrandLogo.XAI
        "dashscope" in n || "qwen" in n -> BrandLogo.Qwen
        "ollama" in n || ":11434" in n -> BrandLogo.Ollama
        "lm studio" in n || "lmstudio" in n || ":1234" in n -> BrandLogo.LmStudio
        "openai" in n || "chatgpt" in n -> BrandLogo.OpenAI
        else -> null
    }
}

private val OPENAI_REASONING = Regex("""(^|[/:])o\d""")

/**
 * The model family's logo from a model id ("claude-opus-4-6-thinking", "openai/gpt-4o",
 * "meta-llama/llama-4-scout", "gemma-4-e2b"…), or null when the family isn't known.
 * DeepSeek comes before Qwen/Llama because its distills are named "deepseek-r1-distill-qwen-…".
 */
fun modelLogo(model: String): BrandLogo? {
    val m = model.trim().lowercase()
    if (m.isEmpty()) return null
    return when {
        "claude" in m -> BrandLogo.Claude
        "deepseek" in m -> BrandLogo.DeepSeek
        "gemma" in m -> BrandLogo.Gemma
        "gemini" in m -> BrandLogo.Gemini
        "grok" in m -> BrandLogo.Grok
        listOf("mistral", "mixtral", "codestral", "ministral", "pixtral", "magistral", "devstral").any { it in m } -> BrandLogo.Mistral
        "qwen" in m || "qwq" in m -> BrandLogo.Qwen
        "llama" in m -> BrandLogo.Meta
        "gpt" in m || m.startsWith("openai/") || OPENAI_REASONING.containsMatchIn(m) ||
            m.startsWith("dall-e") || m.startsWith("text-embedding") || m.startsWith("whisper") -> BrandLogo.OpenAI
        else -> null
    }
}

/**
 * The "Continue with ChatGPT" button from OpenAI's Sign in with ChatGPT guidelines
 * (developers.openai.com/siwc/ui-ux-guidelines): the approved label with the ChatGPT mark, black on
 * light themes and white on dark ones (both approved formats).
 */
@Composable
fun ContinueWithChatGptButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    val dark = isSystemInDarkTheme()
    val container = if (dark) Color.White else Color.Black
    val content = if (dark) Color.Black else Color.White
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.6f),
            disabledContentColor = content.copy(alpha = 0.8f),
        ),
    ) {
        Icon(painterResource(BrandLogo.OpenAI.res), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(if (busy) "Waiting for the browser…" else "Continue with ChatGPT", style = MaterialTheme.typography.labelLarge)
    }
}

/** A logo on a neutral circular tile with a hairline edge, so it reads in light and dark. Decorative. */
@Composable
fun LogoTile(logo: BrandLogo, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Box(
        modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .border(0.5.dp, Locus.colors.hairline, CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(logo.res),
            contentDescription = null,
            tint = if (logo.mono) MaterialTheme.colorScheme.onSurface else Color.Unspecified,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}

/**
 * A model's family logo for model lists; unknown families get the first letter on the same neutral
 * tile. Decorative: the model id is shown next to it.
 */
@Composable
fun ModelLogo(model: String, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val logo = modelLogo(model)
    if (logo != null) {
        LogoTile(logo, modifier, size)
        return
    }
    Box(
        modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .border(0.5.dp, Locus.colors.hairline, CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val letter = model.substringAfterLast('/').trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        Text(
            letter,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.45f).sp,
        )
    }
}
