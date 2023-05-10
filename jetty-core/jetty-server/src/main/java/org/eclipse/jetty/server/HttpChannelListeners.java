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

import java.nio.ByteBuffer;
import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A HttpChannel.Listener that holds a collection of
 * other HttpChannel.Listener instances that are efficiently
 * invoked without iteration.
 * @see AbstractConnector
 */
public class HttpChannelListeners implements HttpChannel.Listener
{
    static final Logger LOG = LoggerFactory.getLogger(HttpChannelListeners.class);
    public static HttpChannel.Listener NOOP = new HttpChannel.Listener() {};

    private final NotifyRequest onRequestBegin;
    private final NotifyRequest onBeforeDispatch;
    private final NotifyFailure onDispatchFailure;
    private final NotifyRequest onAfterDispatch;
    private final NotifyContent onRequestContent;
    private final NotifyRequest onRequestContentEnd;
    private final NotifyRequest onRequestTrailers;
    private final NotifyRequest onRequestEnd;
    private final NotifyFailure onRequestFailure;
    private final NotifyRequest onResponseBegin;
    private final NotifyRequest onResponseCommit;
    private final NotifyContent onResponseContent;
    private final NotifyRequest onResponseEnd;
    private final NotifyFailure onResponseFailure;
    private final NotifyRequest onComplete;

