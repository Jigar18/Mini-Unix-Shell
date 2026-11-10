# No reflection-based libraries are used; the default Android rules are enough for the code itself.

# RepoLens is open source and sideloaded: readable crash reports matter more than a smaller APK.
# Keep class/method names (no obfuscation) and line number tables so stack traces in the crash
# report (and in logcat) name real classes and methods. R8 still renumbers lines and replaces the
# file name with a map id; CI uploads mapping.txt (RepoLens-mapping artifact) to retrace exact lines.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
