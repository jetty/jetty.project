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

package org.eclipse.jetty.websocket.core.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public class BindingMethodHolder2 implements MethodHolder
{
    public MethodHandle _methodHandle;

    public BindingMethodHolder2(MethodHandle methodHandle)
    {
        _methodHandle = methodHandle;
    }

    @Override
    public Object invoke(Object... args) throws Throwable
    {
        return MethodHolder.doInvoke(_methodHandle, args);
    }

    public MethodHandle getMethodHandler()
    {
        return _methodHandle;
    }

    public Object invoke(Object o1, Object o2) throws Throwable
    {
        return MethodHolder.doInvoke(_methodHandle, o1, o2);
    }

    @Override
    public BindingMethodHolder2 bindTo(Object arg)
    {
        _methodHandle = _methodHandle.bindTo(arg);
        return this;
    }

    @Override
    public MethodHolder bindTo(Object arg, int idx)
    {
        _methodHandle = MethodHandles.insertArguments(_methodHandle, idx, arg);
        return this;
    }

    @Override
    public Class<?> parameterType(int idx)
    {
        return _methodHandle.type().parameterType(idx);
    }

    @Override
    public Class<?> returnType()
    {
        return _methodHandle.type().returnType();
    }
}
