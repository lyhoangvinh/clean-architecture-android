package com.duyp.architecture.clean.android.powergit.rules;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import io.reactivex.android.plugins.RxAndroidPlugins;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.Schedulers;

/**
 * Reference: https://medium.com/@fabioCollini/testing-asynchronous-rxjava-code-using-mockito-8ad831a16877
 */
public class TrampolineSchedulerRule implements TestRule {

    @Override
    public Statement apply(final Statement base, final Description d) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                RxJavaPlugins.setIoSchedulerHandler(
                        scheduler -> Schedulers.trampoline());
                RxJavaPlugins.setComputationSchedulerHandler(
                        scheduler -> Schedulers.trampoline());
                RxJavaPlugins.setNewThreadSchedulerHandler(
                        scheduler -> Schedulers.trampoline());
                RxAndroidPlugins.setInitMainThreadSchedulerHandler(
                        scheduler -> Schedulers.trampoline());
                RxAndroidPlugins.setMainThreadSchedulerHandler(scheduler -> Schedulers.trampoline());

                try {
                    base.evaluate();
                } finally {
                    RxJavaPlugins.reset();
                    RxAndroidPlugins.reset();
                }
            }
        };
    }
}