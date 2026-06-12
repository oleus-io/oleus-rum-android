# Keep crash-handler entry points readable in stack traces.
-keep class io.oleus.mobile.** { *; }
# Preserve line numbers for server-side retrace.
-keepattributes SourceFile,LineNumberTable
