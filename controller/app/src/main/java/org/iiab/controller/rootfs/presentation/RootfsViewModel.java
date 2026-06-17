package org.iiab.controller.rootfs.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.iiab.controller.rootfs.domain.GetRootfsSizeUseCase;
import org.iiab.controller.rootfs.domain.Rootfs;
import org.iiab.controller.rootfs.domain.RootfsAbi;
import org.iiab.controller.rootfs.domain.RootfsTier;
import org.iiab.controller.util.ByteFormatter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Presentation-layer ViewModel for the rootfs-size feature.
 *
 * <p>Calls the domain {@link GetRootfsSizeUseCase} off the main thread and
 * exposes a {@link RootfsUiState} stream. This is the reference for how UI in
 * the migrated architecture should consume the Domain layer; screens observe
 * {@link #state()} instead of formatting or fetching sizes themselves.
 *
 * <p>Depends only on Domain abstractions (plus a formatting util) — never on the
 * Data layer directly.
 */
public class RootfsViewModel extends ViewModel {

    private final GetRootfsSizeUseCase getRootfsSize;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<RootfsUiState> state = new MutableLiveData<>(RootfsUiState.loading());

    public RootfsViewModel(GetRootfsSizeUseCase getRootfsSize) {
        this.getRootfsSize = getRootfsSize;
    }

    public LiveData<RootfsUiState> state() {
        return state;
    }

    /** Loads the size for a tier+abi; posts LOADING then SUCCESS/ERROR. */
    public void load(RootfsTier tier, RootfsAbi abi) {
        state.postValue(RootfsUiState.loading());
        executor.execute(() -> {
            try {
                Rootfs rootfs = getRootfsSize.execute(tier, abi);
                state.postValue(RootfsUiState.success(rootfs, ByteFormatter.toHuman(rootfs.sizeBytes())));
            } catch (Exception e) {
                state.postValue(RootfsUiState.error(e.getMessage()));
            }
        });
    }

    @Override
    protected void onCleared() {
        executor.shutdownNow();
    }
}
