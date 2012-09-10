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

import java.nio.ByteBuffer;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ResponseNotifier
{
    private static final Logger LOG = Log.getLogger(ResponseNotifier.class);

    public void notifyBegin(Response.Listener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onBegin(response);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifyHeaders(Response.Listener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onHeaders(response);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifyContent(Response.Listener listener, Response response, ByteBuffer buffer)
    {
        try
        {
            if (listener != null)
                listener.onContent(response, buffer);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifySuccess(Response.Listener listener, Response response)
    {
        try
        {
            if (listener != null)
                listener.onSuccess(response);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifyFailure(Response.Listener listener, Response response, Throwable failure)
    {
        try
        {
            if (listener != null)
                listener.onFailure(response, failure);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    public void notifyComplete(Response.Listener listener, Result result)
    {
        try
        {
            if (listener != null)
                listener.onComplete(result);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }
}
