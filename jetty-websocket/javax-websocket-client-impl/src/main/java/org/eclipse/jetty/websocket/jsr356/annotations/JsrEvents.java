//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.annotations;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.Map;

import javax.websocket.DecodeException;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.common.CloseInfo;

/**
 * The live event methods found for a specific Annotated Endpoint
 */
public class JsrEvents
{
    private final JsrMetadata<?> metadata;

    /**
     * Callable for &#064;{@link OnOpen} annotation.
     */
    private final OnOpenCallable onOpen;

    /**
     * Callable for &#064;{@link OnClose} annotation
     */
    private final OnCloseCallable onClose;

    /**
     * Callable for &#064;{@link OnError} annotation
     */
    private final OnErrorCallable onError;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Text Message Format
     */
    private final OnMessageTextCallable onText;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Text Streaming Message Format
     */
    private final OnMessageTextStreamCallable onTextStream;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Binary Message Format
     */
    private final OnMessageBinaryCallable onBinary;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Binary Streaming Message Format
     */
    private final OnMessageBinaryStreamCallable onBinaryStream;

    /**
     * Callable for &#064;{@link OnMessage} annotation dealing with Pong Message Format
     */
    private OnMessagePongCallable onPong;

    public JsrEvents(JsrMetadata<?> metadata)
    {
        this.metadata = metadata;
        this.onOpen = (metadata.onOpen == null)?null:new OnOpenCallable(metadata.onOpen);
        this.onClose = (metadata.onClose == null)?null:new OnCloseCallable(metadata.onClose);
        this.onError = (metadata.onError == null)?null:new OnErrorCallable(metadata.onError);
        this.onBinary = (metadata.onBinary == null)?null:new OnMessageBinaryCallable(metadata.onBinary);
        this.onBinaryStream = (metadata.onBinaryStream == null)?null:new OnMessageBinaryStreamCallable(metadata.onBinaryStream);
        this.onText = (metadata.onText == null)?null:new OnMessageTextCallable(metadata.onText);
        this.onTextStream = (metadata.onTextStream == null)?null:new OnMessageTextStreamCallable(metadata.onTextStream);
        this.onPong = (metadata.onPong == null)?null:new OnMessagePongCallable(metadata.onPong);
    }

    public void callBinary(Object websocket, ByteBuffer buf, boolean fin) throws DecodeException
    {
        if (onBinary == null)
        {
            return;
        }
        onBinary.call(websocket,buf,fin);
    }

    public void callBinaryStream(Object websocket, InputStream stream) throws DecodeException, IOException
    {
        if (onBinaryStream == null)
        {
            return;
        }
        onBinaryStream.call(websocket,stream);
    }

    public void callClose(Object websocket, CloseInfo close)
    {
        if (onClose == null)
        {
            return;
        }
        onClose.call(websocket,close);
    }

    public void callError(Object websocket, Throwable cause)
    {
        if (onError == null)
        {
            return;
        }
        onError.call(websocket,cause);
    }

    public void callOpen(Object websocket, EndpointConfig config)
    {
        if (onOpen == null)
        {
            return;
        }
        onOpen.call(websocket,config);
    }

    public void callText(Object websocket, String text, boolean fin) throws DecodeException
    {
        if (onText == null)
        {
            return;
        }
        onText.call(websocket,text,fin);
    }

    public void callTextStream(Object websocket, Reader reader) throws DecodeException, IOException
    {
        if (onTextStream == null)
        {
            return;
        }
        onTextStream.call(websocket,reader);
    }

    public boolean hasBinary()
    {
        return (onBinary != null);
    }

    public boolean hasBinaryStream()
    {
        return (onBinaryStream != null);
    }

    public boolean hasText()
    {
        return (onText != null);
    }

    public boolean hasTextStream()
    {
        return (onTextStream != null);
    }

    public void init(Session session)
    {
        Map<String, String> pathParams = session.getPathParameters();

        if (onOpen != null)
        {
            onOpen.init(session);
        }
        if (onClose != null)
        {
            onClose.init(session);
        }
        if (onError != null)
        {
            onError.init(session);
        }
        if (onText != null)
        {
            onText.init(session);
        }
        if (onTextStream != null)
        {
            onTextStream.init(session);
        }
        if (onBinary != null)
        {
            onBinary.init(session);
        }
        if (onBinaryStream != null)
        {
            onBinaryStream.init(session);
        }
        if (onPong != null)
        {
            onPong.init(session);
        }
    }

    public boolean isBinaryPartialSupported()
    {
        if (onBinary == null)
        {
            return false;
        }
        return onBinary.isPartialMessageSupported();
    }

    public boolean isTextPartialSupported()
    {
        if (onText == null)
        {
            return false;
        }
        return onText.isPartialMessageSupported();
    }
}
