//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ByteCounterChannelListener implements HttpChannel.Listener
{
    private static final Logger LOG = Log.getLogger(ByteCounterListener.class);
    private static final String ATTR_KEY = ByteCountEvent.class.getName();
    private final List<ByteCounterListener> listeners = new ArrayList<>();

    public void addListener(ByteCounterListener listener)
    {
        this.listeners.add(listener);
    }

    @Override
    public void onRequestBegin(Request request)
    {
        HttpConnection connection = getHttpConnection(request);
        if (connection != null)
        {
            HttpParser httpParser = connection.getParser();
            ByteCountEventAdaptor byteCountEventAdaptor = new ByteCountEventAdaptor(request);
            byteCountEventAdaptor.getRequestCount().onHeadersEnd(httpParser.getHeaderLength());
            byteCountEventAdaptor.getRequestCount().onBodyStart(connection.getBytesIn());
            request.setAttribute(ATTR_KEY, byteCountEventAdaptor);
        }
    }

    @Override
    public void onRequestEnd(Request request)
    {
        HttpConnection connection = getHttpConnection(request);
        ByteCountEventAdaptor byteCountEventAdaptor = (ByteCountEventAdaptor)request.getAttribute(ATTR_KEY);
        if ((connection != null) && (byteCountEventAdaptor != null))
        {
            HttpInput httpInput = request.getHttpInput();
            long byteCountRequestAPI = httpInput.getContentConsumed();
            byteCountEventAdaptor.getRequestCount().onBodyEnd(connection.getBytesIn(), byteCountRequestAPI);
            byteCountEventAdaptor.getRequestCount().onTrailerStart(connection.getBytesIn());
        }
    }

    @Override
    public void onRequestTrailers(Request request)
    {
        HttpConnection connection = getHttpConnection(request);
        ByteCountEventAdaptor byteCountEventAdaptor = (ByteCountEventAdaptor)request.getAttribute(ATTR_KEY);
        if ((connection != null) && (byteCountEventAdaptor != null))
        {
            byteCountEventAdaptor.getRequestCount().onTrailerEnd(connection.getBytesIn());
        }
    }

    @Override
    public void onRequestFailure(Request request, Throwable failure)
    {
        HttpConnection connection = getHttpConnection(request);
        ByteCountEventAdaptor byteCountEventAdaptor = (ByteCountEventAdaptor)request.getAttribute(ATTR_KEY);
        if ((connection != null) && (byteCountEventAdaptor != null))
        {
            byteCountEventAdaptor.getRequestCount().onFailure(connection.getBytesIn(), failure);
        }
    }

    // TODO: investigate Http Dispatch Failure too.

    @Override
    public void onResponseBegin(Request request)
    {
        HttpConnection connection = getHttpConnection(request);
        ByteCountEventAdaptor byteCountEventAdaptor = (ByteCountEventAdaptor)request.getAttribute(ATTR_KEY);
        if ((connection != null) && (byteCountEventAdaptor != null))
        {
            byteCountEventAdaptor.getResponseCount().onHeadersStart(connection.getBytesOut());
        }
    }

    @Override
    public void onResponseCommit(Request request)
    {
        HttpConnection connection = getHttpConnection(request);
        ByteCountEventAdaptor byteCountEventAdaptor = (ByteCountEventAdaptor)request.getAttribute(ATTR_KEY);
        if ((connection != null) && (byteCountEventAdaptor != null))
        {
            byteCountEventAdaptor.getResponseCount().onHeadersEnd(connection.getBytesOut());
            byteCountEventAdaptor.getResponseCount().onBodyStart(connection.getBytesOut());
        }
    }

    @Override
    public void onResponseEnd(Request request)
    {
        HttpConnection connection = getHttpConnection(request);
        ByteCountEventAdaptor byteCountEventAdaptor = (ByteCountEventAdaptor)request.getAttribute(ATTR_KEY);
        if ((connection != null) && (byteCountEventAdaptor != null))
        {
            Response response = request.getResponse();
            HttpOutput httpOutput = response.getHttpOutput();
            long byteCountResponseAPI = httpOutput.getWritten();
            byteCountEventAdaptor.getResponseCount().onBodyEnd(connection.getBytesOut(), byteCountResponseAPI);
        }
    }

    // TODO: need Response Trailers?

    @Override
    public void onResponseFailure(Request request, Throwable failure)
    {
        HttpConnection connection = getHttpConnection(request);
        ByteCountEventAdaptor byteCountEventAdaptor = (ByteCountEventAdaptor)request.getAttribute(ATTR_KEY);
        if ((connection != null) && (byteCountEventAdaptor != null))
        {
            byteCountEventAdaptor.getResponseCount().onFailure(connection.getBytesOut(), failure);
        }
    }

    @Override
    public void onComplete(Request request)
    {
        HttpConnection connection = getHttpConnection(request);
        ByteCountEventAdaptor byteCountEventAdaptor = (ByteCountEventAdaptor)request.getAttribute(ATTR_KEY);
        if ((connection != null) && (byteCountEventAdaptor != null))
        {
            byteCountEventAdaptor.onComplete(connection.getBytesIn(), connection.getBytesOut());
            notifyByteCount(byteCountEventAdaptor);
        }
    }

    private void notifyByteCount(ByteCountEvent byteCountEvent)
    {
        for (ByteCounterListener listener : listeners)
        {
            try
            {
                listener.onByteCount(byteCountEvent);
            }
            catch (Throwable cause)
            {
                LOG.warn("Unable to notify onByteCount", cause);
            }
        }
    }

    private HttpConnection getHttpConnection(Request request)
    {
        return (HttpConnection)request.getAttribute("org.eclipse.jetty.server.HttpConnection");
    }
}
