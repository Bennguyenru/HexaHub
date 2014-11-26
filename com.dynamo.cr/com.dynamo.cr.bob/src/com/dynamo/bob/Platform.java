package com.dynamo.bob;

public enum Platform {
    X86Darwin("x86", "darwin", "", "", "lib", ".dylib"),
    X86_64Darwin("x86_64", "darwin", "", "", "lib", ".dylib"),
    X86Win32("x86", "win32", ".exe", "", "", ".dll"),
    X86Linux("x86", "linux", "", "", "lib", ".so"),
    Armv7Darwin("armv7", "darwin", "", "", "lib", ".so"),
    Armv7Android("armv7", "android", ".so", "lib", "lib", ".so"),
    JsWeb("js", "web", ".js", "", "lib", "");

    String arch, os;
    String exeSuffix;
    String exePrefix;
    String libSuffix;
    String libPrefix;
    Platform(String arch, String os, String exeSuffix, String exePrefix, String libPrefix, String libSuffix) {
        this.arch = arch;
        this.os = os;
        this.exeSuffix = exeSuffix;
        this.exePrefix = exePrefix;
        this.libSuffix = libSuffix;
        this.libPrefix = libPrefix;
    }

    public String getExeSuffix() {
        return exeSuffix;
    }

    public String getExePrefix() {
        return exePrefix;
    }

    public String getLibPrefix() {
        return libPrefix;
    }

    public String getLibSuffix() {
        return libSuffix;
    }

    public String getPair() {
        return String.format("%s-%s", this.arch, this.os);
    }

    static Platform get(String pair) {
        Platform[] platforms = Platform.values();
        for (Platform p : platforms) {
            if (p.getPair().equals(pair)) {
                return p;
            }
        }
        return null;
    }

    public static Platform getJavaPlatform() {
        String os_name = System.getProperty("os.name").toLowerCase();

        if (os_name.indexOf("win") != -1) {
            return Platform.X86Win32;
        } else if (os_name.indexOf("mac") != -1) {
            return Platform.X86_64Darwin;
        } else if (os_name.indexOf("linux") != -1) {
            return Platform.X86Linux;
        } else {
            throw new RuntimeException(String.format("Could not identify OS: '%s'", os_name));
        }
    }

    public static Platform getHostPlatform() {
        String os_name = System.getProperty("os.name").toLowerCase();

        if (os_name.indexOf("win") != -1) {
            return Platform.X86Win32;
        } else if (os_name.indexOf("mac") != -1) {
            return Platform.X86Darwin;
        } else if (os_name.indexOf("linux") != -1) {
            return Platform.X86Linux;
        } else {
            throw new RuntimeException(String.format("Could not identify OS: '%s'", os_name));
        }
    }

}
