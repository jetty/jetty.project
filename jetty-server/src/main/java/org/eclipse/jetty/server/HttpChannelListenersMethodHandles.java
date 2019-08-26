//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpChannelListenersMethodHandles implements HttpChannel.Listener
{
    private static Logger LOG = Log.getLogger(HttpChannelListenersMethodHandles.class);
    private static HttpChannel.Listener NOOP = new HttpChannel.Listener() {};

    private final MethodHandle onRequestBegin;
    private final MethodHandle onBeforeDispatch;
    private final MethodHandle onDispatchFailure;
    private final MethodHandle onAfterDispatch;
    private final MethodHandle onRequestContent;
    private final MethodHandle onRequestContentEnd;
    private final MethodHandle onRequestTrailers;
    private final MethodHandle onRequestEnd;
    private final MethodHandle onRequestFailure;
    private final MethodHandle onResponseBegin;
    private final MethodHandle onResponseCommit;
    private final MethodHandle onResponseContent;
    private final MethodHandle onResponseEnd;
    private final MethodHandle onResponseFailure;
    private final MethodHandle onComplete;

    public HttpChannelListenersMethodHandles(Collection<HttpChannel.Listener> listeners)
    {
        try
        {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType notifyRequest = MethodType.methodType(Void.TYPE, Request.class);
            MethodType notifyFailure = MethodType.methodType(Void.TYPE, Request.class, Throwable.class);
            MethodType notifyContent = MethodType.methodType(Void.TYPE, Request.class, ByteBuffer.class);

            Map<String, MethodHandle> methods = new HashMap<>();
            Arrays.stream(HttpChannel.Listener.class.getDeclaredMethods()).filter(Method::isDefault).forEach(m ->
            {
                try
                {
                    MethodType type = notifyRequest;
                    if (m.getParameterCount() == 2)
                        type = m.getParameterTypes()[1] == Throwable.class ? notifyFailure : notifyContent;

                    MethodHandle mh = lookup.findVirtual(HttpChannel.Listener.class, m.getName(), type).bindTo(NOOP);
                    methods.put(m.getName(), mh);
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            });

            for (HttpChannel.Listener listener : listeners)
            {
                Class<? extends HttpChannel.Listener> clazz = listener.getClass();
                for (Map.Entry<String, MethodHandle> entry : methods.entrySet())
                {
                    MethodType type = entry.getValue().type();
                    entry.setValue(MethodHandles.foldArguments(lookup.findVirtual(clazz, entry.getKey(), type).bindTo(listener), entry.getValue()));
                }
            }

            onRequestBegin = methods.get("onRequestBegin");
            onBeforeDispatch = methods.get("onBeforeDispatch");
            onDispatchFailure = methods.get("onDispatchFailure");
            onAfterDispatch = methods.get("onAfterDispatch");
            onRequestContent = methods.get("onRequestContent");
            onRequestContentEnd = methods.get("onRequestContentEnd");
            onRequestTrailers = methods.get("onRequestTrailers");
            onRequestEnd = methods.get("onRequestEnd");
            onRequestFailure = methods.get("onRequestFailure");
            onResponseBegin = methods.get("onResponseBegin");
            onResponseCommit = methods.get("onResponseCommit");
            onResponseContent = methods.get("onResponseContent");
            onResponseEnd = methods.get("onResponseEnd");
            onResponseFailure = methods.get("onResponseFailure");
            onComplete = methods.get("onComplete");
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onRequestBegin(Request request)
    {
        try
        {
            onRequestBegin.invoke(request);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onBeforeDispatch(Request request)
    {
        try
        {
            onBeforeDispatch.invoke(request);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onDispatchFailure(Request request, Throwable failure)
    {
        try
        {
            onDispatchFailure.invoke(request, failure);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onAfterDispatch(Request request)
    {
        try
        {
            onAfterDispatch.invoke(request);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onRequestContent(Request request, ByteBuffer content)
    {
        try
        {
            onRequestContent.invoke(request, content.slice());
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onRequestContentEnd(Request request)
    {
        try
        {
            onRequestContentEnd.invoke(request);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onRequestTrailers(Request request)
    {
        try
        {
            onRequestTrailers.invoke(request);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onRequestEnd(Request request)
    {
        try
        {
            onRequestEnd.invoke(request);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onRequestFailure(Request request, Throwable failure)
    {
        try
        {
            onRequestFailure.invoke(request, failure);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onResponseBegin(Request request)
    {
        try
        {
            onResponseBegin.invoke(request);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onResponseCommit(Request request)
    {
        try
        {
            onResponseCommit.invoke(request);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onResponseContent(Request request, ByteBuffer content)
    {
        try
        {
            onResponseContent.invoke(request, content.slice());
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onResponseEnd(Request request)
    {
        try
        {
            onResponseEnd.invoke(request);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onResponseFailure(Request request, Throwable failure)
    {
        try
        {
            onResponseFailure.invoke(request, failure);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }

    @Override
    public void onComplete(Request request)
    {
        try
        {
            onComplete.invoke(request);
        }
        catch (Throwable th)
        {
            LOG.warn(th);
        }
    }
}
