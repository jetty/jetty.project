//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Parse an authority string (in the form {@code host:port}) into
 * {@code host} and {@code port}, handling IPv4 and IPv6 host formats
 * as defined in <a href="https://www.ietf.org/rfc/rfc2732.txt">RFC 2732</a></p>
 */
public class HostPort
{
    private static final Logger LOG = LoggerFactory.getLogger(HostPort.class);
    private static final int BAD_PORT = -1;
    private final String _host;
    private final int _port;

    /**
     * Create a HostPort from an unsafe (and not validated) authority.
     *
     * <p>
     *   There are no validations performed against the provided authority.
     *   It is quite possible to end up with HostPort that cannot be used
     *   to generate valid URL, URI, InetSocketAddress, Location header, etc.
     * </p>
     *
     * @param authority raw authority
     * @return the HostPort
     */
    public static HostPort unsafe(String authority)
    {
        return new HostPort(authority, true);
    }

    public HostPort(String host, int port)
    {
        _host = normalizeHost(host);
        _port = port;
    }

    public HostPort(String authority) throws IllegalArgumentException
    {
        this(authority, false);
    }

    @SuppressWarnings({"ReassignedVariable", "DataFlowIssue"})
    private HostPort(String authority, boolean unsafe)
    {
        String host;
        //noinspection UnusedAssignment
        int port = 0;

        if (authority == null)
        {
            LOG.warn("Bad Authority [<null>]");
            if (!unsafe)
                throw new IllegalArgumentException("No Authority");
            _host = "";
            _port = 0;
            return;
        }

        if (authority.isEmpty())
        {
            _host = authority;
            _port = 0;
            return;
        }

        try
        {
            if (authority.charAt(0) == '[')
            {
                // ipv6reference
                int close = authority.lastIndexOf(']');
                if (close < 0)
                {
                    LOG.warn("Bad IPv6 host: [{}]", authority);
                    if (!unsafe)
                        throw new IllegalArgumentException("Bad IPv6 host");
                    host = authority;
                }
                else
                {
                    host = authority.substring(0, close + 1);
                }

                if (!isValidIpAddress(host))
                {
                    LOG.warn("Bad IPv6 host: [{}]", host);
                    if (!unsafe)
                        throw new IllegalArgumentException("Bad IPv6 host");
                }

                if (authority.length() > close + 1)
                {
                    // ipv6 with port
                    if (authority.charAt(close + 1) != ':')
                    {
                        LOG.warn("Bad IPv6 port: [{}]", authority);
                        if (!unsafe)
                            throw new IllegalArgumentException("Bad IPv6 port");
                        host = authority; // whole authority (no substring)
                        port = 0; // no port
                    }
                    else
                    {
                        port = parsePort(authority.substring(close + 2), unsafe);
                        // horribly bad port during unsafe
                        if (unsafe && (port == BAD_PORT))
                        {
                            host = authority; // whole authority (no substring)
                            port = 0;
                        }
                    }
                }
                else
                {
                    port = 0;
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
                        port = 0;
                        host = "[" + authority + "]";
                        if (!isValidIpAddress(host))
                        {
                            LOG.warn("Bad IPv6Address: [{}]", host);
                            if (!unsafe)
                                throw new IllegalArgumentException("Bad IPv6 host");
                            host = authority; // whole authority (no substring)
                        }
                    }
                    else
                    {
                        // host/ipv4 with port
                        host = authority.substring(0, c);
                        if (StringUtil.isBlank(host))
                        {
                            LOG.warn("Bad Authority: [{}]", host);
                            if (!unsafe)
                                throw new IllegalArgumentException("Bad Authority");
                            // unsafe - allow host to be empty
                            host = "";
                        }
                        else if (!isValidHostName(host))
                        {
                            LOG.warn("Bad Authority: [{}]", host);
                            if (!unsafe)
                                throw new IllegalArgumentException("Bad Authority");
                            // unsafe - bad hostname
                            host = authority; // whole authority (no substring)
                        }

                        port = parsePort(authority.substring(c + 1), unsafe);
                        // horribly bad port during unsafe
                        if (unsafe && (port == BAD_PORT))
                        {
                            host = authority; // whole authority (no substring)
                            port = 0;
                        }
                    }
                }
                else
                {
                    // host/ipv4 without port
                    host = authority;
                    if (StringUtil.isBlank(host) || !isValidHostName(host))
                    {
                        LOG.warn("Bad Authority: [{}]", host);
                        if (!unsafe)
                            throw new IllegalArgumentException("Bad Authority");
                    }
                    port = 0;
                }
            }
        }
        catch (IllegalArgumentException iae)
        {
            if (!unsafe)
                throw iae;
            host = authority;
            port = 0;
        }
        catch (Exception ex)
        {
            if (!unsafe)
                throw new IllegalArgumentException("Bad HostPort", ex);
            host = authority;
            port = 0;
        }
        _host = host;
        _port = port;
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
     * Normalizes IPv6 address as per <a href="https://tools.ietf.org/html/rfc2732">RFC 2732</a>
     * and <a href="https://tools.ietf.org/html/rfc6874">RFC 6874</a>,
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

    /**
     * Parse a potential port.
     *
     * @param rawPort the raw port string to parse
     * @param unsafe true to always return a port in the range 0 to 65535 (or -1 for undefined if rawPort is horribly bad), false to return
     *  the provided port (or {@link IllegalArgumentException} if it is horribly bad)
     * @return the port
     * @throws IllegalArgumentException if unable to parse a valid port and {@code unsafe} is false
     */
    private int parsePort(String rawPort, boolean unsafe)
    {
        if (StringUtil.isEmpty(rawPort))
        {
            if (!unsafe)
                throw new IllegalArgumentException("Bad port [" + rawPort + "]");
            return 0;
        }

        try
        {
            int port = Integer.parseInt(rawPort);
            if (port <= 0 || port > 65535)
            {
                LOG.warn("Bad port [{}]", port);
                if (!unsafe)
                    throw new IllegalArgumentException("Bad port");
                return BAD_PORT;
            }
            return port;
        }
        catch (NumberFormatException e)
        {
            LOG.warn("Bad port [{}]", rawPort);
            if (!unsafe)
                throw new IllegalArgumentException("Bad Port");
            return BAD_PORT;
        }
    }
}
