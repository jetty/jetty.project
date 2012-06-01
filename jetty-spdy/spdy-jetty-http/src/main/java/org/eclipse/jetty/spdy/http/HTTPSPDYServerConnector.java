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

import java.io.IOException;

import org.eclipse.jetty.http.HttpSchemes;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class HTTPSPDYServerConnector extends SPDYServerConnector
{
    public HTTPSPDYServerConnector()
    {
        this(null, new PushStrategy.None());
    }

    public HTTPSPDYServerConnector(PushStrategy pushStrategy)
    {
        this(null, pushStrategy);
    }

    public HTTPSPDYServerConnector(SslContextFactory sslContextFactory)
    {
        this(sslContextFactory, new PushStrategy.None());
    }

    public HTTPSPDYServerConnector(SslContextFactory sslContextFactory, PushStrategy pushStrategy)
    {
        // We pass a null ServerSessionFrameListener because for
        // HTTP over SPDY we need one that references the endPoint
        super(null, sslContextFactory);
        // Override the default connection factory for non-SSL connections to speak plain HTTP
        setDefaultAsyncConnectionFactory(new ServerHTTPAsyncConnectionFactory(this));
        // Override the "spdy/3" protocol by handling HTTP over SPDY
        putAsyncConnectionFactory("spdy/3", new ServerHTTPSPDYAsyncConnectionFactory(SPDY.V3, getByteBufferPool(), getExecutor(), getScheduler(), this, pushStrategy));
        // Override the "spdy/2" protocol by handling HTTP over SPDY
        putAsyncConnectionFactory("spdy/2", new ServerHTTPSPDYAsyncConnectionFactory(SPDY.V2, getByteBufferPool(), getExecutor(), getScheduler(), this, pushStrategy));
        // Add the "http/1.1" protocol for browsers that support NPN but not SPDY
        putAsyncConnectionFactory("http/1.1", getDefaultAsyncConnectionFactory());
    }

    @Override
    public void customize(EndPoint endPoint, Request request) throws IOException
    {
        super.customize(endPoint, request);
        if (getSslContextFactory() != null)
            request.setScheme(HttpSchemes.HTTPS);
    }

    @Override
    public boolean isConfidential(Request request)
    {
        if (getSslContextFactory() != null)
        {
            int confidentialPort = getConfidentialPort();
            return confidentialPort == 0 || confidentialPort == request.getServerPort();
        }
        return super.isConfidential(request);
    }

    @Override
    public boolean isIntegral(Request request)
    {
        if (getSslContextFactory() != null)
        {
            int integralPort = getIntegralPort();
            return integralPort == 0 || integralPort == request.getServerPort();
        }
        return super.isIntegral(request);
    }
}
