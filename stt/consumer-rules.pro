# Consumer ProGuard/R8 rules — applied to any app that depends on :stt.
#
# Intentionally empty. The engine's public surface is reached through ordinary Kotlin calls, not
# reflection, and it registers no manifest-declared components, so R8 needs no keep rules to shrink
# a consumer correctly. This file exists because defaultConfig references it via
# consumerProguardFiles("consumer-rules.pro"); adding rules later is a non-breaking change,
# removing the file is not.
