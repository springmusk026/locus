# libtermux.so binds com.termux.terminal.JNI's native methods by name (Java_com_termux_terminal_JNI_*),
# so R8 must keep the class and those methods' names. app/r8-kept-names.txt checks it on every release build.
-keep class com.termux.terminal.JNI { native <methods>; }
