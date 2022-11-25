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

package org.eclipse.jetty.util;

import java.net.InetAddress;

import org.eclipse.jetty.util.annotation.ManagedAttribute;

/**
 * <p>Parse an authority string (in the form {@code host:port}) into
 * {@code host} and {@code port}, handling IPv4 and IPv6 host formats
 * as defined in https://www.ietf.org/rfc/rfc2732.txt</p>
 */
public class HostPort
{
    private final String _host;
    private final int _port;

    public HostPort(String host, int port)
    {
        _host = normalizeHost(host);
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
                _host = authority.substring(0, close + 1);
                if (!isValidIpAddress(_host))
                    throw new IllegalArgumentException("Bad IPv6 host");

                if (authority.length() > close + 1)
                {
                    // ipv6 with port
                    if (authority.charAt(close + 1) != ':')
                        throw new IllegalArgumentException("Bad IPv6 port");
                    _port = parsePort(authority.substring(close + 2));
                }
                else
                {
                    _port = 0;
                }
            }
            else
            {
                // ipv6address or ipv4address or hostname
                int c = authority.lastIndexOf(':');
                if (c >= 0)
                {
                    if (c != authority.indexOf(':'))
                    {
                        // ipv6address no port
                        _host = "[" + authority + "]";
                        if (!isValidIpAddress(_host))
                            throw new IllegalArgumentException("Bad IPv6 host");
                        _port = 0;
                    }
                    else
                    {
                        // host/ipv4 with port
                        _host = authority.substring(0, c);
                        if (StringUtil.isBlank(_host) || !isValidHostName(_host))
                            throw new IllegalArgumentException("Bad Authority");
                        _port = parsePort(authority.substring(c + 1));
                    }
                }
                else
                {
                    // host/ipv4 without port
                    _host = authority;
                    if (StringUtil.isBlank(_host) || !isValidHostName(_host))
                        throw new IllegalArgumentException("Bad Authority");
                    _port = 0;
                }
            }
        }
        catch (IllegalArgumentException iae)
        {
            throw iae;
        }
        catch (Exception ex)
        {
            throw new IllegalArgumentException("Bad HostPort", ex);
        }
    }

    protected boolean isValidIpAddress(String ip)
    {
        try
        {
            // Per javadoc, If a literal IP address is supplied, only the validity of the
            // address format is checked.
            InetAddress.getByName(ip);
            return true;
        }
        catch (Throwable ignore)
        {
            return false;
        }
    }

    protected boolean isValidHostName(String name)
    {
        return URIUtil.isValidHostRegisteredName(name);
    }

    /**
     * Get the host.
     *
     * @return the host
     */
    @ManagedAttribute("host")
    public String getHost()
    {
        return _host;
    }

    /**
     * Get the port.
     *
     * @return the port
     */
    @ManagedAttribute("port")
    public int getPort()
    {
        return _port;
    }

    /**
     * Get the port or the given default port.
     *
     * @param defaultPort, the default port to return if a port is not specified
     * @return the port
     */
    public int getPort(int defaultPort)
    {
        return _port > 0 ? _port : defaultPort;
    }

    public boolean hasHost()
    {
        return StringUtil.isNotBlank(_host);
    }

    public boolean hasPort()
    {
        return _port > 0;
    }

    @Override
    public String toString()
    {
        if (_port > 0)
            return _host + ":" + _port;
        return _host;
    }

    /**
     * Normalizes IPv6 address as per https://tools.ietf.org/html/rfc2732
     * and https://tools.ietf.org/html/rfc6874,
     * surrounding with square brackets if they are absent.
     *
     * @param host a host name, IPv4 address, IPv6 address or IPv6 literal
     * @return a host name or an IPv4 address or an IPv6 literal (not an IPv6 address)
     */
    public static String normalizeHost(String host)
    {
        // if it is normalized IPv6 or could not be IPv6, return
        if (host == null || host.isEmpty() || host.charAt(0) == '[' || host.indexOf(':') < 0)
            return host;

        // normalize with [ ]
        return "[" + host + "]";
    }

    /**
     * Parse a string representing a port validating it is a valid port value.
     *
     * @param rawPort the port string.
     * @return the integer value for the port.
     * @throws IllegalArgumentException if the port is invalid
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
