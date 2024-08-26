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
import java.lang.invoke.WrongMethodTypeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An interface for managing invocations of methods whose arguments may need to be augmented, by
 * binding in certain parameters ahead of time.
 *
 * Implementations may use various invocation mechanisms, including:
 * <ul>
 *  <li>direct method invocation on an held object</li>
 *  <li>calling a method pointer</li>
 *  <li>calling a MethodHandle bound to the known arguments</li>
 *  <li>calling a MethodHandle without binding to the known arguments</li>
 * </ul>
 *
 * Implementations of this may not be thread safe, so the caller must use some external mutual exclusion
 * unless they are using a specific implementation known to be thread-safe.
 */
public interface MethodHolder
{
    String METHOD_HOLDER_BINDING_PROPERTY = "jetty.websocket.methodholder.binding";
    boolean IS_BINDING = System.getProperty(METHOD_HOLDER_BINDING_PROPERTY) == null || Boolean.getBoolean(METHOD_HOLDER_BINDING_PROPERTY);

    static MethodHolder from(MethodHandle methodHandle)
    {
        return from(methodHandle, IS_BINDING);
    }

    static MethodHolder from(MethodHandle methodHandle, boolean binding)
    {
        if (methodHandle == null)
            return null;
        return binding ? new Binding(methodHandle) : new NonBinding(methodHandle);
    }

    Object invoke(Object... args) throws Throwable;

    default MethodHolder bindTo(Object arg)
    {
        throw new UnsupportedOperationException();
    }

    default MethodHolder bindTo(Object arg, int idx)
    {
        throw new UnsupportedOperationException();
    }

    default Class<?> parameterType(int idx)
    {
        throw new UnsupportedOperationException();
    }

    default Class<?> returnType()
    {
        throw new UnsupportedOperationException();
    }

    static Object doInvoke(MethodHandle methodHandle, Object... args) throws Throwable
    {
        switch (args.length)
        {
            case 0:
                return methodHandle.invoke();
            case 1:
                return methodHandle.invoke(args[0]);
            case 2:
                return methodHandle.invoke(args[0], args[1]);
            case 3:
                return methodHandle.invoke(args[0], args[1], args[2]);
            case 4:
                return methodHandle.invoke(args[0], args[1], args[2], args[3]);
            case 5:
                return methodHandle.invoke(args[0], args[1], args[2], args[3], args[4]);
            case 6:
                return methodHandle.invoke(args[0], args[1], args[2], args[3], args[4], args[5]);
            case 7:
                return methodHandle.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
            case 8:
                return methodHandle.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
            case 9:
                return methodHandle.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8]);
            default:
                return methodHandle.invokeWithArguments(args);
        }
    }

    class Binding implements MethodHolder
    {
        public MethodHandle _methodHandle;

        private Binding(MethodHandle methodHandle)
        {
            _methodHandle = methodHandle;
        }

        @Override
        public Object invoke(Object... args) throws Throwable
        {
            return doInvoke(_methodHandle, args);
        }

        @Override
        public Binding bindTo(Object arg)
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

    /**
     * This implementation of {@link MethodHolder} is not thread safe.
     * Mutual exclusion should be used when calling {@link #invoke(Object...)}, or this should only
     * be invoked from a single thread.
     */
    class NonBinding implements MethodHolder
    {
        private final MethodHandle _methodHandle;
        private final Object[] _parameters;
        private final List<Integer> _unboundParamIndexes = new ArrayList<>();

        private NonBinding(MethodHandle methodHandle)
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
                return doInvoke(_methodHandle, _parameters);
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
}
