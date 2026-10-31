# LiteRT-LM calls back into these classes from native code (JNI) and serialises them with Gson
# and reflection; the AAR ships no keep rules of its own.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
