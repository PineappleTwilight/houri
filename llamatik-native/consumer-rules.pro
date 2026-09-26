# The vendored llamatik wrapper is reached from JNI by class name and native method
# name, so shrinking/renaming must not touch it.
-keepclasseswithmembernames,includedescriptorclasses class com.llamatik.library.platform.** {
    native <methods>;
}

# GenStream instances are constructed in Kotlin and passed to native code, which calls
# back into them by name via JNI.
-keep public interface com.llamatik.library.platform.GenStream {
    public void onDelta(java.lang.String);
    public void onComplete();
    public void onError(java.lang.String);
}
