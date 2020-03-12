//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.common.messages;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;

import org.eclipse.jetty.websocket.jakarta.common.AbstractSessionTest;

public abstract class AbstractMessageSinkTest extends AbstractSessionTest
{
    public <T> MethodHandle getAcceptHandle(Consumer<T> copy, Class<T> type)
    {
        try
        {
            Class<?> refc = copy.getClass();
            String name = "accept";
            MethodType methodType = MethodType.methodType(void.class, type);
            MethodHandle handle = MethodHandles.lookup().findVirtual(refc, name, methodType);
            return handle.bindTo(copy);
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            throw new RuntimeException("Ooops, we didn't find the Consumer<" + type.getName() + "> MethodHandle", e);
        }
    }
}
