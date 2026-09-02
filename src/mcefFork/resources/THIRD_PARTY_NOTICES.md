# WinLandCraft Chromium runtime

This compatibility runtime contains MCEF 2.1.6 code from the CinemaMod Group,
licensed under LGPL-2.1-or-later. Its corresponding source and license are at
https://github.com/CinemaMod/mcef/tree/1.21.4.

The `org.cef` Java API and JNI layer come from Keksuccino's JCEF/Rinku build at
java-cef commit `2eb4ca2648bda91d1dfed81e9a37ba92e757aff9`. WinLandCraft's embedded
native runtime rebuilds the ABI-matched CEF commit
`89cd5813e47d84c68e56ced336c2c01b7dc77b8d` with Chromium's proprietary codec
configuration enabled. JCEF and CEF are licensed under their respective BSD
licenses. Each native distribution includes its complete CEF license and
credits notices. Patent-encumbered codecs may require separate rights in the
jurisdictions where the resulting binaries are distributed or used.

The bundled Java API and native runtime are an exact ABI pair and must not be
independently replaced.
