//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ResponseNotifier
{
    private static final Logger LOG = Log.getLogger(ResponseNotifier.class);

    public void notifyBegin(List<Response.ResponseListener> listeners, Response response)
    {
        // Optimized to avoid allocations of iterator instances
        for (int i = 0; i < listeners.size(); ++i)
        {
            Response.ResponseListener listener = listeners.get(i);
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
        // Optimized to avoid allocations of iterator instances
        for (int i = 0; i < listeners.size(); ++i)
        {
            Response.ResponseListener listener = listeners.get(i);
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
        // Optimized to avoid allocations of iterator instances
        for (int i = 0; i < listeners.size(); ++i)
        {
            Response.ResponseListener listener = listeners.get(i);
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
        // Here we use an IteratingNestedCallback not to avoid the stack overflow, but to
        // invoke the listeners one after the other. When all of them have invoked the
        // callback they got passed, the callback passed to this method is finally invoked.
        ContentCallback contentCallback = new ContentCallback(listeners, response, buffer, callback);
        contentCallback.iterate();
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
        // Optimized to avoid allocations of iterator instances
        for (int i = 0; i < listeners.size(); ++i)
        {
            Response.ResponseListener listener = listeners.get(i);
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
        // Optimized to avoid allocations of iterator instances
        for (int i = 0; i < listeners.size(); ++i)
        {
            Response.ResponseListener listener = listeners.get(i);
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
        // Optimized to avoid allocations of iterator instances
        for (int i = 0; i < listeners.size(); ++i)
        {
            Response.ResponseListener listener = listeners.get(i);
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

    private class ContentCallback extends IteratingNestedCallback
    {
        private final List<Response.ResponseListener> listeners;
        private final Response response;
        private final ByteBuffer buffer;
        private int index;

        private ContentCallback(List<Response.ResponseListener> listeners, Response response, ByteBuffer buffer, Callback callback)
        {
            super(callback);
            this.listeners = listeners;
            this.response = response;
            // Slice the buffer to avoid that listeners peek into data they should not look at.
            this.buffer = buffer.slice();
        }

        @Override
        protected Action process() throws Exception
        {
            if (index == listeners.size())
                return Action.SUCCEEDED;

            Response.ResponseListener listener = listeners.get(index);
            if (listener instanceof Response.AsyncContentListener)
            {
                // The buffer was sliced, so we always clear it
                // (clear => position=0, limit=capacity) before
                // passing it to the listener that may consume it.
                buffer.clear();
                ResponseNotifier.this.notifyContent((Response.AsyncContentListener)listener, response, buffer, this);
                return Action.SCHEDULED;
            }
            else
            {
                succeeded();
                return Action.SCHEDULED;
            }
        }

        @Override
        public void succeeded()
        {
            ++index;
            super.succeeded();
        }
    }
}
