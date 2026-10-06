# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.emfitsolutions.gopreach.data.model.** { *; }

# Production builds carry no debug/verbose/info logging (and so nothing a log line could leak).
# Warnings and errors are kept for crash diagnosis; none of them contain credentials.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Hilt keys each @HiltViewModel by its class name; with names obfuscated, R8 can map two entries
# to the same string and the app dies at launch ("Multiple entries with same key"). Keep the names.
-keepnames class * extends androidx.lifecycle.ViewModel
