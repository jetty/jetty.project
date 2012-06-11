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

package org.eclipse.jetty.spdy.proxy;

import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.spdy.http.ServerHTTPAsyncConnectionFactory;

public class ProxyHTTPAsyncConnectionFactory extends ServerHTTPAsyncConnectionFactory
{
    private final short version;
    private final ProxyEngine proxyEngine;

    public ProxyHTTPAsyncConnectionFactory(SPDYServerConnector connector, short version, ProxyEngine proxyEngine)
    {
        super(connector);
        this.version = version;
        this.proxyEngine = proxyEngine;
    }

    @Override
    public AsyncConnection newAsyncConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
    {
        return new ProxyHTTPSPDYAsyncConnection(getConnector(), endPoint, version, proxyEngine);
    }
}
