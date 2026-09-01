/*
 * ============================================================================
 * Name        : ElfClass.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Domain rule: read the ELF class (32- vs 64-bit) from a binary
 *               header. Used to enforce ABI separation on imported/restored
 *               rootfs archives (ARM64<->ARM64, 32<->32).
 * ============================================================================
 */
package org.appdevforall.k2go.deploy.domain;

/**
 * Pure helper that classifies an ELF binary as 32- or 64-bit from its first
 * bytes (the ELF identification header). No I/O — the caller supplies the bytes.
 *
 * <p>ELF layout: magic {@code 0x7F 'E' 'L' 'F'} then byte 4 ({@code EI_CLASS})
 * is {@code 1} for 32-bit ({@code ELFCLASS32}) or {@code 2} for 64-bit
 * ({@code ELFCLASS64}).
 */
public final class ElfClass {

    public static final int UNKNOWN = 0;
    public static final int BITS_32 = 32;
    public static final int BITS_64 = 64;

    private ElfClass() {
        // Static utility; not instantiable.
    }

    /** Classify from the leading bytes of a file; {@link #UNKNOWN} if not an ELF. */
    public static int of(byte[] header) {
        if (header == null || header.length < 5) {
            return UNKNOWN;
        }
        if ((header[0] & 0xFF) != 0x7F || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
            return UNKNOWN;
        }
        int eiClass = header[4] & 0xFF;
        if (eiClass == 1) {
            return BITS_32;
        }
        if (eiClass == 2) {
            return BITS_64;
        }
        return UNKNOWN;
    }
}
