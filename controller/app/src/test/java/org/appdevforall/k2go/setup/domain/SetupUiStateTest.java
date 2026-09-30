package org.appdevforall.k2go.setup.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** K2GO-434: the setup screen's derived view-state rule (slice 3). Pure, so a plain JVM test. */
public class SetupUiStateTest {

    /** A calm, running REST run: services up, no module, no completion yet. */
    private static SetupUiState.Inputs running() {
        return new SetupUiState.Inputs().servicesReady(true);
    }

    @Test
    public void servicesUpNonModuleIsNeutralAdding() {
        SetupUiState ui = running().build();
        assertEquals(SetupUiState.StatusTone.NEUTRAL, ui.tone);
        assertEquals(SetupUiState.StatusMessage.ADDING, ui.message);
        assertFalse(ui.animate);
    }

    @Test
    public void startingBeforeServicesReadyIsWaiting() {
        SetupUiState ui = new SetupUiState.Inputs().servicesReady(false).build();
        assertEquals(SetupUiState.StatusTone.WAITING, ui.tone);
        assertEquals(SetupUiState.StatusMessage.STARTING, ui.message);
        assertTrue(ui.animate);
    }

    @Test
    public void slowByPollsBeforeReadyShowsSlow() {
        SetupUiState ui = new SetupUiState.Inputs().servicesReady(false).slowByPolls(true).build();
        assertEquals(SetupUiState.StatusMessage.SLOW, ui.message);
    }

    @Test
    public void batchServerSlowIsWaitingSlow() {
        SetupUiState ui = new SetupUiState.Inputs()
                .moduleFlow(true).batchServerSlow(true).build();
        assertEquals(SetupUiState.StatusTone.WAITING, ui.tone);
        assertEquals(SetupUiState.StatusMessage.SLOW, ui.message);
        assertFalse(ui.animate);   // slow is terminal, not an animated wait
    }

    @Test
    public void batchServerSettlingShowsStarting() {
        SetupUiState ui = new SetupUiState.Inputs()
                .moduleFlow(true).batchServerSettling(true).batchServerUp(false).build();
        // moduleFlow && !batchServerUp would read INSTALLING, but batchServerSettling wins (checked first).
        assertEquals(SetupUiState.StatusMessage.STARTING, ui.message);
    }

    @Test
    public void moduleInFlightShowsInstalling() {
        SetupUiState ui = new SetupUiState.Inputs().moduleFlow(true).batchServerUp(false).build();
        assertEquals(SetupUiState.StatusMessage.INSTALLING, ui.message);
        assertEquals(SetupUiState.StatusTone.WAITING, ui.tone);
    }

    @Test
    public void failedModuleKeepsInstallingHeader() {
        SetupUiState ui = new SetupUiState.Inputs()
                .moduleFlow(true).batchServerUp(true).moduleFailed(true).build();
        assertEquals(SetupUiState.StatusMessage.INSTALLING, ui.message);
    }

    @Test
    public void moduleDoneServerUpShowsAdding() {
        SetupUiState ui = new SetupUiState.Inputs()
                .moduleFlow(true).batchServerUp(true).moduleFailed(false).build();
        assertEquals(SetupUiState.StatusMessage.ADDING, ui.message);
        assertEquals(SetupUiState.StatusTone.NEUTRAL, ui.tone);
    }

    @Test
    public void successRedirectsUntilCancelled() {
        assertEquals(SetupUiState.Controls.REDIRECT,
                running().success(true).redirectCancelled(false).build().controls);
        assertEquals(SetupUiState.Controls.FINISH_SUCCESS,
                running().success(true).redirectCancelled(true).build().controls);
    }

    @Test
    public void failureShowsFinish() {
        assertEquals(SetupUiState.Controls.FINISH_FAILURE, running().failure(true).build().controls);
    }

    @Test
    public void runningPassesThroughRunInBackground() {
        SetupUiState on = running().runInBackgroundEnabled(true).build();
        assertEquals(SetupUiState.Controls.RUNNING, on.controls);
        assertTrue(on.runInBackgroundVisible);
        assertFalse(running().runInBackgroundEnabled(false).build().runInBackgroundVisible);
    }
}
