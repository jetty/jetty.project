//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.server.proxy;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.spdy.api.GoAwayResultInfo;
import org.eclipse.jetty.spdy.api.PingResultInfo;
import org.eclipse.jetty.spdy.api.RstInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.StreamStatus;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>{@link ProxyEngineSelector} is the main entry point for push stream events of a jetty SPDY proxy. It receives the
 * push stream frames from the clients, checks if there's an appropriate {@link ProxyServerInfo} for the given target
 * host and forwards the push to a {@link ProxyEngine} for the protocol defined in {@link ProxyServerInfo}.</p>
 *
 * <p>If no {@link ProxyServerInfo} can be found for the given target host or no {@link ProxyEngine} can be found for
 * the given protocol, it resets the client stream.</p>
 *
 * <p>This class also provides configuration for the proxy rules.</p>
 */
public class ProxyEngineSelector extends ServerSessionFrameListener.Adapter
{
    protected final Logger LOG = Log.getLogger(getClass());
    private final Map<String, ProxyServerInfo> proxyInfos = new ConcurrentHashMap<>();
    private final Map<String, ProxyEngine> proxyEngines = new ConcurrentHashMap<>();

    @Override
    public final StreamFrameListener onSyn(final Stream clientStream, SynInfo clientSynInfo)
    {
        LOG.debug("C -> P {} on {}", clientSynInfo, clientStream);

        final Session clientSession = clientStream.getSession();
        short clientVersion = clientSession.getVersion();
        Fields headers = new Fields(clientSynInfo.getHeaders(), false);

        Fields.Field hostHeader = headers.get(HTTPSPDYHeader.HOST.name(clientVersion));
        if (hostHeader == null)
        {
            LOG.debug("No host header found: " + headers);
            rst(clientStream);
            return null;
        }

        String host = hostHeader.getValue();
        int colon = host.indexOf(':');
        if (colon >= 0)
            host = host.substring(0, colon);

        ProxyServerInfo proxyServerInfo = getProxyServerInfo(host);
        if (proxyServerInfo == null)
        {
            LOG.debug("No matching ProxyServerInfo found for: " + host);
            rst(clientStream);
            return null;
        }

        String protocol = proxyServerInfo.getProtocol();
        ProxyEngine proxyEngine = proxyEngines.get(protocol);
        if (proxyEngine == null)
        {
            LOG.debug("No matching ProxyEngine found for: " + protocol);
            rst(clientStream);
            return null;
        }
        LOG.debug("Forwarding request: {} -> {}", clientSynInfo, proxyServerInfo);
        return proxyEngine.proxy(clientStream, clientSynInfo, proxyServerInfo);
    }

    @Override
    public void onPing(Session clientSession, PingResultInfo pingResultInfo)
    {
        // We do not know to which upstream server
        // to send the PING so we just ignore it
    }

    @Override
    public void onGoAway(Session session, GoAwayResultInfo goAwayResultInfo)
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
        stream.getSession().rst(rstInfo, Callback.Adapter.INSTANCE);
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

        @Override
        public String toString()
        {
            return "ProxyServerInfo{" +
                    "protocol='" + protocol + '\'' +
                    ", host='" + host + '\'' +
                    ", address=" + address +
                    '}';
        }
    }
}
