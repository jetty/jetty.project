//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.rhttp.client.RHTTPRequest;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Default implementation of {@link Gateway}.
 *
 * @version $Revision$ $Date$
 */
public class StandardGateway implements Gateway
{
    private final Logger logger = Log.getLogger(getClass().toString());
    private final ConcurrentMap<String, ClientDelegate> clients = new ConcurrentHashMap<String, ClientDelegate>();
    private final ConcurrentMap<Integer, ExternalRequest> requests = new ConcurrentHashMap<Integer, ExternalRequest>();
    private final AtomicInteger requestIds = new AtomicInteger();
    private volatile long gatewayTimeout=20000;
    private volatile long externalTimeout=60000;

    public long getGatewayTimeout()
    {
        return gatewayTimeout;
    }

    public void setGatewayTimeout(long timeout)
    {
        this.gatewayTimeout = timeout;
    }

    public long getExternalTimeout()
    {
        return externalTimeout;
    }

    public void setExternalTimeout(long externalTimeout)
    {
        this.externalTimeout = externalTimeout;
    }

    public ClientDelegate getClientDelegate(String targetId)
    {
        return clients.get(targetId);
    }

    public ClientDelegate newClientDelegate(String targetId)
    {
        StandardClientDelegate client = new StandardClientDelegate(targetId);
        client.setTimeout(getGatewayTimeout());
        return client;
    }

    public ClientDelegate addClientDelegate(String targetId, ClientDelegate client)
    {
        return clients.putIfAbsent(targetId, client);
    }

    public ClientDelegate removeClientDelegate(String targetId)
    {
        return clients.remove(targetId);
    }

    public ExternalRequest newExternalRequest(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException
    {
        int requestId = requestIds.incrementAndGet();
        RHTTPRequest request = convertHttpRequest(requestId, httpRequest);
        StandardExternalRequest gatewayRequest = new StandardExternalRequest(request, httpRequest, httpResponse, this);
        gatewayRequest.setTimeout(getExternalTimeout());
        return gatewayRequest;
    }

    protected RHTTPRequest convertHttpRequest(int requestId, HttpServletRequest httpRequest) throws IOException
    {
        Map<String, String> headers = new HashMap<String, String>();
        for (Enumeration headerNames = httpRequest.getHeaderNames(); headerNames.hasMoreElements();)
        {
            String name = (String)headerNames.nextElement();
            // TODO: improve by supporting getHeaders(name)
            String value = httpRequest.getHeader(name);
            headers.put(name, value);
        }

        byte[] body = Utils.read(httpRequest.getInputStream());
        return new RHTTPRequest(requestId, httpRequest.getMethod(), httpRequest.getRequestURI(), headers, body);
    }

    public ExternalRequest addExternalRequest(int requestId, ExternalRequest externalRequest)
    {
        ExternalRequest existing = requests.putIfAbsent(requestId, externalRequest);
        if (existing == null)
            logger.debug("Added external request {}/{} - {}", new Object[]{requestId, requests.size(), externalRequest});
        return existing;
    }

    public ExternalRequest removeExternalRequest(int requestId)
    {
        ExternalRequest externalRequest = requests.remove(requestId);
        if (externalRequest != null)
            logger.debug("Removed external request {}/{} - {}", new Object[]{requestId, requests.size(), externalRequest});
        return externalRequest;
    }
}
