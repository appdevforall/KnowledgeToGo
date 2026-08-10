package org.iiab.controller.system.domain;

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

    @Test
    public void nothingToGoOnFallsBackToTheCommonCase() {
        // A screen asking this only wants something to draw; refusing to answer would be
        // worse than answering with the case three of the four types are in.
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forOperation(null));
        assertEquals(ProgressVisual.DOWNLOAD, ProgressVisual.forContent(null));
    }
}
