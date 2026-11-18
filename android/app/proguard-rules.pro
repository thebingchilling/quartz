
-keep class com.follow.clash.models.** { *; }

-keep class com.follow.clash.service.models.** { *; }

# Gson reads the auto-tunnel settings Flutter writes, and writes the decisions
# it sends back, by field name.
-keep class com.follow.clash.autotunnel.** { *; }
