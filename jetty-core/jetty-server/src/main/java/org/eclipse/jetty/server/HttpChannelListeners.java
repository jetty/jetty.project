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

package org.eclipse.jetty.server;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Collection;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.invoke.MethodType.methodType;

/**
 * A HttpChannel.Listener that holds a collection of
 * other HttpChannel.Listener instances that are efficiently
 * invoked without iteration.
 * @see AbstractConnector
 */
public class HttpChannelListeners implements HttpChannel.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelListeners.class);

    private static final MethodType LISTENER_TYPE_ON_HANDLING_BEFORE = methodType(Void.TYPE, Request.class);
    private static final MethodType LISTENER_TYPE_ON_HANDLING_AFTER = methodType(Void.TYPE, Request.class, Boolean.TYPE, Throwable.class);

    private static final MethodType LISTENER_TYPE_ON_REQUEST_BEGIN = methodType(Void.TYPE, Request.class);
    private static final MethodType LISTENER_TYPE_ON_REQUEST_READ = methodType(Void.TYPE, Request.class, Content.Chunk.class);

    private static final MethodType LISTENER_TYPE_ON_RESPONSE_COMMITTED = methodType(Void.TYPE, Request.class, Integer.TYPE, HttpFields.class);
    private static final MethodType LISTENER_TYPE_ON_RESPONSE_WRITE = methodType(Void.TYPE, Request.class, Boolean.TYPE, ByteBuffer.class, Throwable.class);

    private static final MethodType LISTENER_TYPE_ON_COMPLETE = methodType(Void.TYPE, Request.class, Throwable.class);

    // Static lookup of HttpChannel.Listener method handles (used when combining / folding arguments)
    private static final MethodHandle LISTENER_HANDLER_ON_HANDLING_BEFORE;
    private static final MethodHandle LISTENER_HANDLER_ON_HANDLING_AFTER;

    private static final MethodHandle LISTENER_HANDLER_ON_REQUEST_BEGIN;
    private static final MethodHandle LISTENER_HANDLER_ON_REQUEST_READ;

    private static final MethodHandle LISTENER_HANDLER_ON_RESPONSE_COMMITTED;
    private static final MethodHandle LISTENER_HANDLER_ON_RESPONSE_WRITE;

    private static final MethodHandle LISTENER_HANDLER_ON_COMPLETE;

    static
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try
        {
            LISTENER_HANDLER_ON_HANDLING_BEFORE = lookup.findVirtual(HttpChannel.Listener.class, "onBeforeHandling", LISTENER_TYPE_ON_HANDLING_BEFORE);
            LISTENER_HANDLER_ON_HANDLING_AFTER = lookup.findVirtual(HttpChannel.Listener.class, "onAfterHandling", LISTENER_TYPE_ON_HANDLING_AFTER);

            LISTENER_HANDLER_ON_REQUEST_BEGIN = lookup.findVirtual(HttpChannel.Listener.class, "onRequestBegin", LISTENER_TYPE_ON_REQUEST_BEGIN);
            LISTENER_HANDLER_ON_REQUEST_READ = lookup.findVirtual(HttpChannel.Listener.class, "onRequestRead", LISTENER_TYPE_ON_REQUEST_READ);

            LISTENER_HANDLER_ON_RESPONSE_COMMITTED = lookup.findVirtual(HttpChannel.Listener.class, "onResponseCommitted", LISTENER_TYPE_ON_RESPONSE_COMMITTED);
            LISTENER_HANDLER_ON_RESPONSE_WRITE = lookup.findVirtual(HttpChannel.Listener.class, "onResponseWrite", LISTENER_TYPE_ON_RESPONSE_WRITE);

            LISTENER_HANDLER_ON_COMPLETE = lookup.findVirtual(HttpChannel.Listener.class, "onComplete", LISTENER_TYPE_ON_COMPLETE);
        }
        catch (NoSuchMethodException e)
        {
            throw new RuntimeException(e);
        }
        catch (IllegalAccessException e)
        {
            throw new RuntimeException(e);
        }
    }

    // List of HttpChannel.Listener method handles that will be called
    private MethodHandle onHandlingBeforeHandle;
    private MethodHandle onHandlingAfterHandle;
    private MethodHandle onRequestBeginHandle;
    private MethodHandle onRequestReadHandle;
    private MethodHandle onResponseCommittedHandle;
    private MethodHandle onResponseWriteHandle;
    private MethodHandle onCompleteHandle;

    public HttpChannelListeners()
    {
        set(null);
    }

    public void set(Collection<HttpChannel.Listener> listeners)
    {
        onHandlingBeforeHandle = MethodHandles.empty(LISTENER_TYPE_ON_HANDLING_BEFORE);
        onHandlingAfterHandle = MethodHandles.empty(LISTENER_TYPE_ON_HANDLING_AFTER);

        onRequestBeginHandle = MethodHandles.empty(LISTENER_TYPE_ON_REQUEST_BEGIN);
        onRequestReadHandle = MethodHandles.empty(LISTENER_TYPE_ON_REQUEST_READ);

        onResponseCommittedHandle = MethodHandles.empty(LISTENER_TYPE_ON_RESPONSE_COMMITTED);
        onResponseWriteHandle = MethodHandles.empty(LISTENER_TYPE_ON_RESPONSE_WRITE);

        onCompleteHandle = MethodHandles.empty(LISTENER_TYPE_ON_COMPLETE);

        if (listeners == null)
            return;

        for (HttpChannel.Listener listener : listeners)
        {
            onHandlingBeforeHandle = MethodHandles.foldArguments(onHandlingBeforeHandle, LISTENER_HANDLER_ON_HANDLING_BEFORE.bindTo(listener));
            onHandlingAfterHandle = MethodHandles.foldArguments(onHandlingAfterHandle, LISTENER_HANDLER_ON_HANDLING_AFTER.bindTo(listener));

            onRequestBeginHandle = MethodHandles.foldArguments(onRequestBeginHandle, LISTENER_HANDLER_ON_REQUEST_BEGIN.bindTo(listener));
            onRequestReadHandle = MethodHandles.foldArguments(onRequestReadHandle, LISTENER_HANDLER_ON_REQUEST_READ.bindTo(listener));

            onResponseCommittedHandle = MethodHandles.foldArguments(onResponseCommittedHandle, LISTENER_HANDLER_ON_RESPONSE_COMMITTED.bindTo(listener));
            onResponseWriteHandle = MethodHandles.foldArguments(onResponseWriteHandle, LISTENER_HANDLER_ON_RESPONSE_WRITE.bindTo(listener));

            onCompleteHandle = MethodHandles.foldArguments(onCompleteHandle, LISTENER_HANDLER_ON_COMPLETE.bindTo(listener));
        }
    }

    @Override
    public void onRequestBegin(Request request)
    {
        try
        {
            onRequestBeginHandle.invoke(request);
        }
        catch (Throwable ignore)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("IGNORED", ignore);
        }
    }

    @Override
    public void onBeforeHandling(Request request)
    {
        try
        {
            onHandlingBeforeHandle.invoke(request);
        }
        catch (Throwable ignore)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("IGNORED", ignore);
        }
    }

    @Override
    public void onAfterHandling(Request request, boolean handled, Throwable failure)
    {
        try
        {
            onHandlingAfterHandle.invoke(request, handled, failure);
        }
        catch (Throwable ignore)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("IGNORED", ignore);
        }
    }

    @Override
    public void onRequestRead(Request request, Content.Chunk chunk)
    {
        try
        {
            onRequestReadHandle.invoke(request, chunk);
        }
        catch (Throwable ignore)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("IGNORED", ignore);
        }
    }

    @Override
    public void onResponseCommitted(Request request, int status, HttpFields response)
    {
        try
        {
            onResponseCommittedHandle.invoke(request, response);
        }
        catch (Throwable ignore)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("IGNORED", ignore);
        }
    }

    @Override
    public void onResponseWrite(Request request, boolean last, ByteBuffer content, Throwable failure)
    {
        try
        {
            onResponseWriteHandle.invoke(request, last, content);
        }
        catch (Throwable ignore)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("IGNORED", ignore);
        }
    }

    @Override
    public void onComplete(Request request, Throwable failure)
    {
        try
        {
            onCompleteHandle.invoke(request, failure);
        }
        catch (Throwable ignore)
        {
            if (LOG.isTraceEnabled())
                LOG.trace("IGNORED", ignore);
        }
    }
}
