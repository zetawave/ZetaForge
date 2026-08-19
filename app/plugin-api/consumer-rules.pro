# Consumer rules for the ZetaForge SDK.
# Plugin entry points are resolved reflectively by the runtime, so the contract
# itself and everything implementing it must survive shrinking in the Host.
-keep interface com.zetaforge.sdk.** { *; }
-keep class com.zetaforge.sdk.** { *; }
