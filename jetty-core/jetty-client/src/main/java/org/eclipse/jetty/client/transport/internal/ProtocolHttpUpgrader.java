//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.transport.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.internal.HttpDestination;
import org.eclipse.jetty.client.internal.HttpResponse;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A HttpUpgrader that upgrades to a given protocol.</p>
 * <p>Works in conjunction with {@link HttpClientTransportDynamic}
 * so that the protocol to upgrade to must be one of the application
 * protocols supported by HttpClientTransportDynamic.</p>
 * <p></p>
 */
public class ProtocolHttpUpgrader implements HttpUpgrader
{
    private static final Logger LOG = LoggerFactory.getLogger(ProtocolHttpUpgrader.class);

    private final HttpDestination destination;
    private final String protocol;

    public ProtocolHttpUpgrader(HttpDestination destination, String protocol)
    {
        this.destination = destination;
        this.protocol = protocol;
    }

    @Override
    public void prepare(Request request)
    {
    }

    @Override
    public void upgrade(Response response, EndPoint endPoint, Callback callback)
    {
        if (response.getHeaders().contains(HttpHeader.UPGRADE, protocol))
        {
            HttpClient httpClient = destination.getHttpClient();
            HttpClientTransport transport = httpClient.getTransport();
            if (transport instanceof HttpClientTransportDynamic)
            {
                HttpClientTransportDynamic dynamicTransport = (HttpClientTransportDynamic)transport;

                Origin origin = destination.getOrigin();
                Origin newOrigin = new Origin(origin.getScheme(), origin.getAddress(), origin.getTag(), new Origin.Protocol(List.of(protocol), false));
                Destination newDestination = httpClient.resolveDestination(newOrigin);

                Map<String, Object> context = new HashMap<>();
                context.put(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY, newDestination);
                context.put(HttpResponse.class.getName(), response);
                context.put(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY, Promise.from(y -> callback.succeeded(), callback::failed));

                if (LOG.isDebugEnabled())
                    LOG.debug("Upgrading {} on {}", response.getRequest(), endPoint);

                dynamicTransport.upgrade(endPoint, context);
            }
            else
            {
                callback.failed(new HttpResponseException(HttpClientTransportDynamic.class.getName() + " required to upgrade to: " + protocol, response));
            }
        }
        else
        {
            callback.failed(new HttpResponseException("Not an upgrade to: " + protocol, response));
        }
    }
}
