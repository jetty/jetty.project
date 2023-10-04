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
import java.lang.invoke.WrongMethodTypeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * This implementation of {@link MethodHolder} is not thread safe.
 * Mutual exclusion should be used when calling {@link #invoke(Object...)}, or this should only
 * be invoked from a single thread.
 */
class NonBindingMethodHolder implements MethodHolder
{
    private final MethodHandle _methodHandle;
    private final Object[] _parameters;
    private final List<Integer> _unboundParamIndexes = new ArrayList<>();

    public NonBindingMethodHolder(MethodHandle methodHandle)
    {
        _methodHandle = Objects.requireNonNull(methodHandle);
        int numParams = methodHandle.type().parameterCount();
        _parameters = new Object[numParams];
        for (int i = 0; i < numParams; i++)
        {
            _unboundParamIndexes.add(i);
        }
    }

    @Override
    public Object invoke(Object... args) throws Throwable
    {
        try
        {
            insertArguments(args);
            return MethodHolder.doInvoke(_methodHandle, _parameters);
        }
        finally
        {
            clearArguments();
        }
    }

    @Override
    public MethodHolder bindTo(Object arg, int idx)
    {
        _parameters[_unboundParamIndexes.get(idx)] = arg;
        _unboundParamIndexes.remove(idx);
        return this;
    }

    @Override
    public MethodHolder bindTo(Object arg)
    {
        return bindTo(arg, 0);
    }

    private void insertArguments(Object... args)
    {
        if (_unboundParamIndexes.size() != args.length)
            throw new WrongMethodTypeException(String.format("Expected %s params but had %s", _unboundParamIndexes.size(), args.length));

        int argsIndex = 0;
        for (int index : _unboundParamIndexes)
        {
            _parameters[index] = args[argsIndex++];
        }
    }

    private void clearArguments()
    {
        for (int i : _unboundParamIndexes)
        {
            _parameters[i] = null;
        }
    }

    @Override
    public Class<?> parameterType(int idx)
    {
        return _methodHandle.type().parameterType(_unboundParamIndexes.get(idx));
    }

    @Override
    public Class<?> returnType()
    {
        return _methodHandle.type().returnType();
    }
}
