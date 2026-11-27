/*
 * ============================================================================
 * Name        : ClassViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: wires the Class tab ViewModel by hand.
 * ============================================================================
 */
package org.appdevforall.k2go.school.presentation;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.appdevforall.k2go.ServerStateRepository;
import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.school.data.HttpClassProbe;
import org.appdevforall.k2go.school.data.HttpTeacherPage;
import org.appdevforall.k2go.school.data.HttpClassStarter;
import org.appdevforall.k2go.school.data.NsdClassFinder;
import org.appdevforall.k2go.school.trust.data.HttpClassHello;
import org.appdevforall.k2go.school.trust.data.KeystoreTeacherKey;
import org.appdevforall.k2go.school.trust.data.SchoolTrustFiles;
import org.appdevforall.k2go.school.trust.domain.PhoneRole;
import org.appdevforall.k2go.school.trust.domain.VerifyClass;
import org.appdevforall.k2go.util.AppExecutors;

public class ClassViewModelFactory implements ViewModelProvider.Factory {

    private final Context app;
    private final SchoolTrustFiles trust;

    public ClassViewModelFactory(Context ctx) {
        app = ctx.getApplicationContext();
        trust = new SchoolTrustFiles(app.getNoBackupFilesDir());
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(ClassViewModel.class)) {
            ServerStateRepository server = ServerStateRepository.get();
            KeystoreTeacherKey teacherKey = new KeystoreTeacherKey();
            return (T) new ClassViewModel(new HttpTeacherPage(), new HttpClassProbe(), new HttpClassStarter(),
                    AppExecutors.get().io(),
                    server.state(), server::hasObservation,
                    () -> PhoneRole.of(trust, teacherKey, System.currentTimeMillis() / 1000),
                    () -> PhoneRole.isDeviceZero(trust));
        }
        if (modelClass.isAssignableFrom(FindClassesViewModel.class)) {
            VerifyClass verify = new VerifyClass(trust, new HttpClassHello());
            return (T) new FindClassesViewModel(new NsdClassFinder(app), new HttpClassProbe(),
                    c -> verify.check(c.address, BoxEndpoints.PORT, c.code, System.currentTimeMillis() / 1000),
                    AppExecutors.get().io());
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
