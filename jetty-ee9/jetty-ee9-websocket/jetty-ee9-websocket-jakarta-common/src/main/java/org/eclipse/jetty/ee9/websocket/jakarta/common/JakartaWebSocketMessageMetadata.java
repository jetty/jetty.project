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

import java.util.List;

import org.eclipse.jetty.ee9.websocket.jakarta.common.decoders.RegisteredDecoder;
import org.eclipse.jetty.websocket.core.messages.MessageSink;
import org.eclipse.jetty.websocket.core.util.MethodHolder;

public class JakartaWebSocketMessageMetadata
{
    private MethodHolder methodHolder;
    private Class<? extends MessageSink> sinkClass;
    private List<RegisteredDecoder> registeredDecoders;

    private int maxMessageSize = -1;
    private boolean maxMessageSizeSet = false;

    public static JakartaWebSocketMessageMetadata copyOf(JakartaWebSocketMessageMetadata metadata)
    {
        if (metadata == null)
            return null;

        JakartaWebSocketMessageMetadata copy = new JakartaWebSocketMessageMetadata();
        copy.methodHolder = metadata.methodHolder;
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

    public MethodHolder getMethodHolder()
    {
        return methodHolder;
    }

    public void setMethodHolder(MethodHolder methodHolder)
    {
        this.methodHolder = methodHolder;
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
