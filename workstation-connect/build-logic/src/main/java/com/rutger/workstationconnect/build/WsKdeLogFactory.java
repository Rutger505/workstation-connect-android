// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.build;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Set;

/**
 * Upstream KDE Connect logs into AppLog without touching upstream source: rewrites
 * {@code android.util.Log.{v,d,i,w,e,wtf,println}} calls in {@code org.kde.kdeconnect.*} classes to the
 * same-signature static methods of {@code com.rutger.workstationconnect.KdeLog}. Registered by {@link WsKdeLogPlugin}.
 */
public abstract class WsKdeLogFactory implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    private static final Set<String> NAMES = Set.of("v", "d", "i", "w", "e", "wtf", "println");
    private static final String TARGET = "com/rutger/workstationconnect/KdeLog";

    @Override
    public ClassVisitor createClassVisitor(ClassContext ctx, ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, ex)) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        boolean redirect = op == Opcodes.INVOKESTATIC && owner.equals("android/util/Log") && NAMES.contains(n);
                        super.visitMethodInsn(op, redirect ? TARGET : owner, n, d, itf);
                    }
                };
            }
        };
    }

    @Override
    public boolean isInstrumentable(ClassData data) {
        return data.getClassName().startsWith("org.kde.kdeconnect.");
    }
}
