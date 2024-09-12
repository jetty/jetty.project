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

package org.eclipse.jetty.ee9.websocket.jakarta.common;

import java.lang.invoke.WrongMethodTypeException;

import jakarta.websocket.MessageHandler;
import org.eclipse.jetty.websocket.core.util.MethodHolder;

class JakartaMessageWholeMethodHolder<T> implements MethodHolder
{
    private final MessageHandler.Whole<T> _messageHandler;

    public JakartaMessageWholeMethodHolder(MessageHandler.Whole<T> messageHandler)
    {
        _messageHandler = messageHandler;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object invoke(Object... args) throws Throwable
    {
        if (args.length != 1)
            throw new WrongMethodTypeException(String.format("Expected %s params but had %s", 1, args.length));
        _messageHandler.onMessage((T)args[0]);
        return null;
    }

    @Override
    public Class<?> parameterType(int idx)
    {
        switch (idx)
        {
            case 0:
                return Object.class;
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
