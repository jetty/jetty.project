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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.api.StreamFrameListener;
import org.eclipse.jetty.spdy.api.SynInfo;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.Fields;

/**
 * <p>{@link ProxyEngine} is the class for SPDY proxy functionalities that receives a SPDY request and converts it to
 * any protocol to its server side.</p>
 * <p>This class listens for SPDY events sent by clients; subclasses are responsible for translating
 * these SPDY client events into appropriate events to forward to the server, in the appropriate
 * protocol that is understood by the server.</p>
 */
public abstract class ProxyEngine
{
    private static final Set<String> HOP_HEADERS = new HashSet<>();
    static
    {
        HOP_HEADERS.add("proxy-connection");
        HOP_HEADERS.add("connection");
        HOP_HEADERS.add("keep-alive");
        HOP_HEADERS.add("transfer-encoding");
        HOP_HEADERS.add("te");
        HOP_HEADERS.add("trailer");
        HOP_HEADERS.add("proxy-authorization");
        HOP_HEADERS.add("proxy-authenticate");
        HOP_HEADERS.add("upgrade");
    }

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

    protected ProxyEngine(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    protected void removeHopHeaders(Fields headers)
    {
        // Header names are case-insensitive (RFC2616) and oej.util.Fields.add converts the names to lowercase. So we
        // need to compare with the lowercase values only
        for (String hopHeader : HOP_HEADERS)
            headers.remove(hopHeader);
    }

    protected void addRequestProxyHeaders(Stream stream, Fields headers)
    {
        addViaHeader(headers);
        Fields.Field schemeField = headers.get(HTTPSPDYHeader.SCHEME.name(stream.getSession().getVersion()));
        if(schemeField != null)
            headers.add("X-Forwarded-Proto", schemeField.getValue());
        InetSocketAddress address = stream.getSession().getRemoteAddress();
        if (address != null)
        {
            headers.add("X-Forwarded-Host", address.getHostName());
            headers.add("X-Forwarded-For", address.toString());
        }
        headers.add("X-Forwarded-Server", name());
    }

    protected void addResponseProxyHeaders(Stream stream, Fields headers)
    {
        addViaHeader(headers);
    }

    private void addViaHeader(Fields headers)
    {
        headers.add("Via", "http/1.1 " + getName());
    }

    protected void customizeRequestHeaders(Stream stream, Fields headers)
    {
    }

    protected void customizeResponseHeaders(Stream stream, Fields headers)
    {
    }

}
