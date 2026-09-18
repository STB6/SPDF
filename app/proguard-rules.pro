-dontobfuscate

# JNI accesses these bindings by name.
-keep class io.legere.pdfiumandroid.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

-keepattributes SourceFile,LineNumberTable
