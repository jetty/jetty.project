//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.function.LongConsumer;
import java.util.function.ObjLongConsumer;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CountingCallback;
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

    public void notifyBeforeContent(Response response, ObjLongConsumer<Object> demand, List<Response.DemandedContentListener> contentListeners)
    {
        for (Response.DemandedContentListener listener : contentListeners)
        {
            notifyBeforeContent(listener, response, d -> demand.accept(listener, d));
        }
    }

    private void notifyBeforeContent(Response.DemandedContentListener listener, Response response, LongConsumer demand)
    {
        try
        {
            listener.onBeforeContent(response, demand);
        }
        catch (Throwable x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifyContent(Response response, ObjLongConsumer<Object> demand, ByteBuffer buffer, Callback callback, List<Response.DemandedContentListener> contentListeners)
    {
        int count = contentListeners.size();
        if (count == 0)
        {
            callback.succeeded();
            demand.accept(null, 1);
        }
        else if (count == 1)
        {
            Response.DemandedContentListener listener = contentListeners.get(0);
            notifyContent(listener, response, d -> demand.accept(listener, d), buffer.slice(), callback);
        }
        else
        {
            callback = new CountingCallback(callback, count);
            for (Response.DemandedContentListener listener : contentListeners)
            {
                notifyContent(listener, response, d -> demand.accept(listener, d), buffer.slice(), callback);
            }
        }
    }

    private void notifyContent(Response.DemandedContentListener listener, Response response, LongConsumer demand, ByteBuffer buffer, Callback callback)
    {
        try
        {
            listener.onContent(response, demand, buffer, callback);
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
        forwardEvents(listeners, response);
        notifySuccess(listeners, response);
    }

    public void forwardSuccessComplete(List<Response.ResponseListener> listeners, Request request, Response response)
    {
        forwardSuccess(listeners, response);
        notifyComplete(listeners, new Result(request, response));
    }

    public void forwardFailure(List<Response.ResponseListener> listeners, Response response, Throwable failure)
    {
        forwardEvents(listeners, response);
        notifyFailure(listeners, response, failure);
    }

    private void forwardEvents(List<Response.ResponseListener> listeners, Response response)
    {
        notifyBegin(listeners, response);
        Iterator<HttpField> iterator = response.getHeaders().iterator();
        while (iterator.hasNext())
        {
            HttpField field = iterator.next();
            if (!notifyHeader(listeners, response, field))
                iterator.remove();
        }
        notifyHeaders(listeners, response);
        if (response instanceof ContentResponse)
        {
            byte[] content = ((ContentResponse)response).getContent();
            if (content != null && content.length > 0)
            {
                List<Response.DemandedContentListener> contentListeners = listeners.stream()
                    .filter(Response.DemandedContentListener.class::isInstance)
                    .map(Response.DemandedContentListener.class::cast)
                    .collect(Collectors.toList());
                ObjLongConsumer<Object> demand = (context, value) -> {};
                notifyBeforeContent(response, demand, contentListeners);
                notifyContent(response, demand, ByteBuffer.wrap(content), Callback.NOOP, contentListeners);
            }
        }
    }

    public void forwardFailureComplete(List<Response.ResponseListener> listeners, Request request, Throwable requestFailure, Response response, Throwable responseFailure)
    {
        forwardFailure(listeners, response, responseFailure);
        notifyComplete(listeners, new Result(request, requestFailure, response, responseFailure));
    }
}
