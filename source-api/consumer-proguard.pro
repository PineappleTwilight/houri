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

-keep,allowoptimization class eu.kanade.tachiyomi.util.JsoupExtensionsKt { public protected *; }

-keep class exh.metadata.** { public protected *; }