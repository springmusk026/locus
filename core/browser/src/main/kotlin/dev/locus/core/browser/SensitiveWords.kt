package dev.locus.core.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The words the sensitive-action checks look for, in every language the app ships (English, Spanish,
 * Portuguese, Simplified Chinese, plus Traditional Chinese spellings): one list used both by
 * [SensitiveActions] (button labels, confirm() texts) and by the page script `locus-agent.js` (field
 * names, autocomplete tokens), which gets them through [forPageScript]. docs/design/browser.md,
 * "Safety".
 *
 * Each pattern is a regular expression source that means the same in Kotlin (compiled with
 * IGNORE_CASE, which Kotlin makes Unicode-aware) and in JavaScript (compiled with the `iu` flags).
 * Latin-script words are matched as whole words with [WORD_START]/[WORD_END] rather than `\b`, which
 * is ASCII-only in JavaScript and so would never end a word after "sesión". Chinese has no spaces
 * between words, so its words match anywhere.
 */
object SensitiveWords {
    private const val WORD_START = "(?<![\\p{L}\\p{N}])"
    private const val WORD_END = "(?![\\p{L}\\p{N}])"

    private fun words(vararg w: String) = "$WORD_START(?:${w.joinToString("|")})$WORD_END"
    private fun anywhere(vararg w: String) = "(?:${w.joinToString("|")})"
    private fun anyOf(vararg p: String) = p.joinToString("|")

    /** A button or dialog that acts outward: pays, sends, deletes, publishes, signs up… */
    val OUTWARD: String = anyOf(
        words(
            "pay", "buy", "purchase", "order", "checkout", "check out", "confirm", "delete", "remove", "send", "transfer",
            "withdraw", "donate", "book", "reserve", "subscribe", "unsubscribe", "publish", "post", "submit", "sign up",
            "register", "place", "share", "invite", "approve", "cancel (?:my |your )?(?:order|subscription|account)",
        ),
        // Spanish.
        words(
            "pagar", "paga", "pague", "comprar", "compra", "compre", "finalizar compra", "finalizar pedido", "realizar pedido",
            "hacer pedido", "tramitar pedido", "confirmar", "confirma", "eliminar", "elimina", "borrar", "borra", "quitar",
            "enviar", "env[ií]a", "transferir", "transfiere", "retirar", "donar", "reservar", "reserva",
            "suscr[ií]b\\p{L}*", "darse de baja", "darme de baja", "publicar", "publica", "registrar(?:se|me)?",
            "reg[ií]strate", "crear cuenta", "compartir", "comparte", "invitar", "invita", "aprobar", "aprueba",
            "cancelar (?:mi |el |la |tu )?(?:pedido|suscripci[oó]n|cuenta)",
        ),
        // Portuguese.
        words(
            "fechar pedido", "fazer pedido", "confirme", "excluir", "exclua", "apagar", "apague", "remover", "remova",
            "envie", "transfira", "sacar", "doar", "assinar", "assine", "inscrever(?:-se)?", "inscreva(?:-se)?",
            "cancelar inscri[cç][aã]o", "publique", "postar", "cadastrar(?:-se)?", "cadastre(?:-se)?", "registre(?:-se)?",
            "criar conta", "compartilhar", "compartilhe", "partilhar", "convidar", "convide", "aprovar", "aprove",
            "cancelar (?:o |a |meu |minha )?(?:pedido|assinatura|conta|encomenda)",
        ),
        // Chinese, Simplified and Traditional.
        anywhere(
            "支付", "付款", "购买", "購買", "买单", "買單", "下单", "下單", "结算", "結算", "结账", "結帳", "确认", "確認",
            "删除", "刪除", "移除", "发送", "發送", "转账", "轉帳", "转帐", "提现", "提現", "捐款", "捐赠", "捐贈", "预订",
            "預訂", "预约", "預約", "订阅", "訂閱", "发布", "發佈", "發布", "发表", "發表", "提交", "注册", "註冊", "分享",
            "邀请", "邀請", "批准",
        ),
    )

    /** A button that signs in (asked only on a page with a password field). */
    val LOGIN: String = anyOf(
        words("log ?in", "sign ?in", "sign on", "continue"),
        words(
            "inici(?:ar|a|e) sesi[oó]n", "acceder", "accede", "ingresar", "ingresa", "entrar", "entra", "continuar", "contin[uú]a",
            "entre", "acessar", "acesse", "iniciar sess[aã]o", "fazer login", "logar",
        ),
        anywhere("登录", "登錄", "登陆", "登陸", "登入", "继续", "繼續"),
    )

    /** Autocomplete tokens of password, card and one-time-code fields (tokens are case-insensitive). */
    val SENSITIVE_AUTOCOMPLETE: String = "cc-|one-time-code|current-password|new-password"

    /** Card fields, by name, id or autocomplete. */
    val PAYMENT: String = anyOf(
        "cc-|card.?number|cardnum|cvv|cvc|security.?code|expir",
        "tarjeta|cart[aã]o|validade|vencimiento|caducidad|c[oó]digo.?de.?seguridad|c[oó]digo.?de.?seguran[cç]a",
        anywhere("卡号", "卡號", "银行卡", "銀行卡", "信用卡", "安全码", "安全碼", "有效期"),
    )

    /** One-time-code fields, by name, id or label. "otp" only on its own, so "footprint" isn't one. */
    val OTP: String = anyOf(
        "(?<![a-z])otp|otp(?![a-z])|totp|one.?time.?(?:code|pass)|verification.?code",
        "c[oó]digo.?(?:de.?)?(?:verifica\\p{L}*|seguridad|acceso|acesso|confirma\\p{L}*)",
        anywhere("验证码", "驗證碼", "校验码", "校驗碼", "动态码", "動態碼", "一次性密码", "一次性密碼"),
    )

    /** Search fields and forms, whose submit only looks things up. */
    val SEARCH: String = anyOf(
        "search|query|^q$|^s$|keyword|find|suche|recherche|buscar",
        "busca|b[uú]squeda|pesquis\\p{L}*|procurar",
        anywhere("搜索", "搜尋", "查找", "检索", "檢索"),
    )

    /** Placeholder in locus-agent.js that [forPageScript] replaces with the patterns. */
    const val PLACEHOLDER = "__LOCUS_WORDS__"

    /** The page script's patterns as a JavaScript object literal (JSON), for `new RegExp(src, 'iu')`. */
    val pageScriptJson: String = JsonObject(
        mapOf(
            "sensitiveAutocomplete" to SENSITIVE_AUTOCOMPLETE,
            "payment" to PAYMENT,
            "otp" to OTP,
            "search" to SEARCH,
        ).mapValues { JsonPrimitive(it.value) },
    ).toString()

    /** [source] (locus-agent.js) with [PLACEHOLDER] filled in. Without it the script stops on a ReferenceError. */
    fun forPageScript(source: String): String = source.replace(PLACEHOLDER, pageScriptJson)
}
