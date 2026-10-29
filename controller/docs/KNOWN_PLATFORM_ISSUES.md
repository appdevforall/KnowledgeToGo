# Known platform issues

This file lists device and kernel builds on which K2Go cannot run because of a defect
outside the app. Each entry identifies the build exactly (ROM version, kernel repository,
branch and commit), not only the kernel version. The same kernel version can be good or bad
depending on the patches that the ROM applied.

An entry is "confirmed" only when we reproduced the failure on a device and showed that the
named fix removes it. Other reports stay "reported, not verified".

---

## 1. Missing cgroup v2 freezer fix in ptrace_stop() (LineageOS sm8150)

**Status:** Confirmed on a OnePlus 7T (2026-10-28).

### Affected builds

| Item | Value |
|---|---|
| ROM | LineageOS 23.2 (Android 16). LineageOS 24.0 (Android 17) has the same code, not tested on a device. |
| Kernel repository | [LineageOS/android_kernel_oneplus_sm8150](https://github.com/LineageOS/android_kernel_oneplus_sm8150) |
| Affected branches | `lineage-23.2`, `lineage-24.0` |
| Not affected | `lineage-23.0`, `lineage-23.1` (no cgroup v2 freezer backport) |
| Commit that adds the defect | "BACKPORT: cgroup: cgroup v2 freezer" (2026-04-24) |
| Missing upstream fix | [05b289263772](https://git.kernel.org/linus/05b289263772) "signal: unconditionally leave the frozen state in ptrace_stop()" |
| Verified build | `4.14.357-openela-perf-g0521dc291cf1`, commit `0521dc291cf10bd754fb5ff385e74875c406d577`, LineageOS 23.2-20261009 (hotdogb) |
| Verified device | OnePlus 7T (HD1901) |

Other Qualcomm trees (sm6125, sm6150, sm6250) are reported in
[termux/proot#417](https://github.com/termux/proot/issues/417). Status: reported, not verified.

### Symptom

- K2Go stays on "Starting your library..." and does not finish.
- Logcat shows this sequence again approximately every 60 s:

  ```
  [Server] /etc/profile: fork: Function not implemented
  [Server] engine exit 137
  ```

- Minimal reproduction with any proot binary, outside K2Go:

  ```
  proot -r / sh -c 'echo hi | cat'
  ```

  On an affected kernel the shell prints `can't fork - try again` and hangs.
  On a good kernel it prints `hi` and exits with code 0.

### Cause

The backport makes `ptrace_stop()` call `cgroup_enter_frozen()` but not
`cgroup_leave_frozen()`. After a traced task stops one time, each `clone()` returns
`-ERESTARTNOINTR` and restarts forever. proot traces every process with ptrace, so no process
in the K2Go environment can fork.

The fix is one line in `kernel/signal.c`, `ptrace_stop()`:

```c
	freezable_schedule();
	cgroup_leave_frozen(true);
```

### Workaround

- **Users:** none. The app cannot correct a kernel defect. Use a ROM build that is not
  affected, or wait for a fixed LineageOS build.
- **Test devices:** build the kernel from the device commit with the line above and flash only
  the kernel into the matching official `boot.img`. With this kernel, K2Go runs normally on the
  OnePlus 7T. A ROM update replaces the kernel, so you must apply the patch again after each
  update.
