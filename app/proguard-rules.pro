# JNI is the only fixed Java/native seam. Keep its descriptors stable.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class com.motexa.vehiclecast.NativeCast { *; }
-keep class com.motexa.openninebot.TrialNative { *; }
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
