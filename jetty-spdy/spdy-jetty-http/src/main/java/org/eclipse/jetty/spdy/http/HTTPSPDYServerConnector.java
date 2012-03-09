/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.http;

import org.eclipse.jetty.spdy.AsyncConnectionFactory;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HTTPSPDYServerConnector extends SPDYServerConnector
{
    private final AsyncConnectionFactory defaultConnectionFactory;

    public HTTPSPDYServerConnector()
    {
        this(null);
    }

    public HTTPSPDYServerConnector(SslContextFactory sslContextFactory)
    {
        super(null, sslContextFactory);
        // Override the default connection factory for non-SSL connections
        defaultConnectionFactory = new ServerHTTPAsyncConnectionFactory(this);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        // Override the "spdy/2" protocol by handling HTTP over SPDY
        putAsyncConnectionFactory("spdy/2", new ServerHTTPSPDYAsyncConnectionFactory(SPDY.V2, getByteBufferPool(), getExecutor(), getScheduler(), this));
        // Add the "http/1.1" protocol for browsers that do not support NPN
        putAsyncConnectionFactory("http/1.1", new ServerHTTPAsyncConnectionFactory(this));
    }

    @Override
    protected AsyncConnectionFactory getDefaultAsyncConnectionFactory()
    {
        return defaultConnectionFactory;
    }
}
