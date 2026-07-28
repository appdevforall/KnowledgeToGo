package org.iiab.controller.redesign;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Pure-JVM unit tests for the static helpers in {@link FqrController} (ADFA-4879). No Android deps:
 * isMapsPage is plain string parsing, the validators are regex, and boundsOf uses the real org.json
 * on the test classpath.
 */
public class FqrControllerTest {

    @Test
    public void isMapsPage_matchesOnlyTheMapsPath() {
        assertTrue(FqrController.isMapsPage("http://localhost:8085/maps/"));
        assertTrue(FqrController.isMapsPage("http://localhost:8085/maps"));
        assertTrue(FqrController.isMapsPage("http://localhost:8085/maps/#40.71/-74.06/9/0/1/m/f/hybrid"));
        assertTrue(FqrController.isMapsPage("http://localhost:8085/maps/?x=1"));
        assertFalse(FqrController.isMapsPage("http://localhost:8085/home/"));
        assertFalse(FqrController.isMapsPage("http://localhost:8085/maps/foo"));
        assertFalse(FqrController.isMapsPage("http://localhost:8085/kiwix/"));
        assertFalse(FqrController.isMapsPage(null));
    }

    @Test
    public void validName_lowercaseDigitsUnderscore_1to34() {
        assertTrue(FqrController.validName("testing"));
        assertTrue(FqrController.validName("oaxaca_city"));
        assertTrue(FqrController.validName("a"));
        assertTrue(FqrController.validName("a1_9"));
        assertTrue(FqrController.validName(new String(new char[34]).replace("\0", "a")));   // 34 chars ok
        assertFalse(FqrController.validName(new String(new char[35]).replace("\0", "a")));  // 35 too long
        assertFalse(FqrController.validName("Testing"));     // uppercase
        assertFalse(FqrController.validName("with-dash"));   // hyphen not allowed
        assertFalse(FqrController.validName("with space"));
        assertFalse(FqrController.validName(""));
        assertFalse(FqrController.validName(null));
    }

    @Test
    public void validBox_fourSignedFloatsNoSpaces() {
        assertTrue(FqrController.validBox("-74.12,40.68,-74.01,40.74"));
        assertTrue(FqrController.validBox("1,2,3,4"));
        assertTrue(FqrController.validBox("-103.05,19.72,-98.33,25.84"));
        assertFalse(FqrController.validBox("1,2,3"));          // too few
        assertFalse(FqrController.validBox("a,b,c,d"));        // non-numeric
        assertFalse(FqrController.validBox("1, 2, 3, 4"));     // spaces
        assertFalse(FqrController.validBox(""));
        assertFalse(FqrController.validBox(null));
    }

    @Test
    public void boundsOf_prefersUiBounds_thenRenderBounds_elseNull() throws Exception {
        JSONObject ui = new JSONObject().put("ui_bounds", new JSONArray().put(-74.1).put(40.6).put(-74.0).put(40.7))
                .put("render_bounds", new JSONArray().put(1).put(2).put(3).put(4));
        assertArrayEquals(new double[]{-74.1, 40.6, -74.0, 40.7}, FqrController.boundsOf(ui), 1e-9);

        JSONObject render = new JSONObject().put("render_bounds", new JSONArray().put(1).put(2).put(3).put(4));
        assertArrayEquals(new double[]{1, 2, 3, 4}, FqrController.boundsOf(render), 1e-9);

        assertNull(FqrController.boundsOf(null));
        assertNull(FqrController.boundsOf(new JSONObject()));
        assertNull(FqrController.boundsOf(new JSONObject().put("ui_bounds", new JSONArray().put(1).put(2))));  // < 4
    }
}
