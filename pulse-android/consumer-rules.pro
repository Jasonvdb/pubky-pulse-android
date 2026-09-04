# Consumer ProGuard/R8 rules shipped to apps that depend on pulse-android.
# Keep the public API surface so consumers' R8 doesn't strip or rename it.
-keep public class org.pubky.pulse.android.Owl { public *; }
-keep public class org.pubky.pulse.android.OwlConfiguration { *; }
-keep public class org.pubky.pulse.android.** { public *; }
