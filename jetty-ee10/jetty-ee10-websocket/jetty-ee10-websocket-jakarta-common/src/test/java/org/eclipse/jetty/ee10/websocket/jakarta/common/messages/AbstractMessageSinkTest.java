//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.jakarta.common.messages;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.function.Consumer;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Decoder;
import org.eclipse.jetty.ee10.websocket.jakarta.common.AbstractSessionTest;
import org.eclipse.jetty.ee10.websocket.jakarta.common.JakartaWebSocketFrameHandlerFactory;
import org.eclipse.jetty.ee10.websocket.jakarta.common.decoders.RegisteredDecoder;

public abstract class AbstractMessageSinkTest extends AbstractSessionTest
{
    public List<RegisteredDecoder> toRegisteredDecoderList(Class<? extends Decoder> clazz, Class<?> objectType)
    {
        Class<? extends Decoder> interfaceType;
        if (Decoder.Text.class.isAssignableFrom(clazz))
            interfaceType = Decoder.Text.class;
        else if (Decoder.Binary.class.isAssignableFrom(clazz))
            interfaceType = Decoder.Binary.class;
        else if (Decoder.TextStream.class.isAssignableFrom(clazz))
            interfaceType = Decoder.TextStream.class;
        else if (Decoder.BinaryStream.class.isAssignableFrom(clazz))
            interfaceType = Decoder.BinaryStream.class;
        else
            throw new IllegalStateException();

        return List.of(new RegisteredDecoder(clazz, interfaceType, objectType, ClientEndpointConfig.Builder.create().build(), components));
    }

    public <T> MethodHandle getAcceptHandle(Consumer<T> copy, Class<T> type)
    {
        try
        {
            Class<?> refc = copy.getClass();
            String name = "accept";
            MethodType methodType = MethodType.methodType(void.class, type);
            MethodHandle handle = JakartaWebSocketFrameHandlerFactory.getServerMethodHandleLookup().findVirtual(refc, name, methodType);
            return handle.bindTo(copy);
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            throw new RuntimeException("Ooops, we didn't find the Consumer<" + type.getName() + "> MethodHandle", e);
        }
    }
}
