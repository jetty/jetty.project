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

package org.eclipse.jetty.rhttp.gateway;

import java.io.IOException;
import java.util.Map;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.rhttp.client.RHTTPRequest;
import org.eclipse.jetty.rhttp.client.RHTTPResponse;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Default implementation of {@link ExternalRequest}.
 *
 * @version $Revision$ $Date$
 */
public class StandardExternalRequest implements ExternalRequest
{
    private final Logger logger = Log.getLogger(getClass().toString());
    private final RHTTPRequest request;
    private final HttpServletRequest httpRequest;
    private final HttpServletResponse httpResponse;
    private final Gateway gateway;
    private final Object lock = new Object();
    private volatile long timeout;
    private Continuation continuation;
    private boolean responded;

    public StandardExternalRequest(RHTTPRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse, Gateway gateway)
    {
        this.request = request;
        this.httpRequest = httpRequest;
        this.httpResponse = httpResponse;
        this.gateway = gateway;
    }

    public long getTimeout()
    {
        return timeout;
    }

    public void setTimeout(long timeout)
    {
        this.timeout = timeout;
    }

    public boolean suspend()
    {
        synchronized (lock)
        {
            // We suspend only if we have no responded yet
            if (!responded)
            {
                assert continuation == null;
                continuation = ContinuationSupport.getContinuation(httpRequest);
                continuation.setTimeout(getTimeout());
                continuation.addContinuationListener(new TimeoutListener());
                continuation.suspend(httpResponse);
                logger.debug("Request {} suspended", getRequest());
            }
            else
            {
                logger.debug("Request {} already responded", getRequest());
            }
            return !responded;
        }
    }

    public void respond(RHTTPResponse response) throws IOException
    {
        responseCompleted(response);
    }

    private void responseCompleted(RHTTPResponse response) throws IOException
    {
        synchronized (lock)
        {
            // Could be that we complete exactly when the response is being expired
            if (!responded)
            {
                httpResponse.setStatus(response.getStatusCode());

                for (Map.Entry<String, String> header : response.getHeaders().entrySet())
                    httpResponse.setHeader(header.getKey(), header.getValue());

                ServletOutputStream output = httpResponse.getOutputStream();
                output.write(response.getBody());
                output.flush();

                // It may happen that the continuation is null,
                // because the response arrived before we had the chance to suspend
                if (continuation != null)
                {
                    continuation.complete();
                    continuation = null;
                }

                // Mark as responded, so we know we don't have to suspend
                // or respond with an expired response
                responded = true;

                if (logger.isDebugEnabled())
                {
                    String eol = System.getProperty("line.separator");
                    logger.debug("Request {} responded {}{}{}{}{}", new Object[]{request, response, eol, request.toLongString(), eol, response.toLongString()});
                }
            }
        }
    }

    private void responseExpired() throws IOException
    {
        synchronized (lock)
        {
            // Could be that we expired exactly when the response is being completed
            if (!responded)
            {
                httpResponse.sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, "Gateway Time-out");

                continuation.complete();
                continuation = null;

                // Mark as responded, so we know we don't have to respond with a completed response
                responded = true;

                logger.debug("Request {} expired", getRequest());
            }
        }
    }

    public RHTTPRequest getRequest()
    {
        return request;
    }

    @Override
    public String toString()
    {
        return request.toString();
    }

    private class TimeoutListener implements ContinuationListener
    {
        public void onComplete(Continuation continuation)
        {
        }

        public void onTimeout(Continuation continuation)
        {
            ExternalRequest externalRequest = gateway.removeExternalRequest(getRequest().getId());
            // The gateway request can be null for a race with delivery
            if (externalRequest != null)
            {
                try
                {
                    responseExpired();
                }
                catch (Exception x)
                {
                    logger.warn("Request " + getRequest() + " expired but failed", x);
                }
            }
        }
    }
}
