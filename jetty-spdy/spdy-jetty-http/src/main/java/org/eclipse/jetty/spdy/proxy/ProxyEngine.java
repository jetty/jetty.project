//========================================================================
//Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================


package org.eclipse.jetty.spdy.proxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.Stream;
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

    protected void addRequestProxyHeaders(Stream stream, Headers headers)
    {
        addViaHeader(headers);
        String address = (String)stream.getSession().getAttribute("org.eclipse.jetty.spdy.remoteAddress");
        if (address != null)
            headers.add("X-Forwarded-For", address);
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
