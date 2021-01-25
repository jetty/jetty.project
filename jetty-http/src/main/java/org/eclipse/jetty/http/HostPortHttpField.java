//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http;

import org.eclipse.jetty.util.HostPort;

/**
 * An HttpField holding a preparsed Host and port number
 *
 * @see HostPort
 */
public class HostPortHttpField extends HttpField
{
    final HostPort _hostPort;

    public HostPortHttpField(String authority)
    {
        this(HttpHeader.HOST, HttpHeader.HOST.asString(), authority);
    }

    protected HostPortHttpField(HttpHeader header, String name, String authority)
    {
        super(header, name, authority);
        try
        {
            _hostPort = new HostPort(authority);
        }
        catch (Exception e)
        {
            throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "Bad HostPort", e);
        }
    }

    public HostPortHttpField(String host, int port)
    {
        this(new HostPort(host, port));
    }

    public HostPortHttpField(HostPort hostport)
    {
        super(HttpHeader.HOST, HttpHeader.HOST.asString(), hostport.toString());
        _hostPort = hostport;
    }

    /**
     * Get the host.
     *
     * @return the host
     */
    public String getHost()
    {
        return _hostPort.getHost();
    }

    /**
     * Get the port.
     *
     * @return the port
     */
    public int getPort()
    {
        return _hostPort.getPort();
    }

    /**
     * Get the port.
     *
     * @param defaultPort The default port to return if no port set
     * @return the port
     */
    public int getPort(int defaultPort)
    {
        return _hostPort.getPort(defaultPort);
    }

    public HostPort getHostPort()
    {
        return _hostPort;
    }
}
