# The demo's release build sets isMinifyEnabled = false, so nothing in this file is applied today.
# It exists because `proguardFiles(...)` in build.gradle.kts names it, and because the moment
# someone turns minification on this is where the rules have to land rather than in a new file
# nobody remembers to create.
#
# If minification is ever enabled here, note that :stt ships consumer-rules.pro and those rules are
# merged in automatically — do not copy them.
