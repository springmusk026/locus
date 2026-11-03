# LiteRT-LM calls back into these classes from native code (JNI) and serialises them with Gson
# and reflection; the AAR ships no keep rules of its own.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# llama.cpp's bridge (liblocusllama.so) binds Java_dev_locus_core_ondevice_gguf_LlamaNative_* by class
# and method name, throws LlamaNativeException by name (its String constructor), and calls
# Sink.onDelta(int, byte[]) on whatever object it's given.
-keep class dev.locus.core.ondevice.gguf.LlamaNative { native <methods>; }
-keep class dev.locus.core.ondevice.gguf.LlamaNativeException { <init>(java.lang.String); }
-keep interface dev.locus.core.ondevice.gguf.LlamaNative$Sink { void onDelta(int, byte[]); }
-keepclassmembers class * implements dev.locus.core.ondevice.gguf.LlamaNative$Sink { void onDelta(int, byte[]); }