    public HttpChannelListeners(Collection<HttpChannel.Listener> listeners)
    {
        try
        {
            NotifyRequest onRequestBegin = NotifyRequest.NOOP;
            NotifyRequest onBeforeDispatch = NotifyRequest.NOOP;
            NotifyFailure onDispatchFailure = NotifyFailure.NOOP;
            NotifyRequest onAfterDispatch = NotifyRequest.NOOP;
            NotifyContent onRequestContent = NotifyContent.NOOP;
            NotifyRequest onRequestContentEnd = NotifyRequest.NOOP;
            NotifyRequest onRequestTrailers = NotifyRequest.NOOP;
            NotifyRequest onRequestEnd = NotifyRequest.NOOP;
            NotifyFailure onRequestFailure = NotifyFailure.NOOP;
            NotifyRequest onResponseBegin = NotifyRequest.NOOP;
            NotifyRequest onResponseCommit = NotifyRequest.NOOP;
            NotifyContent onResponseContent = NotifyContent.NOOP;
            NotifyRequest onResponseEnd = NotifyRequest.NOOP;
            NotifyFailure onResponseFailure = NotifyFailure.NOOP;
            NotifyRequest onComplete = NotifyRequest.NOOP;

            for (HttpChannel.Listener listener : listeners)
            {
                if (!listener.getClass().getMethod("onRequestBegin", Request.class, Response.class).isDefault())
                    onRequestBegin = combine(onRequestBegin, listener::onRequestBegin);
                if (!listener.getClass().getMethod("onBeforeDispatch", Request.class, Response.class).isDefault())
                    onBeforeDispatch = combine(onBeforeDispatch, listener::onBeforeDispatch);
                if (!listener.getClass().getMethod("onDispatchFailure", Request.class, Response.class, Throwable.class).isDefault())
                    onDispatchFailure = combine(onDispatchFailure, listener::onDispatchFailure);
                if (!listener.getClass().getMethod("onAfterDispatch", Request.class, Response.class).isDefault())
                    onAfterDispatch = combine(onAfterDispatch, listener::onAfterDispatch);
                if (!listener.getClass().getMethod("onRequestContent", Request.class, Response.class, ByteBuffer.class).isDefault())
                    onRequestContent = combine(onRequestContent, listener::onRequestContent);
                if (!listener.getClass().getMethod("onRequestContentEnd", Request.class, Response.class).isDefault())
                    onRequestContentEnd = combine(onRequestContentEnd, listener::onRequestContentEnd);
                if (!listener.getClass().getMethod("onRequestTrailers", Request.class, Response.class).isDefault())
                    onRequestTrailers = combine(onRequestTrailers, listener::onRequestTrailers);
                if (!listener.getClass().getMethod("onRequestEnd", Request.class, Response.class).isDefault())
                    onRequestEnd = combine(onRequestEnd, listener::onRequestEnd);
                if (!listener.getClass().getMethod("onRequestFailure", Request.class, Response.class, Throwable.class).isDefault())
                    onRequestFailure = combine(onRequestFailure, listener::onRequestFailure);
                if (!listener.getClass().getMethod("onResponseBegin", Request.class, Response.class).isDefault())
                    onResponseBegin = combine(onResponseBegin, listener::onResponseBegin);
                if (!listener.getClass().getMethod("onResponseCommit", Request.class, Response.class).isDefault())
                    onResponseCommit = combine(onResponseCommit, listener::onResponseCommit);
                if (!listener.getClass().getMethod("onResponseContent", Request.class, Response.class, ByteBuffer.class).isDefault())
                    onResponseContent = combine(onResponseContent, listener::onResponseContent);
                if (!listener.getClass().getMethod("onResponseEnd", Request.class, Response.class).isDefault())
                    onResponseEnd = combine(onResponseEnd, listener::onResponseEnd);
                if (!listener.getClass().getMethod("onResponseFailure", Request.class, Response.class, Throwable.class).isDefault())
                    onResponseFailure = combine(onResponseFailure, listener::onResponseFailure);
                if (!listener.getClass().getMethod("onComplete", Request.class, Response.class).isDefault())
                    onComplete = combine(onComplete, listener::onComplete);
            }

            this.onRequestBegin = onRequestBegin;
            this.onBeforeDispatch = onBeforeDispatch;
            this.onDispatchFailure = onDispatchFailure;
            this.onAfterDispatch = onAfterDispatch;
            this.onRequestContent = onRequestContent;
            this.onRequestContentEnd = onRequestContentEnd;
            this.onRequestTrailers = onRequestTrailers;
            this.onRequestEnd = onRequestEnd;
            this.onRequestFailure = onRequestFailure;
            this.onResponseBegin = onResponseBegin;
            this.onResponseCommit = onResponseCommit;
            this.onResponseContent = onResponseContent;
            this.onResponseEnd = onResponseEnd;
            this.onResponseFailure = onResponseFailure;
            this.onComplete = onComplete;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onRequestBegin(Request request, Response response)
    {
        onRequestBegin.onRequest(request, response);
    }

    @Override
    public void onBeforeDispatch(Request request, Response response)
    {
        onBeforeDispatch.onRequest(request, response);
    }

    @Override
    public void onDispatchFailure(Request request, Response response, Throwable failure)
    {
        onDispatchFailure.onFailure(request, response, failure);
    }

    @Override
    public void onAfterDispatch(Request request, Response response)
    {
        onAfterDispatch.onRequest(request, response);
    }

    @Override
    public void onRequestContent(Request request, Response response, ByteBuffer content)
    {
        onRequestContent.onContent(request, response, content);
    }

    @Override
    public void onRequestContentEnd(Request request, Response response)
    {
        onRequestContentEnd.onRequest(request, response);
    }

    @Override
    public void onRequestTrailers(Request request, Response response)
    {
        onRequestTrailers.onRequest(request, response);
    }

    @Override
    public void onRequestEnd(Request request, Response response)
    {
        onRequestEnd.onRequest(request, response);
    }

    @Override
    public void onRequestFailure(Request request, Response response, Throwable failure)
    {
        onRequestFailure.onFailure(request, response, failure);
    }

    @Override
    public void onResponseBegin(Request request, Response response)
    {
        onResponseBegin.onRequest(request, response);
    }

    @Override
    public void onResponseCommit(Request request, Response response)
    {
        onResponseCommit.onRequest(request, response);
    }

    @Override
    public void onResponseContent(Request request, Response response, ByteBuffer content)
    {
        onResponseContent.onContent(request, response, content);
    }

    @Override
    public void onResponseEnd(Request request, Response response)
    {
        onResponseEnd.onRequest(request, response);
    }

    @Override
    public void onResponseFailure(Request request, Response response, Throwable failure)
    {
        onResponseFailure.onFailure(request, response, failure);
    }

    @Override
    public void onComplete(Request request, Response response)
    {
        onComplete.onRequest(request, response);
    }

    private interface NotifyRequest
    {
        void onRequest(Request request, Response response);

        NotifyRequest NOOP = (request, response) ->
        {
        };
    }

    private interface NotifyFailure
    {
        void onFailure(Request request, Response response, Throwable failure);

        NotifyFailure NOOP = (request, response, failure) ->
        {
        };
    }

    private interface NotifyContent
    {
        void onContent(Request request, Response response, ByteBuffer content);

        NotifyContent NOOP = (request, response, content) ->
        {
        };
    }

    private static NotifyRequest combine(NotifyRequest first, NotifyRequest second)
    {
        if (first == NotifyRequest.NOOP)
            return second;
        if (second == NotifyRequest.NOOP)
            return first;
        return (request, response) ->
        {
            first.onRequest(request, response);
            second.onRequest(request, response);
        };
    }

    private static NotifyFailure combine(NotifyFailure first, NotifyFailure second)
    {
        if (first == NotifyFailure.NOOP)
            return second;
        if (second == NotifyFailure.NOOP)
            return first;
        return (request, response, throwable) ->
        {
            first.onFailure(request, response, throwable);
            second.onFailure(request, response, throwable);
        };
    }

    private static NotifyContent combine(NotifyContent first, NotifyContent second)
    {
        if (first == NotifyContent.NOOP)
            return (request, response, content) -> second.onContent(request, response, content.slice());
        if (second == NotifyContent.NOOP)
            return (request, response, content) -> first.onContent(request, response, content.slice());
        return (request, response, content) ->
        {
            content = content.slice();
            first.onContent(request, response, content);
            second.onContent(request, response, content);
        };
    }
}
