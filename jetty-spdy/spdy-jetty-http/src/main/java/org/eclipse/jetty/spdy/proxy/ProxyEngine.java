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
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link ProxyEngine} is the base class for SPDY proxy functionalities, that is a proxy that
 * accepts SPDY from its client side and converts to any protocol to its server side.</p>
 * <p>This class listens for SPDY events sent by clients; subclasses are responsible for translating
 * these SPDY client events into appropriate events to forward to the server, in the appropriate
 * protocol that is understood by the server.</p>
 * <p>This class also provides configuration for the proxy rules.</p>
 */
public abstract class ProxyEngine extends ServerSessionFrameListener.Adapter implements StreamFrameListener
{
    protected final Logger logger = Log.getLogger(getClass());
    private final ConcurrentMap<String, ProxyInfo> proxyInfos = new ConcurrentHashMap<>();
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

    protected ProxyEngine(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    protected void addRequestProxyHeaders(Headers headers)
    {
        String newValue = "";
        Headers.Header header = headers.get("via");
        if (header != null)
            newValue = header.valuesAsString() + ", ";
        newValue += "http/1.1 " + getName();
        headers.put("via", newValue);
    }

    protected void addResponseProxyHeaders(Headers headers)
    {
        // TODO: add Via header
    }

    public Map<String, ProxyInfo> getProxyInfos()
    {
        return new HashMap<>(proxyInfos);
    }

    public void setProxyInfos(Map<String, ProxyInfo> proxyInfos)
    {
        this.proxyInfos.clear();
        this.proxyInfos.putAll(proxyInfos);
    }

    public void putProxyInfo(String host, ProxyInfo proxyInfo)
    {
        proxyInfos.put(host, proxyInfo);
    }

    protected ProxyInfo getProxyInfo(String host)
    {
        return proxyInfos.get(host);
    }

    public static class ProxyInfo
    {
        private final short version;
        private final InetSocketAddress address;

        public ProxyInfo(short version, String host, int port)
        {
            this.version = version;
            this.address = new InetSocketAddress(host, port);
        }

        public short getVersion()
        {
            return version;
        }

        public InetSocketAddress getAddress()
        {
            return address;
        }
    }
}
