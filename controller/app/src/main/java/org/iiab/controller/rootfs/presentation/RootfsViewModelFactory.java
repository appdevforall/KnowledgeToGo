package org.iiab.controller.rootfs.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.rootfs.data.RootfsCatalog;
import org.iiab.controller.rootfs.data.RootfsRemoteDataSource;
import org.iiab.controller.rootfs.data.RootfsRepositoryImpl;
import org.iiab.controller.rootfs.domain.GetRootfsSizeUseCase;
import org.iiab.controller.rootfs.domain.RootfsRepository;

/**
 * Manual dependency wiring for {@link RootfsViewModel}.
 *
 * <p>A pilot does not need a DI framework: this factory composes
 * Data -> Domain -> Presentation by hand. Introducing Hilt/Dagger, if ever, is a
 * separate, explicit decision.
 */
public class RootfsViewModelFactory implements ViewModelProvider.Factory {

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(RootfsViewModel.class)) {
            RootfsRepository repository =
                    new RootfsRepositoryImpl(new RootfsRemoteDataSource(), new RootfsCatalog());
            GetRootfsSizeUseCase useCase = new GetRootfsSizeUseCase(repository);
            return (T) new RootfsViewModel(useCase);
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
