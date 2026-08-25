# Consumer ProGuard/R8 rules — applied to any app that depends on :voicenotes.
#
# Intentionally empty. :voicenotes's public surface (models + the VoiceNotes interface) is reached
# through ordinary Kotlin calls, not reflection, and its manifest declares no components beyond
# nothing (see AndroidManifest.xml), so R8 needs no keep rules to shrink a consumer correctly. This
# file exists because defaultConfig references it via consumerProguardFiles("consumer-rules.pro");
# adding rules later is a non-breaking change, removing the file is not.
