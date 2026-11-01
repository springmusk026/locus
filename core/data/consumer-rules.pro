# sshlib (ConnectBot) ships no R8 rules. Its own JCA provider for Ed25519 registers its classes by
# name, built from its package: Ed25519Provider.class.getPackage().getName() + ".Ed25519KeyFactory"
# (and ".Ed25519KeyPairGenerator"). JCA later creates them by that name, with their public no-arg
# constructors. Without this, R8 repackages the provider into the default package (getPackage() is
# then null: "Attempt to invoke virtual method 'java.lang.String java.lang.Package.getName()' on a
# null object reference", which broke every SSH connection in release builds) and removes the
# factory classes, which nothing references directly. The whole package is ten small classes.
# app/r8-kept-names.txt checks these names survive every release build.
-keep class org.connectbot.sshlib.crypto.ed25519.** { *; }
