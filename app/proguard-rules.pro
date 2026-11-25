# Keep JNI class and native method names for liblocalbeat_tunnel.so
-keep class com.jimboyo.localbeat.server.NativeTunnel {
    native <methods>;
}
