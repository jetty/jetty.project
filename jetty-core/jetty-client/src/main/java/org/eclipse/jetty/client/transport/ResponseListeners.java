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
import java.util.Iterator;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseListeners
{
    private static final Logger LOG = LoggerFactory.getLogger(ResponseListeners.class);

    private Response.BeginListener beginListener;
    private Response.HeaderListener headerListener;
    private Response.HeadersListener headersListener;
    private Response.ContentSourceListener contentSourceListener;
    private Response.SuccessListener successListener;
    private Response.FailureListener failureListener;
    private Response.CompleteListener completeListener;

    public ResponseListeners()
    {
    }

    public ResponseListeners(Response.Listener listener)
    {
        beginListener = listener;
        headerListener = listener;
        headersListener = listener;
        contentSourceListener = listener;
        successListener = listener;
        failureListener = listener;
        completeListener = listener;
    }

    public ResponseListeners(ResponseListeners that)
    {
        beginListener = that.beginListener;
        headerListener = that.headerListener;
        headersListener = that.headersListener;
        contentSourceListener = that.contentSourceListener;
        successListener = that.successListener;
        failureListener = that.failureListener;
        completeListener = that.completeListener;
    }

    public void addBeginListener(Response.BeginListener listener)
    {
        Response.BeginListener existing = beginListener;
        beginListener = existing == null ? listener : response ->
        {
            notifyBegin(existing, response);
            notifyBegin(listener, response);
        };
    }

    public void notifyBegin(Response response)
    {
        notifyBegin(beginListener, response);
    }

    private static void notifyBegin(Response.BeginListener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onBegin(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addHeaderListener(Response.HeaderListener listener)
    {
        Response.HeaderListener existing = headerListener;
        headerListener = existing == null ? listener : (response, field) ->
        {
            boolean r1 = notifyHeader(existing, response, field);
            boolean r2 = notifyHeader(listener, response, field);
            return r1 && r2;
        };
    }

    public boolean notifyHeader(Response response, HttpField field)
    {
        return notifyHeader(headerListener, response, field);
    }

    private static boolean notifyHeader(Response.HeaderListener listener, Response response, HttpField field)
    {
        try
        {
            if (listener != null)
                return listener.onHeader(response, field);
            return true;
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
            return false;
        }
    }

    public void addHeadersListener(Response.HeadersListener listener)
    {
        Response.HeadersListener existing = headersListener;
        headersListener = existing == null ? listener : response ->
        {
            notifyHeaders(existing, response);
            notifyHeaders(listener, response);
        };
    }

    public void notifyHeaders(Response response)
    {
        notifyHeaders(headersListener, response);
    }

    private static void notifyHeaders(Response.HeadersListener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onHeaders(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addContentSourceListener(Response.ContentSourceListener listener)
    {
        Response.ContentSourceListener existing = contentSourceListener;
        contentSourceListener = existing == null ? listener : (response, contentSource) ->
        {
            notifyContentSource(existing, response, contentSource);
            notifyContentSource(listener, response, contentSource);
        };
    }

    public boolean hasContentSourceListeners()
    {
        return contentSourceListener != null;
    }

    public void notifyContentSource(Response response, Content.Source contentSource)
    {
        notifyContentSource(contentSourceListener, response, contentSource);
    }

    private static void notifyContentSource(Response.ContentSourceListener listener, Response response, Content.Source contentSource)
    {
        try
        {
            if (listener != null)
                listener.onContentSource(response, contentSource);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addSuccessListener(Response.SuccessListener listener)
    {
        Response.SuccessListener existing = successListener;
        successListener = existing == null ? listener : response ->
        {
            notifySuccess(existing, response);
            notifySuccess(listener, response);
        };
    }

    public void notifySuccess(Response response)
    {
        notifySuccess(successListener, response);
    }

    private static void notifySuccess(Response.SuccessListener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onSuccess(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addFailureListener(Response.FailureListener listener)
    {
        Response.FailureListener existing = failureListener;
        failureListener = existing == null ? listener : (response, failure) ->
        {
            notifyFailure(existing, response, failure);
            notifyFailure(listener, response, failure);
        };
    }

    public void notifyFailure(Response response, Throwable failure)
    {
        notifyFailure(failureListener, response, failure);
    }

    private static void notifyFailure(Response.FailureListener listener, Response response, Throwable failure)
    {
        try
        {
            if (listener != null)
                listener.onFailure(response, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addCompleteListener(Response.CompleteListener listener)
    {
        if (listener instanceof Response.BeginListener l)
            addBeginListener(l);
        if (listener instanceof Response.HeaderListener l)
            addHeaderListener(l);
        if (listener instanceof Response.HeadersListener l)
            addHeadersListener(l);
        if (listener instanceof Response.ContentSourceListener l)
            addContentSourceListener(l);
        if (listener instanceof Response.SuccessListener l)
            addSuccessListener(l);
        if (listener instanceof Response.FailureListener l)
            addFailureListener(l);
        Response.CompleteListener existing = completeListener;
        completeListener = existing == null ? listener : result ->
        {
            notifyComplete(existing, result);
            notifyComplete(listener, result);
        };
    }

    public void notifyComplete(Result result)
    {
        notifyComplete(completeListener, result);
    }

    private static void notifyComplete(Response.CompleteListener listener, Result result)
    {
        try
        {
            if (listener != null)
                listener.onComplete(result);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener {}", listener, x);
        }
    }

    public void addListener(Response.Listener listener)
    {
        addBeginListener(listener);
        addHeaderListener(listener);
        addHeadersListener(listener);
        addContentSourceListener(listener);
        addSuccessListener(listener);
        addFailureListener(listener);
        addCompleteListener(listener);
    }

    public void add(ResponseListeners listeners)
    {
        addBeginListener(listeners.beginListener);
        addHeaderListener(listeners.headerListener);
        addHeadersListener(listeners.headersListener);
        addContentSourceListener(listeners.contentSourceListener);
        addSuccessListener(listeners.successListener);
        addFailureListener(listeners.failureListener);
        addCompleteListener(listeners.completeListener);
    }

    public void emitEvents(Response response)
    {
        notifyBegin(beginListener, response);
        Iterator<HttpField> iterator = response.getHeaders().iterator();
        while (iterator.hasNext())
        {
            HttpField field = iterator.next();
            if (!notifyHeader(headerListener, response, field))
                iterator.remove();
        }
        notifyHeaders(headersListener, response);
        if (response instanceof ContentResponse contentResponse)
        {
            byte[] content = contentResponse.getContent();
            if (content != null && content.length > 0)
            {
                ByteBufferContentSource byteBufferContentSource = new ByteBufferContentSource(ByteBuffer.wrap(content));
                notifyContentSource(contentSourceListener, response, byteBufferContentSource);
            }
        }
    }

    public void emitSuccess(Response response)
    {
        emitEvents(response);
        notifySuccess(successListener, response);
    }

    public void emitFailure(Response response, Throwable failure)
    {
        emitEvents(response);
        notifyFailure(failureListener, response, failure);
    }

    public void emitSuccessComplete(Result result)
    {
        emitSuccess(result.getResponse());
        notifyComplete(completeListener, result);
    }

    public void emitFailureComplete(Result result)
    {
        emitFailure(result.getResponse(), result.getFailure());
        notifyComplete(completeListener, result);
    }
}
