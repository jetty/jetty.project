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

package org.eclipse.jetty.websocket.jsr356;

import java.lang.invoke.MethodHandle;

import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.websocket.core.InvalidWebSocketException;
import org.eclipse.jetty.websocket.jsr356.decoders.AvailableDecoders;
import org.eclipse.jetty.websocket.jsr356.encoders.AvailableEncoders;

public class JavaxWebSocketFrameHandlerMetadata
{
    // EndpointConfig entries
    private final EndpointConfig endpointConfig;
    private final AvailableDecoders availableDecoders;
    private final AvailableEncoders availableEncoders;

    private MethodHandle openHandle;
    private MethodHandle closeHandle;
    private MethodHandle errorHandle;

    // @OnMessage Settings (-2 means unset, -1 means no-limit)
    public static long UNSET = -2L;
    private MessageMetadata textMetadata;
    private MessageMetadata binaryMetadata;

    private MethodHandle pongHandle;

    public JavaxWebSocketFrameHandlerMetadata(EndpointConfig endpointConfig)
    {
        this.endpointConfig = endpointConfig;
        this.availableDecoders = new AvailableDecoders(endpointConfig);
        this.availableEncoders = new AvailableEncoders(endpointConfig);
    }

    public boolean hasBinaryMetdata()
    {
        return (binaryMetadata != null);
    }

    public void setBinaryMetadata(MessageMetadata metadata, Object origin)
    {
        assertNotSet(this.binaryMetadata, "BINARY Message Metadata", origin);
        this.binaryMetadata = metadata;
    }

    public MessageMetadata getBinaryMetadata()
    {
        return binaryMetadata;
    }

    public void setCloseHandler(MethodHandle close, Object origin)
    {
        assertNotSet(this.closeHandle, "CLOSE Handler", origin);
        this.closeHandle = close;
    }

    public MethodHandle getCloseHandle()
    {
        return closeHandle;
    }

    public void setErrorHandler(MethodHandle error, Object origin)
    {
        assertNotSet(this.errorHandle, "ERROR Handler", origin);
        this.errorHandle = error;
    }

    public MethodHandle getErrorHandle()
    {
        return errorHandle;
    }

    public void setEncoders(Class<? extends Encoder>[] encoders)
    {
        this.availableEncoders.registerAll(encoders);
    }

    public AvailableEncoders getAvailableEncoders()
    {
        return availableEncoders;
    }

    public void setDecoders(Class<? extends Decoder>[] decoders)
    {
        this.availableDecoders.registerAll(decoders);
    }

    public AvailableDecoders getAvailableDecoders()
    {
        return availableDecoders;
    }

    public void setOpenHandler(MethodHandle open, Object origin)
    {
        assertNotSet(this.openHandle, "OPEN Handler", origin);
        this.openHandle = open;
    }

    public MethodHandle getOpenHandle()
    {
        return openHandle;
    }

    public void setPongHandle(MethodHandle pong, Object origin)
    {
        assertNotSet(this.pongHandle, "PONG Handler", origin);
        this.pongHandle = pong;
    }

    public MethodHandle getPongHandle()
    {
        return pongHandle;
    }

    public boolean hasTextMetdata()
    {
        return (textMetadata != null);
    }

    public void setTextMetadata(MessageMetadata metadata, Object origin)
    {
        assertNotSet(this.textMetadata, "TEXT Messsage Metadata", origin);
        this.textMetadata = metadata;
    }

    public MessageMetadata getTextMetadata()
    {
        return textMetadata;
    }

    @SuppressWarnings("Duplicates")
    private void assertNotSet(Object val, String role, Object origin)
    {
        if (val == null)
            return;

        StringBuilder err = new StringBuilder();
        err.append("Cannot replace previously assigned [");
        err.append(role);
        err.append("] at ").append(describeOrigin(val));
        err.append(" with ");
        err.append(describeOrigin(origin));

        throw new InvalidWebSocketException(err.toString());
    }

    private String describeOrigin(Object obj)
    {
        if (obj == null)
        {
            return "<undefined>";
        }

        return obj.toString();
    }

    public static class MessageMetadata
    {
        public MethodHandle handle;
        public Class<? extends MessageSink> sinkClass;
        public AvailableDecoders.RegisteredDecoder registeredDecoder;
        public long maxMessageSize = UNSET;

        public static MessageMetadata copyOf(MessageMetadata metadata)
        {
            if (metadata == null)
                return null;

            MessageMetadata copy = new MessageMetadata();
            copy.handle = metadata.handle;
            copy.sinkClass = metadata.sinkClass;
            copy.registeredDecoder = metadata.registeredDecoder;
            copy.maxMessageSize = metadata.maxMessageSize;

            return copy;
        }

        public boolean isMaxMessageSizeSet()
        {
            return (maxMessageSize != UNSET) && (maxMessageSize != 0);
        }
    }
}
