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

}
