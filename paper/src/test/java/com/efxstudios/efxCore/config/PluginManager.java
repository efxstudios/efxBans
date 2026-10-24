package com.efxstudios.efxCore.config;
/** Test fixture matching the inspected optional efxCore public API. */
public final class PluginManager {
    public static boolean enabled=true;
    public static boolean isModuleEnabled(String module) { return enabled; }
}
