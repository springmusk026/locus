# Jakarta Mail (Eclipse Angus) ships no R8 rules. It finds its protocol providers (SMTP/IMAP),
# mailcap data-content handlers and activation registries by class name from META-INF files,
# so those classes must keep their names. R8 picks this file up from the core:agent jar.
-keep class org.eclipse.angus.mail.** { *; }
-keep class org.eclipse.angus.activation.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
# Not on Android: SASL and its NameCallback (Angus notes it is unsupported there), AWT image handlers, GraalVM features.
-dontwarn javax.security.sasl.**
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn java.awt.**
-dontwarn org.graalvm.nativeimage.**
