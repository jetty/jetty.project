//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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

    public HostPortHttpField(HttpHeader header, String headerString, HostPort hostport)
    {
        super(header, headerString, hostport.toString());
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
