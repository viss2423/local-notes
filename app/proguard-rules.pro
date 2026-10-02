# Native code (sherpa-onnx, llama bridge) looks these up by name over JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class dev.localnotes.Language { *; }
-keep interface dev.localnotes.TextListener { *; }
-keep class * implements dev.localnotes.TextListener { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.**
-dontwarn org.tukaani.**
-dontwarn com.github.luben.**
-dontwarn org.objectweb.asm.**
