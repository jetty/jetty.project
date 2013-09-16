//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.proxy;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.PingInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link ProxyEngineSelector} is the main entry point for syn stream events of a jetty SPDY proxy. It receives the
 * syn stream frames from the clients, checks if there's an appropriate {@link ProxyServerInfo} for the given target
 * host and forwards the syn to a {@link ProxyEngine} for the protocol defined in {@link ProxyServerInfo}.</p>
 *
 * <p>If no {@link ProxyServerInfo} can be found for the given target host or no {@link ProxyEngine} can be found for
 * the given protocol, it resets the client stream.</p>
 *
 * <p>This class also provides configuration for the proxy rules.</p>
 */
public class ProxyEngineSelector extends ServerSessionFrameListener.Adapter
{
    protected final Logger logger = Log.getLogger(getClass());
    private final Map<String, ProxyServerInfo> proxyInfos = new ConcurrentHashMap<>();
    private final Map<String, ProxyEngine> proxyEngines = new ConcurrentHashMap<>();

    @Override
    public final StreamFrameListener onSyn(final Stream clientStream, SynInfo clientSynInfo)
    {
        logger.debug("C -> P {} on {}", clientSynInfo, clientStream);

        final Session clientSession = clientStream.getSession();
        short clientVersion = clientSession.getVersion();
        Headers headers = new Headers(clientSynInfo.getHeaders(), false);

        Headers.Header hostHeader = headers.get(HTTPSPDYHeader.HOST.name(clientVersion));
        if (hostHeader == null)
        {
            logger.debug("No host header found: " + headers);
            rst(clientStream);
            return null;
        }

        String host = hostHeader.value();
        int colon = host.indexOf(':');
        if (colon >= 0)
            host = host.substring(0, colon);

        ProxyServerInfo proxyServerInfo = getProxyServerInfo(host);
        if (proxyServerInfo == null)
        {
            logger.debug("No matching ProxyServerInfo found for: " + host);
            rst(clientStream);
            return null;
        }

        String protocol = proxyServerInfo.getProtocol();
        ProxyEngine proxyEngine = proxyEngines.get(protocol);
        if (proxyEngine == null)
        {
            logger.debug("No matching ProxyEngine found for: " + protocol);
            rst(clientStream);
            return null;
        }

        return proxyEngine.proxy(clientStream, clientSynInfo, proxyServerInfo);
    }

    @Override
    public void onPing(Session clientSession, PingInfo pingInfo)
    {
        // We do not know to which upstream server
        // to send the PING so we just ignore it
    }

    @Override
    public void onGoAway(Session session, GoAwayInfo goAwayInfo)
    {
        // TODO:
    }

    public Map<String, ProxyEngine> getProxyEngines()
    {
        return new HashMap<>(proxyEngines);
    }

    public void setProxyEngines(Map<String, ProxyEngine> proxyEngines)
    {
        this.proxyEngines.clear();
        this.proxyEngines.putAll(proxyEngines);
    }

    public ProxyEngine getProxyEngine(String protocol)
    {
        return proxyEngines.get(protocol);
    }

    public void putProxyEngine(String protocol, ProxyEngine proxyEngine)
    {
        proxyEngines.put(protocol, proxyEngine);
    }

    public Map<String, ProxyServerInfo> getProxyServerInfos()
    {
        return new HashMap<>(proxyInfos);
    }

    protected ProxyServerInfo getProxyServerInfo(String host)
    {
        return proxyInfos.get(host);
    }

    public void setProxyServerInfos(Map<String, ProxyServerInfo> proxyServerInfos)
    {
        this.proxyInfos.clear();
        this.proxyInfos.putAll(proxyServerInfos);
    }

    public void putProxyServerInfo(String host, ProxyServerInfo proxyServerInfo)
    {
        proxyInfos.put(host, proxyServerInfo);
    }

    private void rst(Stream stream)
    {
        RstInfo rstInfo = new RstInfo(stream.getId(), StreamStatus.REFUSED_STREAM);
        stream.getSession().rst(rstInfo);
    }

    public static class ProxyServerInfo
    {
        private final String protocol;
        private final String host;
        private final InetSocketAddress address;

        public ProxyServerInfo(String protocol, String host, int port)
        {
            this.protocol = protocol;
            this.host = host;
            this.address = new InetSocketAddress(host, port);
        }

        public String getProtocol()
        {
            return protocol;
        }

        public String getHost()
        {
            return host;
        }

        public InetSocketAddress getAddress()
        {
            return address;
        }
    }
}
