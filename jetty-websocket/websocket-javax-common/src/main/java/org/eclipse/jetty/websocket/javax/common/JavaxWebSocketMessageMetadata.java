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

package org.eclipse.jetty.websocket.javax.common;

import java.lang.invoke.MethodHandle;
import java.util.List;

import org.eclipse.jetty.websocket.core.internal.messages.MessageSink;
import org.eclipse.jetty.websocket.javax.common.decoders.RegisteredDecoder;

public class JavaxWebSocketMessageMetadata
{
    private MethodHandle methodHandle;
    private Class<? extends MessageSink> sinkClass;
    private List<RegisteredDecoder> registeredDecoders;

    private int maxMessageSize = -1;
    private boolean maxMessageSizeSet = false;

    public static JavaxWebSocketMessageMetadata copyOf(JavaxWebSocketMessageMetadata metadata)
    {
        if (metadata == null)
            return null;

        JavaxWebSocketMessageMetadata copy = new JavaxWebSocketMessageMetadata();
        copy.methodHandle = metadata.methodHandle;
        copy.sinkClass = metadata.sinkClass;
        copy.registeredDecoders = metadata.registeredDecoders;
        copy.maxMessageSize = metadata.maxMessageSize;
        copy.maxMessageSizeSet = metadata.maxMessageSizeSet;
        return copy;
    }

    public boolean isMaxMessageSizeSet()
    {
        return maxMessageSizeSet;
    }

    public int getMaxMessageSize()
    {
        return maxMessageSize;
    }

    public void setMaxMessageSize(int maxMessageSize)
    {
        this.maxMessageSize = maxMessageSize;
        this.maxMessageSizeSet = true;
    }

    public MethodHandle getMethodHandle()
    {
        return methodHandle;
    }

    public void setMethodHandle(MethodHandle methodHandle)
    {
        this.methodHandle = methodHandle;
    }

    public Class<? extends MessageSink> getSinkClass()
    {
        return sinkClass;
    }

    public void setSinkClass(Class<? extends MessageSink> sinkClass)
    {
        this.sinkClass = sinkClass;
    }

    public List<RegisteredDecoder> getRegisteredDecoders()
    {
        return registeredDecoders;
    }

    public void setRegisteredDecoders(List<RegisteredDecoder> decoders)
    {
        this.registeredDecoders = decoders;
    }
}
