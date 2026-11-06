# FFmpeg extension is loaded by reflection from DefaultRenderersFactory
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
# Video effects pipeline is created by reflection from ExoPlayer
-keep class androidx.media3.effect.** { *; }
