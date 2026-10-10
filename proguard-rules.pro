# BYD SDK Stubs - keep all classes
-keep class android.hardware.bydauto.** { *; }
-keep class android.hardware.IBYDAutoListener { *; }
-keep class android.hardware.IBYDAutoEvent { *; }
-keep class android.hardware.IBYDCameraService { *; }
-keep class android.hardware.IBYDCameraUser { *; }
-keep class android.hardware.BYDAutoManager { *; }

# Reflection for device instance retrieval
-keepnames class android.hardware.bydauto.** { *; }

# App components
-keep class com.cseini.bydsniffer.MainActivity { *; }
-keep class com.cseini.bydsniffer.SnifferService { *; }

# Keep app packages
-keepnames class com.cseini.bydsniffer.** { }

# Jetpack & Material
-keep class androidx.** { *; }
-keep class com.google.android.material.** { *; }

# Serialization
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
