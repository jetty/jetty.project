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

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link ProxyEngine} is the class for SPDY proxy functionalities that receives a SPDY request and converts it to
 * any protocol to its server side.</p>
 * <p>This class listens for SPDY events sent by clients; subclasses are responsible for translating
 * these SPDY client events into appropriate events to forward to the server, in the appropriate
 * protocol that is understood by the server.</p>
 */
public abstract class ProxyEngine
{
    protected final Logger logger = Log.getLogger(getClass());
    private final String name;

    protected ProxyEngine()
    {
        this(name());
    }

    private static String name()
    {
        try
        {
            return InetAddress.getLocalHost().getHostName();
        }
        catch (UnknownHostException x)
        {
            return "localhost";
        }
    }

    public abstract StreamFrameListener proxy(Stream clientStream, SynInfo clientSynInfo, ProxyEngineSelector.ProxyServerInfo proxyServerInfo);

    public abstract String getProtocol();

    protected ProxyEngine(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    protected void addRequestProxyHeaders(Stream stream, Headers headers)
    {
        addViaHeader(headers);
    }

    protected void addResponseProxyHeaders(Stream stream, Headers headers)
    {
        addViaHeader(headers);
    }

    private void addViaHeader(Headers headers)
    {
        headers.add("Via", "http/1.1 " + getName());
    }

    protected void customizeRequestHeaders(Stream stream, Headers headers)
    {
    }

    protected void customizeResponseHeaders(Stream stream, Headers headers)
    {
    }

}
