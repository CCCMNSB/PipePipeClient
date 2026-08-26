# Add proguard rules here if the merged release build strips anything needed.
# The translator module is used by a minified release build, so keep its public API.
-keep class com.pipepipe.translator.** { *; }
