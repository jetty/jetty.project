//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CountingCallback;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ResponseNotifier
{
    private static final Logger LOG = Log.getLogger(ResponseNotifier.class);

    public void notifyBegin(List<Response.ResponseListener> listeners, Response response)
    {
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.BeginListener)
                notifyBegin((Response.BeginListener)listener, response);
        }
    }

    private void notifyBegin(Response.BeginListener listener, Response response)
    {
        try
        {
            listener.onBegin(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public boolean notifyHeader(List<Response.ResponseListener> listeners, Response response, HttpField field)
    {
        boolean result = true;
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.HeaderListener)
                result &= notifyHeader((Response.HeaderListener)listener, response, field);
        }
        return result;
    }

    private boolean notifyHeader(Response.HeaderListener listener, Response response, HttpField field)
    {
        try
        {
            return listener.onHeader(response, field);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
            return false;
        }
    }

    public void notifyHeaders(List<Response.ResponseListener> listeners, Response response)
    {
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.HeadersListener)
                notifyHeaders((Response.HeadersListener)listener, response);
        }
    }

    private void notifyHeaders(Response.HeadersListener listener, Response response)
    {
        try
        {
            listener.onHeaders(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifyContent(List<Response.ResponseListener> listeners, Response response, ByteBuffer buffer, Callback callback)
    {
        List<Response.AsyncContentListener> contentListeners = listeners.stream()
                .filter(Response.AsyncContentListener.class::isInstance)
                .map(Response.AsyncContentListener.class::cast)
                .collect(Collectors.toList());
        notifyContent(response, buffer, callback, contentListeners);
    }

    public void notifyContent(Response response, ByteBuffer buffer, Callback callback, List<Response.AsyncContentListener> contentListeners)
    {
        if (contentListeners.isEmpty())
        {
            callback.succeeded();
        }
        else
        {
            CountingCallback counter = new CountingCallback(callback, contentListeners.size());
            Retainable retainable = callback instanceof Retainable ? (Retainable)callback : null;
            for (Response.AsyncContentListener listener : contentListeners)
            {
                if (retainable != null)
                    retainable.retain();
                notifyContent(listener, response, buffer.slice(), counter);
            }
        }
    }

    private void notifyContent(Response.AsyncContentListener listener, Response response, ByteBuffer buffer, Callback callback)
    {
        try
        {
            listener.onContent(response, buffer, callback);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifySuccess(List<Response.ResponseListener> listeners, Response response)
    {
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.SuccessListener)
                notifySuccess((Response.SuccessListener)listener, response);
        }
    }

    private void notifySuccess(Response.SuccessListener listener, Response response)
    {
        try
        {
            listener.onSuccess(response);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifyFailure(List<Response.ResponseListener> listeners, Response response, Throwable failure)
    {
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.FailureListener)
                notifyFailure((Response.FailureListener)listener, response, failure);
        }
    }

    private void notifyFailure(Response.FailureListener listener, Response response, Throwable failure)
    {
        try
        {
            listener.onFailure(response, failure);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifyComplete(List<Response.ResponseListener> listeners, Result result)
    {
        for (Response.ResponseListener listener : listeners)
        {
            if (listener instanceof Response.CompleteListener)
                notifyComplete((Response.CompleteListener)listener, result);
        }
    }

    private void notifyComplete(Response.CompleteListener listener, Result result)
    {
        try
        {
            listener.onComplete(result);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void forwardSuccess(List<Response.ResponseListener> listeners, Response response)
    {
        notifyBegin(listeners, response);
        for (Iterator<HttpField> iterator = response.getHeaders().iterator(); iterator.hasNext();)
        {
            HttpField field = iterator.next();
            if (!notifyHeader(listeners, response, field))
                iterator.remove();
        }
        notifyHeaders(listeners, response);
        if (response instanceof ContentResponse)
            notifyContent(listeners, response, ByteBuffer.wrap(((ContentResponse)response).getContent()), Callback.NOOP);
        notifySuccess(listeners, response);
    }

    public void forwardSuccessComplete(List<Response.ResponseListener> listeners, Request request, Response response)
    {
        forwardSuccess(listeners, response);
        notifyComplete(listeners, new Result(request, response));
    }

    public void forwardFailure(List<Response.ResponseListener> listeners, Response response, Throwable failure)
    {
        notifyBegin(listeners, response);
        for (Iterator<HttpField> iterator = response.getHeaders().iterator(); iterator.hasNext();)
        {
            HttpField field = iterator.next();
            if (!notifyHeader(listeners, response, field))
                iterator.remove();
        }
        notifyHeaders(listeners, response);
        if (response instanceof ContentResponse)
            notifyContent(listeners, response, ByteBuffer.wrap(((ContentResponse)response).getContent()), Callback.NOOP);
        notifyFailure(listeners, response, failure);
    }

    public void forwardFailureComplete(List<Response.ResponseListener> listeners, Request request, Throwable requestFailure, Response response, Throwable responseFailure)
    {
        forwardFailure(listeners, response, responseFailure);
        notifyComplete(listeners, new Result(request, requestFailure, response, responseFailure));
    }
}
