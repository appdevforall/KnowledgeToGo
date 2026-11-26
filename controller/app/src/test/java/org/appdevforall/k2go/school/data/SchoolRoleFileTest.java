package org.appdevforall.k2go.school.data;

import static org.junit.Assert.assertEquals;

import org.appdevforall.k2go.school.domain.SchoolRole;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The role file on disk. */
public class SchoolRoleFileTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File write(String text) throws IOException {
        File f = new File(tmp.getRoot(), "role");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
        }
        return f;
    }

    @Test public void noFileIsNoRole() {
        assertEquals(SchoolRole.NONE, SchoolRoleFile.read(new File(tmp.getRoot(), "role")));
    }

    @Test public void teacherFile() throws IOException {
        assertEquals(SchoolRole.TEACHER, SchoolRoleFile.read(write("teacher\n")));
    }

    @Test public void emptyFileIsStudent() throws IOException {
        assertEquals(SchoolRole.STUDENT, SchoolRoleFile.read(write("")));
    }

    @Test public void aFolderInPlaceOfTheFileIsStudent() throws IOException {
        assertEquals(SchoolRole.STUDENT, SchoolRoleFile.read(tmp.newFolder("role")));
    }
}
