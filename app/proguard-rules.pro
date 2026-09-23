# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep USB protocol and payload models for binary serialization / reflection
-keep class com.example.usb.model.** { *; }
-keepclassmembers class com.example.usb.model.** { *; }

# Keep Video and Audio streaming encoder/decoder pipelines
-keep class com.example.stream.** { *; }
-keepclassmembers class com.example.stream.** { *; }

# Keep Android Foreground and Accessibility Services
-keep class com.example.service.** { *; }
-keepclassmembers class com.example.service.** { *; }

# Keep Data models and Settings
-keep class com.example.model.** { *; }
-keepclassmembers class com.example.model.** { *; }

# Moshi & Room annotations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn javax.annotation.**

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
