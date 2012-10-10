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
import org.eclipse.jetty.client.api.Result;
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
    private volatile Throwable requestFailure;
    private volatile Throwable responseFailure;

    public HttpExchange(HttpConversation conversation, HttpConnection connection, Request request, Response.Listener listener)
    {
        this.conversation = conversation;
        this.connection = connection;
        this.request = request;
        this.listener = listener;
        this.response = new HttpResponse(this, listener);
    }

    public HttpConversation conversation()
    {
        return conversation;
    }

    public Request request()
    {
        return request;
    }

    public Throwable requestFailure()
    {
        return requestFailure;
    }

    public Response.Listener listener()
    {
        return listener;
    }

    public HttpResponse response()
    {
        return response;
    }

    public Throwable responseFailure()
    {
        return responseFailure;
    }

    public void receive()
    {
        connection.receive();
    }

    public Result requestComplete(Throwable failure)
    {
        this.requestFailure = failure;
        int requestSuccess = 0b0011;
        int requestFailure = 0b0001;
        return complete(failure == null ? requestSuccess : requestFailure);
    }

    public Result responseComplete(Throwable failure)
    {
        this.responseFailure = failure;
        if (failure == null)
        {
            int responseSuccess = 0b1100;
            return complete(responseSuccess);
        }
        else
        {
            proceed(false);
            int responseFailure = 0b0100;
            return complete(responseFailure);
        }
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
     * @return the result if the exchange completed, or null if the exchange did not complete
     */
    private Result complete(int code)
    {
        int status = complete.addAndGet(code);
        int completed = 0b0101;
        if ((status & completed) == completed)
        {
            boolean success = status == 0b1111;
            LOG.debug("{} complete success={}", this, success);
            // Request and response completed
            if (this == conversation.last())
                conversation.complete();
            connection.complete(this, success);
            return new Result(request(), requestFailure(), response(), responseFailure());
        }
        return null;
    }

    public void abort()
    {
        LOG.debug("Aborting {}", response);
        connection.abort(response);
    }

    public void resetResponse(boolean success)
    {
        int responseSuccess = 0b1100;
        int responseFailure = 0b0100;
        int code = success ? responseSuccess : responseFailure;
        complete.addAndGet(-code);
    }

    public void proceed(boolean proceed)
    {
        connection.proceed(proceed);
    }

    @Override
    public String toString()
    {
        String padding = "0000";
        String status = Integer.toBinaryString(complete.get());
        return String.format("%s@%x status=%s%s",
                HttpExchange.class.getSimpleName(),
                hashCode(),
                padding.substring(status.length()),
                status);
    }
}
