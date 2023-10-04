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

package org.eclipse.jetty.websocket.javax.common;

import java.lang.invoke.WrongMethodTypeException;
import javax.websocket.MessageHandler;

import org.eclipse.jetty.websocket.core.internal.util.MethodHolder;

class JavaxMessagePartialMethodHolder<T> implements MethodHolder
{
    private final MessageHandler.Partial<T> _messageHandler;

    public JavaxMessagePartialMethodHolder(MessageHandler.Partial<T> messageHandler)
    {
        _messageHandler = messageHandler;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object invoke(Object... args) throws Throwable
    {
        if (args.length != 2)
            throw new WrongMethodTypeException(String.format("Expected %s params but had %s", 2, args.length));
        _messageHandler.onMessage((T)args[0], (boolean)args[1]);
        return null;
    }

    @Override
    public Class<?> parameterType(int idx)
    {
        switch (idx)
        {
            case 0:
                return Object.class;
            case 1:
                return boolean.class;
            default:
                throw new IndexOutOfBoundsException(idx);
        }
    }

    @Override
    public Class<?> returnType()
    {
        return void.class;
    }
}
