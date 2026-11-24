package com.rutger.workstationconnect;

// Runs inside the Shizuku UserService (shell uid). Results are human-readable; "OK" prefix = success.
interface ITetherControl {
    // Wi-Fi hotspot (TetheringManager.TETHERING_WIFI)
    String startHotspot() = 1;
    String stopHotspot() = 2;
    String whoami() = 3;
    // Runs a command as the shell uid (pm grant, appops). Returns exit code + output.
    String shell(String command) = 4;
    // AppLog lines buffered in this process since the last call ("\n"-joined), forwarded into the app's log.
    String takeLog() = 5;
    void destroy() = 16777114; // Shizuku's reserved destroy transaction
}
