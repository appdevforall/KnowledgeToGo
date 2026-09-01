/*
 * ============================================================================
 * Name        : KolibriReadinessViewModel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Answers "can this device take courses right now, and what does it
 *               already have?" — through the ADR-5061 model (ADFA-4954).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.presentation;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.appdevforall.k2go.kolibri.data.InstalledChannelsSource;
import org.appdevforall.k2go.kolibri.data.KolibriPlatformProbe;
import org.appdevforall.k2go.kolibri.domain.InstalledLibrary;
import org.appdevforall.k2go.system.data.SystemFactsReader;
import org.appdevforall.k2go.system.domain.Operation;
import org.appdevforall.k2go.system.domain.OperationDispatcher;
import org.appdevforall.k2go.system.domain.PlatformPresence;
import org.appdevforall.k2go.system.domain.SystemFacts;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves what should happen to a Courses order, and what the device already has.
 *
 * <p>This is the first consumer of the ADR-5061 model. The question it replaces was
 * "am I running inside the wizard?", answered from a boolean on the activity that
 * did not survive being restored. The question it asks instead is "can this
 * operation run now?", answered from facts the device can look up — and the four
 * possible answers become four behaviours rather than two.
 *
 * <p>Three reads, one round trip each, all off the main thread: the system facts,
 * whether Kolibri is on this box at all, and what it already holds. Its failure is
 * {@link InstalledLibrary#unknown()} rather than "nothing installed" — a picker
 * acting on the wrong one of those would offer content the device already has.
 *
 * <p>ADFA-5061: the listing read used to be skipped whenever the platform did not answer,
 * which since "silence is not absence" would mean it ran precisely when the box was least
 * able to serve it — two timeouts in series, several seconds of "checking" for a value that
 * comes back unknown either way. It is now gated on the server being up instead.
 */
public class KolibriReadinessViewModel extends ViewModel {

    /** The Courses order, as the model describes it: content, over REST. */
    private static final Operation SEED = Operation.content("kolibri");

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<KolibriReadinessUiState> state =
            new MutableLiveData<>(KolibriReadinessUiState.checking());

    public KolibriReadinessViewModel(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public LiveData<KolibriReadinessUiState> state() {
        return state;
    }

    /**
     * Asks again. Cheap enough to call whenever the screen is shown: two short HTTP
     * calls against localhost and two filesystem checks.
     *
     * @param replacementPending whether the caller is inside a setup wizard that
     *                           will install or replace the system. The reader
     *                           cannot know this — it is a decision the user has
     *                           made, not a state of the box, and during a reinstall
     *                           every fact the reader can see still says the old
     *                           system is fine. Only the screen knows which door it
     *                           came through.
     */
    public void refresh(boolean replacementPending) {
        state.setValue(KolibriReadinessUiState.checking());
        executor.execute(() -> {
            SystemFacts facts = SystemFactsReader.read(appContext);
            if (replacementPending) {
                facts = facts.withReplacementPending();
            }

            // Only worth asking once there is a system that is staying: with nothing
            // installed, or one about to be replaced, the dispatcher answers DEFER
            // without consulting the platform anyway.
            //
            // ADFA-5061: this is where the collapse used to happen. The probe returned a
            // boolean and any failure meant "absent", which the dispatcher treats as
            // terminal — so a courses order asked for while an earlier one was still
            // downloading was refused with "not installed" and thrown away. The probe now
            // reports evidence rather than a verdict, and PlatformPresence weighs it.
            boolean present = facts.isInstalled() && !facts.isReplacementPending()
                    && PlatformPresence.resolve(KolibriPlatformProbe.probe());
            // Gated on the server, not on the probe. Asking a box that is not answering costs
            // another full timeout in series and comes back unknown regardless — which is
            // what the picker gets here anyway.
            InstalledLibrary library = present && facts.isServerUp()
                    ? InstalledChannelsSource.read()
                    : InstalledLibrary.unknown();

            state.postValue(KolibriReadinessUiState.resolved(
                    OperationDispatcher.resolve(SEED, facts, present), facts, library));
        });
    }

    @Override
    protected void onCleared() {
        executor.shutdownNow();
    }
}
