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

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpExchange
{
    private static final Logger LOG = Log.getLogger(HttpExchange.class);

    private final AtomicInteger complete = new AtomicInteger();
    private final HttpConversation conversation;
    private final HttpConnection connection;
    private final Request request;
    private final Response.Listener listener;
    private final HttpResponse response;

    public HttpExchange(HttpConversation conversation, HttpConnection connection, Request request, Response.Listener listener)
    {
        this.conversation = conversation;
        this.connection = connection;
        this.request = request;
        this.listener = listener;
        this.response = new HttpResponse(request, listener);
    }

    public HttpConversation conversation()
    {
        return conversation;
    }

    public Request request()
    {
        return request;
    }

    public Response.Listener listener()
    {
        return listener;
    }

    public HttpResponse response()
    {
        return response;
    }

    public void receive()
    {
        connection.receive();
    }

    public boolean requestComplete(boolean success)
    {
        int requestSuccess = 0b0011;
        int requestFailure = 0b0001;
        return complete(success ? requestSuccess : requestFailure);
    }

    public boolean responseComplete(boolean success)
    {
        int responseSuccess = 0b1100;
        int responseFailure = 0b0100;
        return complete(success ? responseSuccess : responseFailure);
    }

    /**
     * This method needs to atomically compute whether this exchange is completed,
     * that is both request and responses are completed (either with a success or
     * a failure).
     *
     * Furthermore, this method needs to atomically compute whether the exchange
     * has completed successfully (both request and response are successful) or not.
     *
     * To do this, we use 2 bits for the request (one to indicate completion, one
     * to indicate success), and similarly for the response.
     * By using {@link AtomicInteger} to atomically sum these codes we can know
     * whether the exchange is completed and whether is successful.
     *
     * @param code the bits representing the status code for either the request or the response
     * @return whether the exchange completed (either successfully or not)
     */
    private boolean complete(int code)
    {
        int status = complete.addAndGet(code);
        int completed = 0b0101;
        if ((status & completed) == completed)
        {
            LOG.debug("{} complete", this);
            // Request and response completed
            if (conversation().listener() == conversation.first().listener())
                conversation.complete();
            int success = 0b1111;
            connection.complete(this, status == success);
            return true;
        }
        return false;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", HttpExchange.class.getSimpleName(), hashCode());
    }
}
