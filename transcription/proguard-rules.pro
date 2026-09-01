# ProGuard/R8 rules for the :transcription library's OWN release build.
#
# Intentionally empty — the release build sets isMinifyEnabled = false (a library ships unminified
# so the consumer's R8 pass sees the real bytecode). Referenced by buildTypes.release.proguardFiles.
