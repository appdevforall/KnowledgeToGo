package org.appdevforall.k2go.contentexchange.domain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The no-manifest scan rules: which files are recognizable and the Kiwix filename decomposition. */
public class ImportScanTest {

    @Test
    public void recognizesZimAsKiwixOnly() {
        assertEquals(ImportScan.APP_KIWIX, ImportScan.recognizeApp("wikipedia_en_all_2023-10.zim"));
        assertEquals(ImportScan.APP_KIWIX, ImportScan.recognizeApp("X.ZIM"));
        assertNull(ImportScan.recognizeApp("channel.tar"));
        assertNull(ImportScan.recognizeApp("manifest.json"));
        assertNull(ImportScan.recognizeApp(null));
    }

    @Test
    public void decomposesKiwixFilenameLikeTheCore() {
        assertArrayEquals(new String[]{"wikipedia", "en", "all_maxi", "2023-10"},
                ImportScan.parseZimFilename("wikipedia_en_all_maxi_2023-10.zim"));
        assertArrayEquals(new String[]{"wikipedia", "en", "all", "2023-10-01"},
                ImportScan.parseZimFilename("wikipedia_en_all_2023-10-01.zim"));
    }

    @Test
    public void offConventionNameStillYieldsAStableCreator() {
        assertArrayEquals(new String[]{"welcome", "", "", ""},
                ImportScan.parseZimFilename("welcome.zim"));
    }
}
