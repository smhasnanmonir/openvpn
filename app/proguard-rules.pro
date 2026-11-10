# JNI entry points are resolved by name from C++.
-keep class com.smhasnan.openvpn.nativevpn.OpenVpnNative { *; }
-keep interface com.smhasnan.openvpn.nativevpn.NativeCallbacks { *; }
-keepclassmembers class * implements com.smhasnan.openvpn.nativevpn.NativeCallbacks { public *; }

# Kotlin metadata and coroutines are handled by the default rules.
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
