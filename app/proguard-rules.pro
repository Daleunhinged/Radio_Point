# Proguard rules for RadioPoint & MeetPredictor
-keep class com.radiopoint.app.gis.LivePullout { *; }
-keep class com.radiopoint.app.gis.RoadDefinition { *; }
-keep class com.radiopoint.app.data.TelemetryPacket { *; }
-keep class org.locationtech.jts.** { *; }
-keep class org.osmdroid.** { *; }
