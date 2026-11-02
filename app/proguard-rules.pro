-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Constructed by the editor JNI bridge for an atomic serialized-size rejection.
-keep class dev.soupslurpr.beautyxt.document.DocumentSizeLimitException {
    public <init>(java.lang.String);
}

# R8 9.5.28 nondeterministically strengthens these helpers' Continuation type.
# Disable their optimization until a stable compiler fix is verified.
# See docs/release.md; shrinking and obfuscation remain enabled.
-keepclassmembers,allowshrinking,allowobfuscation class kotlinx.coroutines.flow.FlowKt__LimitKt {
    private static java.lang.Object emitAbort$FlowKt__LimitKt(
        kotlinx.coroutines.flow.FlowCollector,
        java.lang.Object,
        java.lang.Object,
        kotlin.coroutines.Continuation);
    public static java.lang.Object access$emitAbort$FlowKt__LimitKt(
        kotlinx.coroutines.flow.FlowCollector,
        java.lang.Object,
        java.lang.Object,
        kotlin.coroutines.Continuation);
}
