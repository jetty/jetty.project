//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.lang.annotation.Annotation;
import java.nio.ByteBuffer;
import java.util.Map;

import javax.websocket.CloseReason;
import javax.websocket.DecodeException;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.jsr356.JsrSession;

/**
 * The live event methods found for a specific Annotated Endpoint
 * @param <T> the annotation type
 * @param <C> the endpoint config type
 */
public class JsrEvents<T extends Annotation, C extends EndpointConfig>
{
    private static final Logger LOG = Log.getLogger(JsrEvents.class);
    private final AnnotatedEndpointMetadata<T, C> metadata;

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

    /**
     * The Request Parameters (from resolved javax.websocket.server.PathParam entries)
     */
    private Map<String, String> pathParameters;

    public JsrEvents(AnnotatedEndpointMetadata<T, C> metadata)
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

    public void callBinary(RemoteEndpoint.Async endpoint, Object websocket, ByteBuffer buf, boolean fin) throws DecodeException
    {
        if (onBinary == null)
        {
            return;
        }

        Object ret = onBinary.call(websocket,buf,fin);
        if (ret != null)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("returning: {}",ret);
            }
            endpoint.sendObject(ret);
        }
    }

    public void callBinaryStream(RemoteEndpoint.Async endpoint, Object websocket, InputStream stream) throws DecodeException, IOException
    {
        if (onBinaryStream == null)
        {
            return;
        }

        Object ret = onBinaryStream.call(websocket,stream);
        if (ret != null)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("returning: {}",ret);
            }
            endpoint.sendObject(ret);
        }
    }

    public void callClose(Object websocket, CloseReason close)
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
            LOG.warn("Unable to report throwable to websocket (no @OnError handler declared): " + websocket.getClass().getName(), cause);
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

    public void callPong(RemoteEndpoint.Async endpoint, Object websocket, ByteBuffer pong)
    {
        if (onPong == null)
        {
            return;
        }

        Object ret = onPong.call(websocket,pong);
        if (ret != null)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("returning: {}",ret);
            }
            endpoint.sendObject(ret);
        }
    }

    public void callText(RemoteEndpoint.Async endpoint, Object websocket, String text, boolean fin) throws DecodeException
    {
        if (onText == null)
        {
            return;
        }
        Object ret = onText.call(websocket,text,fin);
        if (ret != null)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("returning: {}",ret);
            }
            endpoint.sendObject(ret);
        }
    }

    public void callTextStream(RemoteEndpoint.Async endpoint, Object websocket, Reader reader) throws DecodeException, IOException
    {
        if (onTextStream == null)
        {
            return;
        }
        Object ret = onTextStream.call(websocket,reader);
        if (ret != null)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("returning: {}",ret);
            }
            endpoint.sendObject(ret);
        }
    }

    public AnnotatedEndpointMetadata<T, C> getMetadata()
    {
        return metadata;
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

    public void init(JsrSession session)
    {
        session.setPathParameters(pathParameters);

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

    public void setPathParameters(Map<String, String> pathParameters)
    {
        this.pathParameters = pathParameters;
    }
}
