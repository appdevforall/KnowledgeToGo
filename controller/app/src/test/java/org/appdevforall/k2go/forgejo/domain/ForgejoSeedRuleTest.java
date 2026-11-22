package org.appdevforall.k2go.forgejo.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ForgejoSeedRuleTest {

    @Test
    public void batchWithForgejoOwesSeed() {
        assertTrue(ForgejoSeedRule.owedBy(new String[]{"forgejo"}));
        assertTrue(ForgejoSeedRule.owedBy(new String[]{"kolibri", "forgejo", "matomo"}));
    }

    @Test
    public void batchWithoutForgejoOwesNoSeed() {
        assertFalse(ForgejoSeedRule.owedBy(new String[]{"kolibri", "matomo"}));
        assertFalse(ForgejoSeedRule.owedBy(new String[]{"Forgejo"}));
        assertFalse(ForgejoSeedRule.owedBy(new String[]{"forgejo_extra"}));
    }

    @Test
    public void emptyOrMissingBatchOwesNoSeed() {
        assertFalse(ForgejoSeedRule.owedBy(new String[0]));
        assertFalse(ForgejoSeedRule.owedBy(new String[]{null}));
        assertFalse(ForgejoSeedRule.owedBy(null));
    }
}
