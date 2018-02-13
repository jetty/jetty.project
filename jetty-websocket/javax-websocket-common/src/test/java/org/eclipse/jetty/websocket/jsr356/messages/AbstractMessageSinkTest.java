//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356.messages;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;

import org.eclipse.jetty.websocket.jsr356.AbstractSessionTest;

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
