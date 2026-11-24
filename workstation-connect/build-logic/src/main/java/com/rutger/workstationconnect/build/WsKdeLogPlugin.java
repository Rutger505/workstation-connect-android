// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.build;

import com.android.build.api.instrumentation.InstrumentationScope;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import kotlin.Unit;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Registers {@link WsKdeLogFactory} (upstream android.util.Log → KdeLog → AppLog) for every variant. */
public class WsKdeLogPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().withPlugin("com.android.application", p -> {
            ApplicationAndroidComponentsExtension ext = project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
            ext.onVariants(ext.selector().all(), (Action<ApplicationVariant>) variant -> variant.getInstrumentation()
                .transformClassesWith(WsKdeLogFactory.class, InstrumentationScope.PROJECT, params -> Unit.INSTANCE));
        });
    }
}
