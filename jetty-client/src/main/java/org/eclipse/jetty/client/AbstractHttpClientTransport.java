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

package org.eclipse.jetty.client;

import java.util.Map;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject
public abstract class AbstractHttpClientTransport extends ContainerLifeCycle implements HttpClientTransport
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpClientTransport.class);

    private HttpClient client;
    private ConnectionPool.Factory factory;

    protected HttpClient getHttpClient()
    {
        return client;
    }

    @Override
    public void setHttpClient(HttpClient client)
    {
        this.client = client;
    }

    @Override
    public ConnectionPool.Factory getConnectionPoolFactory()
    {
        return factory;
    }

    @Override
    public void setConnectionPoolFactory(ConnectionPool.Factory factory)
    {
        this.factory = factory;
    }

    protected void connectFailed(Map<String, Object> context, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Could not connect to {}", context.get(HTTP_DESTINATION_CONTEXT_KEY));
        @SuppressWarnings("unchecked")
        Promise<Connection> promise = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        promise.failed(failure);
    }
}
