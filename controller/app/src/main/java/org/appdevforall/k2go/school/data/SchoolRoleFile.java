/*
 * ============================================================================
 * Name        : SchoolRoleFile.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: the one reader of the role file of this phone.
 * ============================================================================
 */
package org.appdevforall.k2go.school.data;

import android.content.Context;

import org.appdevforall.k2go.school.domain.SchoolRole;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The role file: {@code getNoBackupFilesDir()/school/role}. The school writes it with the terminal
 * command {@code k2go-role}; the app never writes it. It is in no_backup, so Backup and Clone never
 * copy it to another phone. PRootEngine binds the folder into the box at {@link #GUEST_DIR}, so the
 * box reads the same file. Read on each use, no cache: the file is the one owner of the role.
 */
public final class SchoolRoleFile {

    /** Where PRootEngine binds {@link #dir} in the box. dash-node reads {@code role} in it. */
    public static final String GUEST_DIR = "/run/k2go/school";

    private static final String DIR = "school";
    private static final String FILE = "role";
    private static final int MAX_BYTES = 64;

    private SchoolRoleFile() {}

    /** The folder of the role file. */
    public static File dir(Context ctx) {
        return new File(ctx.getNoBackupFilesDir(), DIR);
    }

    /** The role of this phone. */
    public static SchoolRole role(Context ctx) {
        return read(new File(dir(ctx), FILE));
    }

    /** The role in {@code file}: no file is no role; a file that cannot be read is a student. */
    static SchoolRole read(File file) {
        byte[] buf = new byte[MAX_BYTES];
        try (FileInputStream in = new FileInputStream(file)) {
            int n = in.read(buf);
            return SchoolRole.parse(n > 0 ? new String(buf, 0, n, StandardCharsets.US_ASCII) : "");
        } catch (FileNotFoundException e) {
            return file.exists() ? SchoolRole.STUDENT : SchoolRole.NONE;
        } catch (IOException e) {
            return SchoolRole.STUDENT;
        }
    }
}
