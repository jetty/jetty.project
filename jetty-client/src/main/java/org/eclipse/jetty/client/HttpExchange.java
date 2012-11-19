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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicMarkableReference;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpExchange
{
    private static final Logger LOG = Log.getLogger(HttpExchange.class);

    private final AtomicInteger complete = new AtomicInteger();
    private final CountDownLatch terminate = new CountDownLatch(2);
    private final HttpConversation conversation;
    private final HttpConnection connection;
    private final Request request;
    private final List<Response.ResponseListener> listeners;
    private final HttpResponse response;
    private volatile boolean last;
    private volatile Throwable requestFailure;
    private volatile Throwable responseFailure;

    public HttpExchange(HttpConversation conversation, HttpConnection connection, Request request, List<Response.ResponseListener> listeners)
    {
        this.conversation = conversation;
        this.connection = connection;
        this.request = request;
        this.listeners = listeners;
        this.response = new HttpResponse(request, listeners);
    }

    public HttpConversation getConversation()
    {
        return conversation;
    }

    public Request getRequest()
    {
        return request;
    }

    public Throwable getRequestFailure()
    {
        return requestFailure;
    }

    public List<Response.ResponseListener> getResponseListeners()
    {
        return listeners;
    }

    public HttpResponse getResponse()
    {
        return response;
    }

    public Throwable getResponseFailure()
    {
        return responseFailure;
    }

    /**
     * @return whether this exchange is the last in the conversation
     */
    public boolean isLast()
    {
        return last;
    }

    /**
     * @param last whether this exchange is the last in the conversation
     */
    public void setLast(boolean last)
    {
        this.last = last;
    }

    public void receive()
    {
        connection.receive();
    }

    public AtomicMarkableReference<Result> requestComplete(Throwable failure)
    {
        int requestSuccess = 0b0011;
        int requestFailure = 0b0001;
        return complete(failure == null ? requestSuccess : requestFailure, failure);
    }

    public AtomicMarkableReference<Result> responseComplete(Throwable failure)
    {
        if (failure == null)
        {
            int responseSuccess = 0b1100;
            return complete(responseSuccess, failure);
        }
        else
        {
            proceed(false);
            int responseFailure = 0b0100;
            return complete(responseFailure, failure);
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
     * @param failure the failure - if any - associated with the status code for either the request or the response
     * @return an AtomicMarkableReference holding whether the operation modified the
     * completion status and the {@link Result} - if any - associated with the status
     */
    private AtomicMarkableReference<Result> complete(int code, Throwable failure)
    {
        Result result = null;
        boolean modified = false;

        int current;
        while (true)
        {
            current = complete.get();
            boolean updateable = (current & code) == 0;
            if (updateable)
            {
                int candidate = current | code;
                if (!complete.compareAndSet(current, candidate))
                    continue;
                current = candidate;
                modified = true;
                if ((code & 0b01) == 0b01)
                    requestFailure = failure;
                else
                    responseFailure = failure;
                LOG.debug("{} updated", this);
            }
            break;
        }

        int completed = 0b0101;
        if ((current & completed) == completed)
        {
            if (modified)
            {
                // Request and response completed
                LOG.debug("{} complete", this);
                if (isLast())
                {
                    HttpExchange first = conversation.getExchanges().peekFirst();
                    List<Response.ResponseListener> listeners = first.getResponseListeners();
                    for (Response.ResponseListener listener : listeners)
                        if (listener instanceof Schedulable)
                            ((Schedulable)listener).cancel();
                    conversation.complete();
                }
            }
            result = new Result(getRequest(), getRequestFailure(), getResponse(), getResponseFailure());
        }

        return new AtomicMarkableReference<>(result, modified);
    }

    public boolean abort(Throwable cause)
    {
        LOG.debug("Aborting {} reason {}", this, cause);
        boolean aborted = connection.abort(this, cause);
        LOG.debug("Aborted {}: {}", this, aborted);
        return aborted;
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

    public void terminateRequest()
    {
        terminate.countDown();
    }

    public void terminateResponse()
    {
        terminate.countDown();
    }

    public void awaitTermination()
    {
        try
        {
            terminate.await();
        }
        catch (InterruptedException x)
        {
            LOG.ignore(x);
        }
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
