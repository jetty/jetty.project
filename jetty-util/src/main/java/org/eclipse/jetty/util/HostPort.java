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

package org.eclipse.jetty.util;

/**
 * <p>Parse an authority string (in the form {@code host:port}) into
 * {@code host} and {@code port}, handling IPv4 and IPv6 host formats
 * as defined in https://www.ietf.org/rfc/rfc2732.txt</p>
 */
public class HostPort
{
    public static final int NO_PORT = -1;  // value used in java.net.URI for no-port
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
            if (StringUtil.isBlank(authority))
            {
                throw new IllegalArgumentException("Empty authority");
            }
            else if (authority.charAt(0) == '[')
            {
                // ipv6reference
                int close = authority.lastIndexOf(']');
                if (close < 0)
                    throw new IllegalArgumentException("Bad IPv6 host");
                _host = authority.substring(0, close + 1);
                if (!isLooseIpv6Address(_host))
                    throw new IllegalArgumentException("Invalid Host");

                if (authority.length() > close + 1)
                {
                    if (authority.charAt(close + 1) != ':')
                        throw new IllegalArgumentException("Bad IPv6 port");
                    _port = parsePort(authority.substring(close + 2));
                }
                else
                {
                    _port = NO_PORT;
                }
            }
            else
            {
                // ipv6address or ipv4address or hostname
                int c = authority.lastIndexOf(':');
                if (c >= 0)
                {
                    // possible ipv6address
                    if (c != authority.indexOf(':'))
                    {
                        if (!isLooseIpv6Address(authority))
                            throw new IllegalArgumentException("Invalid Host");

                        _host = "[" + authority + "]";
                        _port = NO_PORT;
                    }
                    else
                    {
                        _host = authority.substring(0, c);
                        _port = parsePort(authority.substring(c + 1));
                        if (StringUtil.isBlank(_host))
                            throw new IllegalArgumentException("No Host");
                        // TODO: validate _host is valid (see issue #7269)
                    }
                }
                else
                {
                    _host = authority;
                    _port = NO_PORT;
                    // TODO: validate _host is valid (see issue #7269)
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

    /**
     * <p>
     * Perform a loose validation of the characters in a IPv6 address.
     * This is not strict, and does not validate all of the aspects of IPv6,
     * is only designed to catch really bad cases such as <code>X:Y:Z</code>.
     * </p>
     *
     * For normal IPv6, see ...
     * Per https://datatracker.ietf.org/doc/html/rfc7230#section-5.4
     * and https://datatracker.ietf.org/doc/html/rfc7230#section-2.7.1
     * and https://datatracker.ietf.org/doc/html/rfc3986#section-3.2.2
     *
     * Updates for IPv4 literals in IPv6 see
     * https://tools.ietf.org/html/rfc2732#section-2
     *
     * Updates for IPv6 with zone identifiers see
     * https://datatracker.ietf.org/doc/html/rfc6874
     *
     * @param authority the raw string authority
     * @return true if it loosely follows the IPv6 address rules
     */
    private boolean isLooseIpv6Address(String authority)
    {
        if (StringUtil.isBlank(authority))
            return false;
        int start = 0;
        int end = authority.length() - 1;
        if (authority.charAt(start) == '[')
            start = 1;
        if (authority.charAt(end) == ']')
            end = end - 1;
        for (int i = start; i < end; i++)
        {
            char c = authority.charAt(i);
            if (c == '%')
                return true; // don't bother validating the rest
            if (!((c >= '0' && c <= '9') ||
                (c >= 'a' && c <= 'f') ||
                (c >= 'A' && c <= 'F') ||
                (c == '.') || (c == ':')))
            {
                return false;
            }
        }
        return true;
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
     * Get the port or the given default port.
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
            return NO_PORT;

        int port = Integer.parseInt(rawPort);
        if (port <= 0 || port > 65535)
            throw new IllegalArgumentException("Bad port");

        return port;
    }
}
