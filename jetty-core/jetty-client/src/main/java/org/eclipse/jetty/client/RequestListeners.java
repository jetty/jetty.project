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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A specialized container for request listeners.</p>
 */
public class RequestListeners implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(RequestListeners.class);

    private Request.QueuedListener queuedListener;
    private Request.BeginListener beginListener;
    private Request.HeadersListener headersListener;
    private Request.CommitListener commitListener;
    private Request.ContentListener contentListener;
    private Request.SuccessListener successListener;
    private Request.FailureListener failureListener;

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

    protected static void notifyQueued(Request.QueuedListener listener, Request request)
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

    protected static void notifyBegin(Request.BeginListener listener, Request request)
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

    protected static void notifyHeaders(Request.HeadersListener listener, Request request)
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

    protected static void notifyCommit(Request.CommitListener listener, Request request)
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

    protected static void notifyContent(Request.ContentListener listener, Request request, ByteBuffer byteBuffer)
    {
        try
        {
            if (listener != null)
            {
                // The same ByteBuffer instance may be passed to multiple listeners
                // that may modify the position and limit, so clear it every time.
                byteBuffer.clear();
                listener.onContent(request, byteBuffer);
            }
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

    protected static void notifySuccess(Request.SuccessListener listener, Request request)
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

    protected static void notifyFailure(Request.FailureListener listener, Request request, Throwable failure)
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

    protected Request.QueuedListener getQueuedListener()
    {
        return queuedListener;
    }

    protected Request.BeginListener getBeginListener()
    {
        return beginListener;
    }

    protected Request.HeadersListener getHeadersListener()
    {
        return headersListener;
    }

    protected Request.CommitListener getCommitListener()
    {
        return commitListener;
    }

    protected Request.ContentListener getContentListener()
    {
        return contentListener;
    }

    protected Request.SuccessListener getSuccessListener()
    {
        return successListener;
    }

    protected Request.FailureListener getFailureListener()
    {
        return failureListener;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            new ListenerDump("queued", getQueuedListener()),
            new ListenerDump("begin", getBeginListener()),
            new ListenerDump("headers", getHeadersListener()),
            new ListenerDump("commit", getCommitListener()),
            new ListenerDump("content", getContentListener()),
            new ListenerDump("success", getSuccessListener()),
            new ListenerDump("failure", getFailureListener())
        );
    }

    private record ListenerDump(String name, Object listener)
    {
        @Override
        public String toString()
        {
            return name + " = " + listener;
        }
    }
}
