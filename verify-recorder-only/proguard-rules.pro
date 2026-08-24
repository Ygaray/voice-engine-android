# Intentionally empty. The whole point of this harness is unforced R8 shrinking with ZERO keep
# rules — a keep rule here would make check-r8-mapping.sh's "recognition-only classes are absent"
# assertion meaningless, because it would then be testing what we told R8 to retain rather than
# what plain code reachability retains on its own. Do not add rules to this file.
