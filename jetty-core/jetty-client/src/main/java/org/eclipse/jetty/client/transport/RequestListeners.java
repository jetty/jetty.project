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

package org.eclipse.jetty.client.transport;

import java.nio.ByteBuffer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestListeners
{
    private static final Logger LOG = LoggerFactory.getLogger(RequestListeners.class);

    final HttpClient httpClient;
    Request.QueuedListener queuedListener;
    Request.BeginListener beginListener;
    Request.HeadersListener headersListener;
    Request.CommitListener commitListener;
    Request.ContentListener contentListener;
    Request.SuccessListener successListener;
    Request.FailureListener failureListener;

    public RequestListeners(HttpClient httpClient)
    {
        this.httpClient = httpClient;
    }

    public void addListener(Request.Listener listener)
    {
        addQueuedListener(listener);
        addBeginListener(listener);
        addHeadersListener(listener);
        addCommitListener(listener);
        addContentListener(listener);
        addSuccessListener(listener);
        addFailureListener(listener);
    }

    public void addQueuedListener(Request.QueuedListener listener)
    {
        Request.QueuedListener existing = queuedListener;
        queuedListener = existing == null ? listener : request ->
        {
            notifyQueued(existing, request);
            notifyQueued(listener, request);
        };
    }

    private static void notifyQueued(Request.QueuedListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onQueued(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addBeginListener(Request.BeginListener listener)
    {
        Request.BeginListener existing = beginListener;
        beginListener = existing == null ? listener : request ->
        {
            notifyBegin(existing, request);
            notifyBegin(listener, request);
        };
    }

    private static void notifyBegin(Request.BeginListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onBegin(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addHeadersListener(Request.HeadersListener listener)
    {
        Request.HeadersListener existing = headersListener;
        headersListener = existing == null ? listener : request ->
        {
            notifyHeaders(existing, request);
            notifyHeaders(listener, request);
        };
    }

    private static void notifyHeaders(Request.HeadersListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onHeaders(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addCommitListener(Request.CommitListener listener)
    {
        Request.CommitListener existing = commitListener;
        commitListener = existing == null ? listener : request ->
        {
            notifyCommit(existing, request);
            notifyCommit(listener, request);
        };
    }

    private static void notifyCommit(Request.CommitListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onCommit(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addContentListener(Request.ContentListener listener)
    {
        Request.ContentListener existing = contentListener;
        contentListener = existing == null ? listener : (request, byteBuffer) ->
        {
            notifyContent(existing, request, byteBuffer);
            notifyContent(listener, request, byteBuffer);
        };
    }

    private static void notifyContent(Request.ContentListener listener, Request request, ByteBuffer byteBuffer)
    {
        try
        {
            if (listener != null)
                listener.onContent(request, byteBuffer);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addSuccessListener(Request.SuccessListener listener)
    {
        Request.SuccessListener existing = successListener;
        successListener = existing == null ? listener : request ->
        {
            notifySuccess(existing, request);
            notifySuccess(listener, request);
        };
    }

    private static void notifySuccess(Request.SuccessListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onSuccess(request);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addFailureListener(Request.FailureListener listener)
    {
        Request.FailureListener existing = failureListener;
        failureListener = existing == null ? listener : (request, failure) ->
        {
            notifyFailure(existing, request, failure);
            notifyFailure(listener, request, failure);
        };
    }

    private static void notifyFailure(Request.FailureListener listener, Request request, Throwable failure)
    {
        try
        {
            if (listener != null)
                listener.onFailure(request, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void clear()
    {
        queuedListener = null;
        beginListener = null;
        headersListener = null;
        commitListener = null;
        contentListener = null;
        successListener = null;
        failureListener = null;
    }

    public static class Internal extends RequestListeners
    {
        public Internal(HttpClient httpClient)
        {
            super(httpClient);
        }

        public void notifyQueued(Request request)
        {
            RequestListeners.notifyQueued(queuedListener, request);
            RequestListeners.notifyQueued(httpClient.getRequestListeners().queuedListener, request);
        }

        public void notifyBegin(Request request)
        {
            RequestListeners.notifyBegin(beginListener, request);
            RequestListeners.notifyBegin(httpClient.getRequestListeners().beginListener, request);
        }

        public void notifyHeaders(Request request)
        {
            RequestListeners.notifyHeaders(headersListener, request);
            RequestListeners.notifyHeaders(httpClient.getRequestListeners().headersListener, request);
        }

        public void notifyCommit(Request request)
        {
            RequestListeners.notifyCommit(commitListener, request);
            RequestListeners.notifyCommit(httpClient.getRequestListeners().commitListener, request);
        }

        public void notifyContent(Request request, ByteBuffer byteBuffer)
        {
            RequestListeners.notifyContent(contentListener, request, byteBuffer);
            RequestListeners.notifyContent(httpClient.getRequestListeners().contentListener, request, byteBuffer);
        }

        public void notifySuccess(Request request)
        {
            RequestListeners.notifySuccess(successListener, request);
            RequestListeners.notifySuccess(httpClient.getRequestListeners().successListener, request);
        }

        public void notifyFailure(Request request, Throwable failure)
        {
            RequestListeners.notifyFailure(failureListener, request, failure);
            RequestListeners.notifyFailure(httpClient.getRequestListeners().failureListener, request, failure);
        }
    }
}
