-keep class dev.callstream.probe.shell.ProbeService { *; }
-keep class dev.callstream.probe.IProbeService { *; }
-keep class dev.callstream.probe.IProbeService$Stub { *; }
-keepclassmembers class dev.callstream.probe.shell.ProbeService {
    public <init>();
    public <init>(android.content.Context);
}

# Our own code is loaded by name (app_process helper, reflection on hidden APIs): keep all of it when shrinking.
-keep class dev.callstream.probe.** { *; }
-dontwarn **
