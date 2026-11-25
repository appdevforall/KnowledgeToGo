/*
 * ============================================================================
 * Name        : ClassViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: wires the Class tab ViewModel by hand.
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.appdevforall.k2go.ServerStateRepository;
import org.appdevforall.k2go.school.data.HttpTeacherPage;
import org.appdevforall.k2go.util.AppExecutors;

public class ClassViewModelFactory implements ViewModelProvider.Factory {

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(ClassViewModel.class)) {
            ServerStateRepository server = ServerStateRepository.get();
            return (T) new ClassViewModel(new HttpTeacherPage(), AppExecutors.get().io(),
                    server.state(), server::hasObservation);
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
