//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal.util;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;

public class LambdaMetafactoryMethodHolder implements MethodHolder
{
    private final CallSite _callSite;

    public LambdaMetafactoryMethodHolder(MethodHandle methodHandle, MethodHandles.Lookup lookup) throws Throwable
    {
        MethodType methodType = methodHandle.type().changeReturnType(Supplier.class);
        _callSite = LambdaMetafactory.metafactory(
            lookup,
            "get",
            methodType,
            MethodType.methodType(Object.class),
            methodHandle,
            // Supplier method real signature (reified)
            // trim accepts no parameters and returns String
            MethodType.methodType(methodType.returnType()));
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object invoke(Object... args) throws Throwable
    {
        return ((Supplier<Object>)MethodHolder.doInvoke(_callSite.getTarget(), args)).get();
    }

    @Override
    public LambdaMetafactoryMethodHolder bindTo(Object arg)
    {
        _callSite.setTarget(_callSite.getTarget().bindTo(arg));
        return this;
    }

    @Override
    public MethodHolder bindTo(Object arg, int idx)
    {
        _callSite.setTarget(MethodHandles.insertArguments(_callSite.getTarget(), idx, arg));
        return this;
    }

    @Override
    public Class<?> parameterType(int idx)
    {
        return _callSite.type().parameterType(idx);
    }

    @Override
    public Class<?> returnType()
    {
        return _callSite.type().returnType();
    }
}
