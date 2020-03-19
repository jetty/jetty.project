//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util;

/**
 * Parse an authority string into Host and Port
 * <p>Parse a string in the form "host:port", handling IPv4 an IPv6 hosts</p>
 *
 * <p>The System property "org.eclipse.jetty.util.HostPort.STRIP_IPV6" can be set to a boolean
 * value to control of the square brackets are stripped off IPv6 addresses (default false).</p>
 */
public class HostPort
{
    private static final boolean STRIP_IPV6 = Boolean.parseBoolean(System.getProperty("org.eclipse.jetty.util.HostPort.STRIP_IPV6", "false"));

    private final String _host;
    private final int _port;

    public HostPort(String host, int port)
    {
        _host = host;
        _port = port;
    }

    public HostPort(String authority) throws IllegalArgumentException
    {
        if (authority == null)
            throw new IllegalArgumentException("No Authority");
        try
        {
            if (authority.isEmpty())
            {
                _host = authority;
                _port = 0;
            }
            else if (authority.charAt(0) == '[')
            {
                // ipv6reference
                int close = authority.lastIndexOf(']');
                if (close < 0)
                    throw new IllegalArgumentException("Bad IPv6 host");
                _host = STRIP_IPV6 ? authority.substring(1, close) : authority.substring(0, close + 1);

                if (authority.length() > close + 1)
                {
                    if (authority.charAt(close + 1) != ':')
                        throw new IllegalArgumentException("Bad IPv6 port");
                    _port = parsePort(authority.substring(close + 2));
                }
                else
                    _port = 0;
            }
            else
            {
                // ipv4address or hostname
                int c = authority.lastIndexOf(':');
                if (c >= 0)
                {
                    if (c != authority.indexOf(':'))
                    {
                        _host = "[" + authority + "]";
                        _port = 0;
                    }
                    else
                    {
                        _host = authority.substring(0, c);
                        _port = parsePort(authority.substring(c + 1));
                    }
                }
                else
                {
                    _host = authority;
                    _port = 0;
                }
            }
        }
        catch (IllegalArgumentException iae)
        {
            throw iae;
        }
        catch (final Exception ex)
        {
            throw new IllegalArgumentException("Bad HostPort")
            {
                {
                    initCause(ex);
                }
            };
        }
    }

    /**
     * Get the host.
     *
     * @return the host
     */
    public String getHost()
    {
        return _host;
    }

    /**
     * Get the port.
     *
     * @return the port
     */
    public int getPort()
    {
        return _port;
    }

    /**
     * Get the port.
     *
     * @param defaultPort, the default port to return if a port is not specified
     * @return the port
     */
    public int getPort(int defaultPort)
    {
        return _port > 0 ? _port : defaultPort;
    }

    @Override
    public String toString()
    {
        if (_port > 0)
            return normalizeHost(_host) + ":" + _port;
        return _host;
    }

    /**
     * Normalize IPv6 address as per https://www.ietf.org/rfc/rfc2732.txt
     *
     * @param host A host name
     * @return Host name surrounded by '[' and ']' as needed.
     */
    public static String normalizeHost(String host)
    {
        // if it is normalized IPv6 or could not be IPv6, return
        if (host.isEmpty() || host.charAt(0) == '[' || host.indexOf(':') < 0)
            return host;

        // normalize with [ ]
        return "[" + host + "]";
    }

    /**
     * Parse a string representing a port validating it is a valid port value.
     *
     * @param rawPort the port string.
     * @return the integer value for the port.
     * @throws IllegalArgumentException
     */
    public static int parsePort(String rawPort) throws IllegalArgumentException
    {
        if (StringUtil.isEmpty(rawPort))
            throw new IllegalArgumentException("Bad port");

        int port = Integer.parseInt(rawPort);
        if (port <= 0 || port > 65535)
            throw new IllegalArgumentException("Bad port");

        return port;
    }
}
