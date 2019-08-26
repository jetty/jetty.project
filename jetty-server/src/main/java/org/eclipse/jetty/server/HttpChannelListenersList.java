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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpChannelListenersList implements HttpChannel.Listener
{
    private static Logger LOG = Log.getLogger(HttpChannelListenersList.class);
    private static HttpChannel.Listener NOOP = new HttpChannel.Listener() {};

    private final List<HttpChannel.Listener> _listeners;

    public HttpChannelListenersList(Collection<HttpChannel.Listener> listeners)
    {
        _listeners = new ArrayList<>(listeners);
    }

    @Override public void onRequestBegin(Request request)
    {
        notifyEvent1(listener -> listener::onRequestBegin, request);
    }

    @Override public void onBeforeDispatch(Request request)
    {
        notifyEvent1(listener -> listener::onBeforeDispatch, request);
    }

    @Override public void onDispatchFailure(Request request, Throwable failure)
    {
        notifyEvent2(listener -> listener::onDispatchFailure, request, failure);
    }

    @Override public void onAfterDispatch(Request request)
    {
        notifyEvent1(listener -> listener::onAfterDispatch, request);
    }

    @Override public void onRequestContent(Request request, ByteBuffer content)
    {
        notifyEvent2(listener -> listener::onRequestContent, request, content);
    }

    @Override public void onRequestContentEnd(Request request)
    {
        notifyEvent1(listener -> listener::onRequestContentEnd, request);
    }

    @Override public void onRequestTrailers(Request request)
    {
        notifyEvent1(listener -> listener::onRequestTrailers, request);
    }

    @Override public void onRequestEnd(Request request)
    {
        notifyEvent1(listener -> listener::onRequestEnd, request);
    }

    @Override public void onRequestFailure(Request request, Throwable failure)
    {
        notifyEvent2(listener -> listener::onRequestFailure, request, failure);
    }

    @Override public void onResponseBegin(Request request)
    {
        notifyEvent1(listener -> listener::onResponseBegin, request);
    }

    @Override public void onResponseCommit(Request request)
    {
        notifyEvent1(listener -> listener::onResponseCommit, request);
    }

    @Override public void onResponseContent(Request request, ByteBuffer content)
    {
        notifyEvent2(listener -> listener::onResponseContent, request, content);
    }

    @Override public void onResponseEnd(Request request)
    {
        notifyEvent1(listener -> listener::onResponseEnd, request);
    }

    @Override public void onResponseFailure(Request request, Throwable failure)
    {
        notifyEvent2(listener -> listener::onResponseFailure, request, failure);
    }

    @Override public void onComplete(Request request)
    {
        notifyEvent1(listener -> listener::onComplete, request);
    }

    private void notifyEvent1(Function<HttpChannel.Listener, Consumer<Request>> function, Request request)
    {
        for (HttpChannel.Listener listener : _listeners)
        {
            try
            {
                function.apply(listener).accept(request);
            }
            catch (Throwable x)
            {
                LOG.debug("Failure invoking listener " + listener, x);
            }
        }
    }

    private void notifyEvent2(Function<HttpChannel.Listener, BiConsumer<Request, ByteBuffer>> function, Request request, ByteBuffer content)
    {
        for (HttpChannel.Listener listener : _listeners)
        {
            ByteBuffer view = content.slice();
            try
            {
                function.apply(listener).accept(request, view);
            }
            catch (Throwable x)
            {
                LOG.debug("Failure invoking listener " + listener, x);
            }
        }
    }

    private void notifyEvent2(Function<HttpChannel.Listener, BiConsumer<Request, Throwable>> function, Request request, Throwable failure)
    {
        for (HttpChannel.Listener listener : _listeners)
        {
            try
            {
                function.apply(listener).accept(request, failure);
            }
            catch (Throwable x)
            {
                LOG.debug("Failure invoking listener " + listener, x);
            }
        }
    }
}
