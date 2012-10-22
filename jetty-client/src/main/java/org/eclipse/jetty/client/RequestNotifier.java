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
        for (Request.Listener listener : request.listeners())
            notifyQueued(listener, request);
        for (Request.Listener listener : client.getRequestListeners())
            notifyQueued(listener, request);
    }

    private void notifyQueued(Request.Listener listener, Request request)
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
        for (Request.Listener listener : request.listeners())
            notifyBegin(listener, request);
        for (Request.Listener listener : client.getRequestListeners())
            notifyBegin(listener, request);
    }

    private void notifyBegin(Request.Listener listener, Request request)
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
        for (Request.Listener listener : request.listeners())
            notifyHeaders(listener, request);
        for (Request.Listener listener : client.getRequestListeners())
            notifyHeaders(listener, request);
    }

    private void notifyHeaders(Request.Listener listener, Request request)
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
        for (Request.Listener listener : request.listeners())
            notifySuccess(listener, request);
        for (Request.Listener listener : client.getRequestListeners())
            notifySuccess(listener, request);
    }

    private void notifySuccess(Request.Listener listener, Request request)
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
        for (Request.Listener listener : request.listeners())
            notifyFailure(listener, request, failure);
        for (Request.Listener listener : client.getRequestListeners())
            notifyFailure(listener, request, failure);
    }

    private void notifyFailure(Request.Listener listener, Request request, Throwable failure)
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
