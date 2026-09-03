package org.appdevforall.k2go.system.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link ProgressVisual} — which animation an operation gets, and why it is
 * the execution class that decides rather than the content type. Pure JVM.
 */
public class ProgressVisualTest {

    @Test
    public void whatDownloadsGetsTheDownloadArt() {
        // ZIM, Books and Courses are all REST: the server pulls the bytes, and the cloud
        // sending data to the device is literally what is happening.
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forContent(ContentType.ZIM));
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forContent(ContentType.BOOKS));
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forContent(ContentType.COURSES));
    }

    @Test
    public void whatBuildsDoesNotClaimToBeDownloading() {
        // Maps is a runrole assembling tiles with the box stopped. Showing it a download
        // animation would describe something that is not happening — which is the reason
        // this is resolved from the class instead of one asset being shared by all four.
        assertEquals(ProgressVisual.BUILD, ProgressVisual.forContent(ContentType.MAPS));
    }

    @Test
    public void installingAPlatformIsABuildWhateverItsContentIs() {
        // The Courses app is a proot module while its channels are REST. The visual follows
        // the operation in hand, not the platform's name.
        assertEquals(ProgressVisual.BUILD,
                ProgressVisual.forOperation(Operation.appInstall("kolibri")));
        assertEquals(ProgressVisual.DOWNLOAD,
                ProgressVisual.forOperation(Operation.content("kolibri")));
    }

    @Test
    public void replacingTheSystemIsABuild() {
        assertEquals(ProgressVisual.BUILD, ProgressVisual.forOperation(Operation.system()));
    }

    // ---- the row keys ------------------------------------------------------

    @Test
    public void theIndexRowKeysMapToTheRightVisual() {
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forKey("zim"));
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forKey("books"));
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forKey("kolibri"));
        assertEquals(ProgressVisual.BUILD, ProgressVisual.forKey("maps"));
    }

    @Test
    public void aModuleRowIsABuildWhateverTheModuleIs() {
        // "mod:<name>" is how the index names a proot module row. The prefix is the part
        // that breaks quietly — an off-by-one in the substring would silently classify
        // every module as content and show it a download animation.
        assertEquals(ProgressVisual.BUILD, ProgressVisual.forKey("mod:calibreweb"));
        assertEquals(ProgressVisual.BUILD, ProgressVisual.forKey("mod:kolibri"));
        assertEquals(ProgressVisual.BUILD, ProgressVisual.forKey("mod:"));
    }

    @Test
    public void anUnknownKeyIsTreatedAsContent() {
        // Not a crash and not a build: three of the four types are downloads, so an
        // unrecognised row draws the common case rather than claiming to build something.
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forKey("banana"));
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forKey(""));
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forKey(null));
    }

    @Test
    public void aModuleKeyReachesThePerPlatformHook() {
        // The reason forModuleInstall takes the name at all: the exception point in
        // forOperation matches on platform(), so handing it an empty name would make the
        // hook unreachable from the one place most likely to need it.
        assertEquals("calibreweb",
                Operation.appInstall("calibreweb").platform());
        assertEquals(ProgressVisual.BUILD, ProgressVisual.forModuleInstall("calibreweb"));
        assertEquals(ProgressVisual.BUILD, ProgressVisual.forModuleInstall(null));
    }

    @Test
    public void nothingToGoOnFallsBackToTheCommonCase() {
        // A screen asking this only wants something to draw; refusing to answer would be
        // worse than answering with the case three of the four types are in.
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forOperation(null));
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forContent(null));
    }
}
