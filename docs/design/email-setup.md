# Email setup

Settings → Integrations → Email (and the same sheet in onboarding) sets up the agent's own mailbox.
It used to be one text field holding `smtps://USER:APP_PASSWORD@HOST:465 imaps://HOST:993 Name`. It is
now a form. The email tools (`Email.kt`) didn't change: the form saves a value they already read.

## Flow

1. **Email address, app password, display name.** From the address's domain the form fills both
   servers from a bundled preset and says so ("Gmail settings"), with a link to that provider's
   app-password page. Outlook.com/Hotmail/Live/MSN and Proton Mail get a note instead: Microsoft only
   allows OAuth for Outlook.com, and Proton Mail Bridge runs on a computer, not on Android.
2. **Server settings**, collapsed to a two-line summary when a preset filled them, opened by itself for
   an unknown domain or a field with a problem: IMAP and SMTP host, port and security (SSL/TLS or
   STARTTLS), and one username (default: the address). A security change moves the port to its default
   (993/143, 465/587) unless the user typed a port. "None" is only offered for a server on the phone
   itself, which is all the tools accept. One username, because the tools sign in to both servers with
   one account.
3. **Test connection** signs in to IMAP and to SMTP at once and disconnects. Nothing is sent or read.
   Each server gets its own row: Works, or Failed with what to change (wrong password → app password;
   host not found; port refused; TLS failed or a timeout → the other security mode and its port).
4. **Save** checks the value with `EmailConfig.parse`, the tools' own parser, before it goes to the vault.

This follows the shape of the account setup in Thunderbird, Thunderbird for Android (K-9) and FairEmail:
address and password first, provider settings found for the user, manual server settings one step away,
a check before relying on it. The bundled presets play the part of FairEmail's bundled provider list.

## Presets

Each value is from the provider's own documentation (checked 2026-11), cited in `EmailPresets`:

| Provider | Domains | IMAP | SMTP | Source |
|---|---|---|---|---|
| Gmail | gmail.com, googlemail.com | imap.gmail.com 993 SSL/TLS | smtp.gmail.com 465 SSL/TLS | developers.google.com/workspace/gmail/imap/imap-smtp |
| Fastmail | fastmail.com, fastmail.fm | imap.fastmail.com 993 SSL/TLS | smtp.fastmail.com 465 SSL/TLS | fastmail.help article 1500000278342 |
| iCloud Mail | icloud.com, me.com, mac.com | imap.mail.me.com 993 SSL/TLS | smtp.mail.me.com 587 STARTTLS | support.apple.com/102525 |
| Yahoo Mail | yahoo.com, ymail.com, rocketmail.com, regional yahoo.* (not yahoo.co.jp) | imap.mail.yahoo.com 993 SSL/TLS | smtp.mail.yahoo.com 465 SSL/TLS | help.yahoo.com SLN4075 |
| Zoho Mail | zoho.com, zohomail.com | imap.zoho.com 993 SSL/TLS | smtp.zoho.com 465 SSL/TLS | zoho.com/mail/help/imap-access.html |
| GMX | gmx.com, gmx.us | imap.gmx.com 993 SSL/TLS | mail.gmx.com 587 STARTTLS | support.gmx.com/pop-imap/setup-emailprogram-fails.html |
| GMX | gmx.net, gmx.de, gmx.at, gmx.ch | imap.gmx.net 993 SSL/TLS | mail.gmx.net 587 STARTTLS | hilfe.gmx.net/pop-imap/imap/imap-serverdaten.html |
| mailbox.org | mailbox.org | imap.mailbox.org 993 SSL/TLS | smtp.mailbox.org 465 SSL/TLS | kb.mailbox.org/en/business/e-mail/e-mail-configuration |

Zoho organisation domains (imappro/smtppro) and Zoho's other data centres aren't guessed: those users
type their settings or look them up.

## Look up settings: only on a tap

Locus is local-first: it makes network calls only for things the user sets up. The presets are bundled,
so typing an address sends nothing anywhere. For a domain without a preset, Server settings has a
**Look up settings** button. Only that tap asks Thunderbird's public settings list (ISPDB,
`https://autoconfig.thunderbird.net/v1.1/<domain>`), and only with the domain: never the address or the
password. The provider's own autoconfig host is not asked, because that request carries the whole
address. The answer is untrusted: a document with a DOCTYPE is refused, only TLS servers are taken
(SSL/TLS preferred over STARTTLS), and the user still sees and can change every value before testing.

## Stored value and compatibility

The vault value (`connector:integration-email`) is the JSON form `EmailConfig.parse` has always read:

```json
{"smtp":"smtps://smtp.gmail.com:465","imap":"imaps://imap.gmail.com:993","username":"agent@gmail.com",
 "password":"…","name":"Locus Agent","address":"agent@gmail.com"}
```

JSON keeps any password character as typed, so there is no URL encoding to get wrong. A value saved as
one line still works for the tools, and opening the sheet parses it with the same parser into the fields;
saving rewrites it as JSON. A value that doesn't parse shows a banner and an empty form, never the value.
`EmailFormTest` round-trips parse → fields → serialize → parse, including `@ : / % +`, spaces and
Unicode in passwords, URL-encoded one-line values, both security modes, send-only and loopback setups.

## The password

The saved password never comes back to the UI. Its field starts empty and says "Leave blank to keep the
saved password"; Test and Save then use the saved one inside `EmailSetup`. The field (`PasswordField` in
the design system) is masked with a show/hide button, uses a password keyboard without learning or
autocorrect, carries the password autofill hint, and on Android 15+ marks the screen sensitive so screen
sharing and recording hide it. Test messages are scrubbed of the password, and nothing here logs.
