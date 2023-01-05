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

package org.eclipse.jetty.client.internal;

import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestNotifier
{
    private static final Logger LOG = LoggerFactory.getLogger(ResponseNotifier.class);

    private final HttpClient client;

    public RequestNotifier(HttpClient client)
    {
        this.client = client;
    }

    public void notifyQueued(Request request)
    {
        // Optimized to avoid allocations of iterator instances
        List<Request.RequestListener> requestListeners = request.getRequestListeners(null);
        for (int i = 0; i < requestListeners.size(); ++i)
        {
            Request.RequestListener listener = requestListeners.get(i);
            if (listener instanceof Request.QueuedListener)
                notifyQueued((Request.QueuedListener)listener, request);
        }
        List<Request.Listener> listeners = client.getRequestListeners();
        for (int i = 0; i < listeners.size(); ++i)
        {
            Request.Listener listener = listeners.get(i);
            notifyQueued(listener, request);
        }
    }

    private void notifyQueued(Request.QueuedListener listener, Request request)
    {
        try
        {
            listener.onQueued(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void notifyBegin(Request request)
    {
        // Optimized to avoid allocations of iterator instances
        List<Request.RequestListener> requestListeners = request.getRequestListeners(null);
        for (int i = 0; i < requestListeners.size(); ++i)
        {
            Request.RequestListener listener = requestListeners.get(i);
            if (listener instanceof Request.BeginListener)
                notifyBegin((Request.BeginListener)listener, request);
        }
        List<Request.Listener> listeners = client.getRequestListeners();
        for (int i = 0; i < listeners.size(); ++i)
        {
            Request.Listener listener = listeners.get(i);
            notifyBegin(listener, request);
        }
    }

    private void notifyBegin(Request.BeginListener listener, Request request)
    {
        try
        {
            listener.onBegin(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void notifyHeaders(Request request)
    {
        // Optimized to avoid allocations of iterator instances
        List<Request.RequestListener> requestListeners = request.getRequestListeners(null);
        for (int i = 0; i < requestListeners.size(); ++i)
        {
            Request.RequestListener listener = requestListeners.get(i);
            if (listener instanceof Request.HeadersListener)
                notifyHeaders((Request.HeadersListener)listener, request);
        }
        List<Request.Listener> listeners = client.getRequestListeners();
        for (int i = 0; i < listeners.size(); ++i)
        {
            Request.Listener listener = listeners.get(i);
            notifyHeaders(listener, request);
        }
    }

    private void notifyHeaders(Request.HeadersListener listener, Request request)
    {
        try
        {
            listener.onHeaders(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void notifyCommit(Request request)
    {
        // Optimized to avoid allocations of iterator instances
        List<Request.RequestListener> requestListeners = request.getRequestListeners(null);
        for (int i = 0; i < requestListeners.size(); ++i)
        {
            Request.RequestListener listener = requestListeners.get(i);
            if (listener instanceof Request.CommitListener)
                notifyCommit((Request.CommitListener)listener, request);
        }
        List<Request.Listener> listeners = client.getRequestListeners();
        for (int i = 0; i < listeners.size(); ++i)
        {
            Request.Listener listener = listeners.get(i);
            notifyCommit(listener, request);
        }
    }

    private void notifyCommit(Request.CommitListener listener, Request request)
    {
        try
        {
            listener.onCommit(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void notifyContent(Request request, ByteBuffer content)
    {
        if (!content.hasRemaining())
            return;
        // Slice the buffer to avoid that listeners peek into data they should not look at.
        content = content.slice();
        // Optimized to avoid allocations of iterator instances.
        List<Request.RequestListener> requestListeners = request.getRequestListeners(null);
        for (int i = 0; i < requestListeners.size(); ++i)
        {
            Request.RequestListener listener = requestListeners.get(i);
            if (listener instanceof Request.ContentListener)
            {
                // The buffer was sliced, so we always clear it (position=0, limit=capacity)
                // before passing it to the listener that may consume it.
                content.clear();
                notifyContent((Request.ContentListener)listener, request, content);
            }
        }
        List<Request.Listener> listeners = client.getRequestListeners();
        for (int i = 0; i < listeners.size(); ++i)
        {
            Request.Listener listener = listeners.get(i);
            // The buffer was sliced, so we always clear it (position=0, limit=capacity)
            // before passing it to the listener that may consume it.
            content.clear();
            notifyContent(listener, request, content);
        }
    }

    private void notifyContent(Request.ContentListener listener, Request request, ByteBuffer content)
    {
        try
        {
            listener.onContent(request, content);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void notifySuccess(Request request)
    {
        // Optimized to avoid allocations of iterator instances
        List<Request.RequestListener> requestListeners = request.getRequestListeners(null);
        for (int i = 0; i < requestListeners.size(); ++i)
        {
            Request.RequestListener listener = requestListeners.get(i);
            if (listener instanceof Request.SuccessListener)
                notifySuccess((Request.SuccessListener)listener, request);
        }
        List<Request.Listener> listeners = client.getRequestListeners();
        for (int i = 0; i < listeners.size(); ++i)
        {
            Request.Listener listener = listeners.get(i);
            notifySuccess(listener, request);
        }
    }

    private void notifySuccess(Request.SuccessListener listener, Request request)
    {
        try
        {
            listener.onSuccess(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void notifyFailure(Request request, Throwable failure)
    {
        // Optimized to avoid allocations of iterator instances
        List<Request.RequestListener> requestListeners = request.getRequestListeners(null);
        for (int i = 0; i < requestListeners.size(); ++i)
        {
            Request.RequestListener listener = requestListeners.get(i);
            if (listener instanceof Request.FailureListener)
                notifyFailure((Request.FailureListener)listener, request, failure);
        }
        List<Request.Listener> listeners = client.getRequestListeners();
        for (int i = 0; i < listeners.size(); ++i)
        {
            Request.Listener listener = listeners.get(i);
            notifyFailure(listener, request, failure);
        }
    }

    private void notifyFailure(Request.FailureListener listener, Request request, Throwable failure)
    {
        try
        {
            listener.onFailure(request, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }
}
