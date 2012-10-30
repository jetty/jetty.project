//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class RequestNotifier
{
    private static final Logger LOG = Log.getLogger(ResponseNotifier.class);

    private final HttpClient client;

    public RequestNotifier(HttpClient client)
    {
        this.client = client;
    }

    public void notifyQueued(Request request)
    {
        for (Request.QueuedListener listener : request.getListeners(Request.QueuedListener.class))
            notifyQueued(listener, request);
        for (Request.Listener listener : client.getRequestListeners())
            notifyQueued(listener, request);
    }

    private void notifyQueued(Request.QueuedListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onQueued(request);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifyBegin(Request request)
    {
        for (Request.BeginListener listener : request.getListeners(Request.BeginListener.class))
            notifyBegin(listener, request);
        for (Request.Listener listener : client.getRequestListeners())
            notifyBegin(listener, request);
    }

    private void notifyBegin(Request.BeginListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onBegin(request);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifyHeaders(Request request)
    {
        for (Request.HeadersListener listener : request.getListeners(Request.HeadersListener.class))
            notifyHeaders(listener, request);
        for (Request.Listener listener : client.getRequestListeners())
            notifyHeaders(listener, request);
    }

    private void notifyHeaders(Request.HeadersListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onHeaders(request);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifySuccess(Request request)
    {
        for (Request.SuccessListener listener : request.getListeners(Request.SuccessListener.class))
            notifySuccess(listener, request);
        for (Request.Listener listener : client.getRequestListeners())
            notifySuccess(listener, request);
    }

    private void notifySuccess(Request.SuccessListener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onSuccess(request);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifyFailure(Request request, Throwable failure)
    {
        for (Request.FailureListener listener : request.getListeners(Request.FailureListener.class))
            notifyFailure(listener, request, failure);
        for (Request.Listener listener : client.getRequestListeners())
            notifyFailure(listener, request, failure);
    }

    private void notifyFailure(Request.FailureListener listener, Request request, Throwable failure)
    {
        try
        {
            if (listener != null)
                listener.onFailure(request, failure);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }
}
