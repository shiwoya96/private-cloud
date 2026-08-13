# OkHttp, Okio, WorkManager, jcifs-ng and Bouncy Castle publish consumer rules where required.
# Keep these optional jcifs desktop integrations from becoming release-build warnings.
-dontwarn javax.servlet.**
-dontwarn com.sun.security.jgss.**

# jcifs discovers several protocol/authentication implementations dynamically.
-keep class jcifs.** { *; }

# Keep the provider constructor and algorithm implementation names used by JCA lookups.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { public <init>(); }
-keepnames class org.bouncycastle.jcajce.provider.**
