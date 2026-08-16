-keep class eu.kanade.tachiyomi.source.model.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.online.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.** extends eu.kanade.tachiyomi.source.Source { public protected *; }

# Keep ALL constructors for Filter model classes (including synthetic constructors
# generated for Kotlin default parameters). Extensions compiled against this API
# call these constructors directly via bytecode; R8 must not strip them.
-keepclassmembers class eu.kanade.tachiyomi.source.model.Filter** {
    <init>(...);
}

# Keep ALL constructors for SManga, SChapter, and other model classes used by extensions.
-keepclassmembers class eu.kanade.tachiyomi.source.model.SManga { <init>(...); }
-keepclassmembers class eu.kanade.tachiyomi.source.model.SChapter { <init>(...); }
-keepclassmembers class eu.kanade.tachiyomi.source.model.MangasPage { <init>(...); }
-keepclassmembers class eu.kanade.tachiyomi.source.model.SMangaUpdate { <init>(...); }
-keepclassmembers class eu.kanade.tachiyomi.source.model.Page { <init>(...); }
-keepclassmembers class eu.kanade.tachiyomi.source.model.FilterList { <init>(...); }

# KMK -->
# Kotlin generates synthetic $default bridge methods for functions with default
# parameters. Extensions compiled against this API call $default directly when
# using defaults, but R8 cannot see those cross-APK call sites. The old rule
# used { public protected *; } which excluded synthetic methods →
# NoSuchMethodError at runtime. Use { *; } to keep ALL members.
-keep class eu.kanade.tachiyomi.util.JsoupExtensionsKt { *; }
# KMK <--

-keep class exh.metadata.** { public protected *; }

# KMK -->
# Prevent R8 devirtualization on the Source interface. Extension APKs loaded
# via classloader implement Source, but R8 cannot see those implementations
# across dex boundaries. Devirtualization + null-check stripping = NPE.
-keep,allowobfuscation interface eu.kanade.tachiyomi.source.Source { *; }

# Keep HttpSource methods to prevent R8 from making them final when it
# cannot see classloader-loaded overrides.
-keep,allowobfuscation class eu.kanade.tachiyomi.source.online.HttpSource {
    public protected <methods>;
    public protected <fields>;
}

# Keep EnhancedHttpSource / DelegatedHttpSource fields and constructors.
# R8 must not strip fields read after classloader-loaded objects are wrapped.
-keepclassmembers class exh.source.EnhancedHttpSource {
    <init>(...);
    public protected *;
}
-keepclassmembers class exh.source.DelegatedHttpSource {
    <init>(...);
    public protected *;
}
# KMK <--