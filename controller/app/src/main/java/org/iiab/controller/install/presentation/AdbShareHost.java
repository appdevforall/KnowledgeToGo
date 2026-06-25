/*
 * ============================================================================
 * Name        : AdbShareHost.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Seam between DeployFragment and AdbShareController. The only
 *               cross-feature coupling is the CPU chart, fed by the ADB_CPU_UPDATE
 *               broadcast that arrives over the ADB channel.
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

public interface AdbShareHost {
    float parseCpuUsage(String cpuLine);
    void addCpuEntry(float cpuPercentage);
}
