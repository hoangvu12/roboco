# R8 rules for the release build (debug is not minified; android-app#31).
#
# The Rust core (libroboco_mobile.so) is reached through UniFFI's generated
# Kotlin (uniffi.roboco_core, under uniffi.**) on top of JNA. Both work by
# reflection and from native code: JNA reads Structure fields by name (and
# their @FieldOrder), instantiates Structure and Callback classes, finds
# `callback` methods on Callback interfaces, and its libjnidispatch calls
# back into com.sun.jna by name. Nothing of either may be renamed or removed.
# (Proven on device by upstream #621; #609 disabled R8 instead — the bug this
# file exists to not repeat.)

# JNA
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# UniFFI bindings: every class, field, method and constructor, including the
# callback-interface vtables (com.sun.jna.Callback), Structure records
# (RustBuffer, UniffiRustCallStatus, ForeignFuture*, VTables), the
# `external`/Native.register methods and the handle maps. `uniffi.**` covers
# uniffi.roboco_core.
-keep class uniffi.** { *; }
-keep interface uniffi.** { *; }

# Structure.FieldOrder and other annotations JNA reads at run time; generic
# signatures and inner-class info for Structure.ByValue / ByReference.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# Anything declaring JNI methods keeps them under their names.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
