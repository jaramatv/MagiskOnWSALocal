-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# jump3r (codificador MP3) y el sustituto de javax.sound: sin optimizar para evitar sorpresas en release.
-keep class de.sciss.jump3r.** { *; }
-keep class javax.sound.sampled.** { *; }
