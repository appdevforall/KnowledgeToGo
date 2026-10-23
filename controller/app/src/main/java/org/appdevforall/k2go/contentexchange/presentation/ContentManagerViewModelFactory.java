package org.appdevforall.k2go.contentexchange.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.appdevforall.k2go.contentexchange.data.ContentRepositoryImpl;
import org.appdevforall.k2go.contentexchange.data.CxCoreDataSource;
import org.appdevforall.k2go.contentexchange.domain.ContentRepository;
import org.appdevforall.k2go.contentexchange.domain.GetStorageStatusUseCase;
import org.appdevforall.k2go.contentexchange.domain.ListManagedContentUseCase;

/**
 * Manual dependency wiring for {@link ContentManagerViewModel}: composes
 * Data -> Domain -> Presentation by hand (no DI framework, per the feature rules).
 */
public class ContentManagerViewModelFactory implements ViewModelProvider.Factory {

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(ContentManagerViewModel.class)) {
            ContentRepository repository = new ContentRepositoryImpl(new CxCoreDataSource());
            return (T) new ContentManagerViewModel(
                    new ListManagedContentUseCase(repository),
                    new GetStorageStatusUseCase(repository));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
