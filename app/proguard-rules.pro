# R8 rules for release builds. Most libraries (Firebase, Compose, kotlinx.serialization,
# CameraX) ship their own consumer rules; these cover the ones that do not.

# Keep line numbers so crash reports in Play Console point at real code (the mapping file
# travels inside the AAB, so Play de-obfuscates them).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Credential Manager finds its Play Services provider by reflection.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# The Connect IQ companion SDK ships without consumer rules and talks to Garmin Connect
# through parcelled classes, so keep it as-is.
-keep class com.garmin.** { *; }
-dontwarn com.garmin.**
