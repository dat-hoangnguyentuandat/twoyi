package com.hzy.libp7zip;

/** Java bridge for the libp7zip.so already bundled in the APK. */
public final class P7ZipApi {
    static {
        System.loadLibrary("p7zip");
    }

    private P7ZipApi() {}

    public static native int executeCommand(String command);
}
