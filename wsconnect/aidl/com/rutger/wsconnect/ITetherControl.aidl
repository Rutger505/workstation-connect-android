package com.rutger.wsconnect;

// Runs inside the Shizuku UserService (shell uid). Results are human-readable; "OK" prefix = success.
interface ITetherControl {
    // type: 0 = Wi-Fi hotspot, 2 = Bluetooth tethering (TetheringManager.TETHERING_*)
    String start(int type) = 1;
    String stop(int type) = 2;
    String whoami() = 3;
    // Runs a command as the shell uid (pm grant, appops). Returns exit code + output.
    String shell(String command) = 4;
    void destroy() = 16777114; // Shizuku's reserved destroy transaction
}
