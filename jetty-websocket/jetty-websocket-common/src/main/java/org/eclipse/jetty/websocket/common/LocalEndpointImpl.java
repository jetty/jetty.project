//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketLocalEndpoint;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.ReadOnlyDelegatedFrame;

public class LocalEndpointImpl implements WebSocketLocalEndpoint
{
    private final Logger log;
    private final Object endpointInstance;
    private final WebSocketPolicy policy;
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final MethodHandle openHandle;
    private final MethodHandle closeHandle;
    private final MethodHandle errorHandle;
    private final MessageSink textSink;
    private final MessageSink binarySink;
    private final MethodHandle frameHandle;
    private final MethodHandle pingHandle;
    private final MethodHandle pongHandle;
    private MessageSink activeMessageSink;

    public LocalEndpointImpl(Object endpointInstance, WebSocketPolicy endpointPolicy,
                             MethodHandle openHandle, MethodHandle closeHandle, MethodHandle errorHandle,
                             MessageSink textSink, MessageSink binarySink,
                             MethodHandle frameHandle,
                             MethodHandle pingHandle, MethodHandle pongHandle)
    {
        this.log = Log.getLogger(endpointInstance.getClass());

        this.endpointInstance = endpointInstance;
        this.policy = endpointPolicy;

        this.openHandle = openHandle;
        this.closeHandle = closeHandle;
        this.errorHandle = errorHandle;
        this.textSink = textSink;
        this.binarySink = binarySink;
        this.frameHandle = frameHandle;
        this.pingHandle = pingHandle;
        this.pongHandle = pongHandle;
    }

    public Logger getEndpointLogger()
    {
        return this.log;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public void onBinary(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = binarySink;

        acceptMessage(frame, callback);
    }

    @Override
    public void onText(Frame frame, Callback callback)
    {
        if (activeMessageSink == null)
            activeMessageSink = textSink;

        acceptMessage(frame, callback);
    }

    @Override
    public void onContinuation(Frame frame, Callback callback)
    {
        acceptMessage(frame, callback);
    }

    private void acceptMessage(Frame frame, Callback callback)
    {
        // No message sink is active
        if (activeMessageSink == null)
            return;

        // Accept the payload into the message sink
        activeMessageSink.accept(frame, callback);
        if (frame.isFin())
            activeMessageSink = null;
    }


    @Override
    public void onOpen()
    {
        if (open.compareAndSet(false, true))
        {
            if (openHandle == null)
                return;

            try
            {
                openHandle.invoke();
            }
            catch (Throwable cause)
            {
                throw new WebSocketException("Unhandled OPEN endpoint method error", cause);
            }
        }
    }

    @Override
    public void onClose(CloseStatus close)
    {
        if (open.compareAndSet(true, false))
        {
            if (closeHandle == null)
                return;

            try
            {
                closeHandle.invoke(close.getCode(), close.getReason());
            }
            catch (Throwable cause)
            {
                throw new WebSocketException("Unhandled CLOSE endpoint method error", cause);
            }
        }
    }

    @Override
    public void onFrame(Frame frame)
    {
        if (frameHandle == null)
            return;

        try
        {
            frameHandle.invoke(new ReadOnlyDelegatedFrame(frame));
        }
        catch (Throwable cause)
        {
            throw new WebSocketException("Unhandled FRAME endpoint method error", cause);
        }
    }

    @Override
    public void onError(Throwable cause)
    {
        if (open.compareAndSet(true, false))
        {
            if (errorHandle == null)
            {
                log.warn("ERROR endpoint method missing", cause);
                return;
            }

            try
            {
                errorHandle.invoke(cause);
            }
            catch (Throwable t)
            {
                WebSocketException wsError = new WebSocketException("Unhandled ERROR endpoint method error", t);
                wsError.addSuppressed(cause);
                throw wsError;
            }
        }
        else
        {
            log.warn("ERROR endpoint method not called (endpoint closed)", cause);
        }
    }

    @Override
    public void onPing(ByteBuffer payload)
    {
        if (pingHandle == null)
            return;

        try
        {
            if (payload == null)
                payload = BufferUtil.EMPTY_BUFFER;

            pingHandle.invoke(payload);
        }
        catch (Throwable cause)
        {
            throw new WebSocketException("Unhandled PING endpoint method error", cause);
        }
    }

    @Override
    public void onPong(ByteBuffer payload)
    {
        if (pongHandle == null)
            return;

        try
        {
            if (payload == null)
                payload = BufferUtil.EMPTY_BUFFER;

            pongHandle.invoke(payload);
        }
        catch (Throwable cause)
        {
            throw new WebSocketException("Unhandled PONG endpoint method error", cause);
        }
    }
}
