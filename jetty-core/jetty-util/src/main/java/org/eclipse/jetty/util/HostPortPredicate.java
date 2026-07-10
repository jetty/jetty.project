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
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * A predicate that matches host:port combinations.
 * Use {@link #from(String)} to create a predicate from a pattern string that conforms to one
 * of the following formats:
 *
 * <dl>
 * <dt>Exact hostname</dt>
 * <dd>An exact hostname match (case-insensitive). eg. "example.com", "localhost:8080"</dd>
 * <dt>Hostname wildcard prefix</dt>
 * <dd>A hostname pattern starting with "*." to match any subdomain.
 * eg. "*.example.com" matches "foo.example.com", "bar.baz.example.com", and "example.com" itself</dd>
 * <dt>Hostname wildcard suffix</dt>
 * <dd>A hostname pattern ending with ".*" to match any domain suffix.
 * eg. "internal.*" matches "internal.corp", "internal.local", and "internal" itself</dd>
 * <dt>Match all</dt>
 * <dd>A single "*" matches all hosts</dd>
 * <dt>InetAddress pattern</dt>
 * <dd>Any pattern supported by {@link InetAddressPattern} including CIDR notation
 * and IP ranges. eg. "192.168.0.0/16", "10.0.0.1-10.0.0.255"</dd>
 * </dl>
 *
 * <p>All patterns may optionally include a port specification after a colon.
 * If no port is specified, the pattern matches any port.</p>
 *
 * <p><b>Limitations:</b></p>
 * <ul>
 * <li>Wildcards are only supported at the start ({@code *.example.com})
 *     or end ({@code internal.*}), not in the middle</li>
 * <li>The dot in wildcard patterns is a literal dot, not a regex "any character"</li>
 * <li>Patterns cannot contain '@' (userinfo) or '/' (path components)</li>
 * <li>IP-based patterns (CIDR, ranges) require DNS resolution when matching
 *     hostnames, which adds latency and fails if DNS is unavailable</li>
 * </ul>
 *
 * <p>Based on ideas from PR #10538 by @sugilite.</p>
 *
 * @see InetAddressPattern
 * @see IncludeExcludeSet
 */
public abstract class HostPortPredicate implements Predicate<HostPort>
{
    protected final String _pattern;
    protected final int _port;

    /**
     * Creates a predicate from a host:port pattern.
     *
     * @param pattern the pattern string
     * @return a predicate appropriate for the pattern
     * @throws IllegalArgumentException if the pattern is invalid
     */
    public static HostPortPredicate from(String pattern)
    {
        if (pattern == null || pattern.isEmpty())
            throw new IllegalArgumentException("Pattern cannot be null or empty");

        String hostPattern;
        int port = -1;

        // Parse port from pattern if present
        // Handle IPv6 addresses like [::1]:8080
        if (pattern.startsWith("["))
        {
            int closeBracket = pattern.indexOf(']');
            if (closeBracket < 0)
                throw new IllegalArgumentException("Bad IPv6 pattern: " + pattern);

            if (closeBracket + 1 < pattern.length())
            {
                if (pattern.charAt(closeBracket + 1) == ':')
                {
                    port = parsePort(pattern.substring(closeBracket + 2));
                }
                else
                {
                    throw new IllegalArgumentException("Bad pattern after IPv6 address: " + pattern);
                }
            }
            hostPattern = pattern.substring(0, closeBracket + 1);
        }
        else
        {
            // Check for port - but be careful with IPv6 addresses without brackets
            int lastColon = pattern.lastIndexOf(':');
            int firstColon = pattern.indexOf(':');

            if (lastColon >= 0 && lastColon == firstColon)
            {
                // Single colon - assume host:port format
                hostPattern = pattern.substring(0, lastColon);
                port = parsePort(pattern.substring(lastColon + 1));
            }
            else if (lastColon >= 0 && lastColon != firstColon)
            {
                // Multiple colons - could be IPv6 without brackets, or IPv6 range
                // Treat the whole thing as the host pattern (no port)
                hostPattern = pattern;
            }
            else
            {
                // No colon - just host
                hostPattern = pattern;
            }
        }

        // Validate the host pattern
        validateHostPattern(hostPattern);

        // Determine pattern type
        if (hostPattern.startsWith("*."))
        {
            // Wildcard prefix: *.example.com
            return new WildcardPrefixPattern(pattern, hostPattern.substring(2).toLowerCase(Locale.ENGLISH), port);
        }
        else if (hostPattern.endsWith(".*"))
        {
            // Wildcard suffix: internal.*
            return new WildcardSuffixPattern(pattern, hostPattern.substring(0, hostPattern.length() - 2).toLowerCase(Locale.ENGLISH), port);
        }
        else if ("*".equals(hostPattern))
        {
            // Match all hosts
            return new MatchAllPattern(pattern, port);
        }
        else if (looksLikeIpPattern(hostPattern))
        {
            // Try to parse as InetAddress pattern (CIDR, ranges, exact IP)
            try
            {
                InetAddressPattern inetPattern = InetAddressPattern.from(hostPattern);
                return new InetAddressHostPortPattern(pattern, inetPattern, port);
            }
            catch (IllegalArgumentException e)
            {
                // A slash denotes CIDR syntax, so do not treat an invalid CIDR as a hostname.
                if (hostPattern.contains("/"))
                    throw e;

                // An invalid range-like pattern may still be a hostname.
            }
        }

        // Exact hostname match
        return new ExactHostPattern(pattern, hostPattern.toLowerCase(Locale.ENGLISH), port);
    }

    /**
     * Check if the pattern is a CIDR or IP range pattern that requires InetAddress matching.
     * Only CIDR notation (e.g., 192.168.0.0/16) and IP ranges (e.g., 10.0.0.1-10.0.0.10)
     * should use InetAddressPattern. Plain IP addresses use ExactHostPattern for
     * backward-compatible string comparison.
     */
    private static boolean looksLikeIpPattern(String pattern)
    {
        if (pattern.isEmpty())
            return false;

        // Must start with a digit (IPv4) or '[' (IPv6) to be an IP pattern
        char first = pattern.charAt(0);
        if (!Character.isDigit(first) && first != '[')
            return false;

        // Only match CIDR notation (192.168.0.0/16) or IP ranges (10.0.0.1-10.0.0.10)
        // Plain IP addresses use ExactHostPattern for string comparison
        return pattern.contains("/") || pattern.contains("-");
    }

    /**
     * Validate that the host pattern does not contain invalid characters or constructs.
     *
     * @param hostPattern the host pattern to validate (without port)
     * @throws IllegalArgumentException if the pattern is invalid
     */
    private static void validateHostPattern(String hostPattern)
    {
        // Reject patterns with @ (userinfo)
        if (hostPattern.contains("@"))
            throw new IllegalArgumentException("Pattern cannot contain '@' (userinfo not allowed): " + hostPattern);

        // Reject patterns with / (path) - but allow CIDR notation which is handled separately
        if (hostPattern.contains("/") && !looksLikeIpPattern(hostPattern))
            throw new IllegalArgumentException("Pattern cannot contain '/' (path not allowed): " + hostPattern);

        // Reject wildcards in invalid positions
        int starIndex = hostPattern.indexOf('*');
        if (starIndex >= 0)
        {
            // Only allow: *.suffix, prefix.*, or standalone *
            boolean validWildcard = hostPattern.equals("*") ||
                                    hostPattern.startsWith("*.") ||
                                    hostPattern.endsWith(".*");
            if (!validWildcard)
                throw new IllegalArgumentException(
                    "Wildcard '*' only supported at start (*.example.com) or end (prefix.*): " + hostPattern);
        }
    }

    private static int parsePort(String portStr)
    {
        try
        {
            int port = Integer.parseInt(portStr);
            if (port <= 0 || port > 65535)
                throw new IllegalArgumentException("Port out of range: " + port);
            return port;
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Invalid port: " + portStr, e);
        }
    }

    protected HostPortPredicate(String pattern, int port)
    {
        _pattern = pattern;
        _port = port;
    }

    /**
     * Test if this pattern matches the given host and port.
     *
     * @param hostPort the host:port to test
     * @return true if the pattern matches
     */
    @Override
    public abstract boolean test(HostPort hostPort);

    /**
     * Check if the port matches. A pattern port of -1 matches any port.
     *
     * @param targetPort the port to check
     * @return true if the port matches
     */
    protected boolean matchesPort(int targetPort)
    {
        return _port <= 0 || _port == targetPort;
    }

    @Override
    public int hashCode()
    {
        return _pattern.hashCode();
    }

    @Override
    public boolean equals(Object obj)
    {
        return obj instanceof HostPortPredicate other && _pattern.equals(other._pattern);
    }

    @Override
    public String toString()
    {
        return _pattern;
    }

    /**
     * Pattern that matches an exact hostname (case-insensitive).
     */
    static class ExactHostPattern extends HostPortPredicate
    {
        private final String _host;

        ExactHostPattern(String pattern, String host, int port)
        {
            super(pattern, port);
            _host = host;
        }

        @Override
        public boolean test(HostPort hostPort)
        {
            if (hostPort == null)
                return false;
            return _host.equalsIgnoreCase(hostPort.getHost()) && matchesPort(hostPort.getPort());
        }
    }

    /**
     * Pattern that matches hostnames with a wildcard prefix (*.example.com).
     */
    static class WildcardPrefixPattern extends HostPortPredicate
    {
        private final String _suffix;

        WildcardPrefixPattern(String pattern, String suffix, int port)
        {
            super(pattern, port);
            _suffix = suffix;
        }

        @Override
        public boolean test(HostPort hostPort)
        {
            if (hostPort == null)
                return false;
            String host = hostPort.getHost();
            if (host == null)
                return false;
            String lowerHost = host.toLowerCase(Locale.ENGLISH);
            // Match "foo.example.com" against ".example.com" suffix
            // Also match "example.com" itself
            boolean hostMatches = lowerHost.endsWith("." + _suffix) || lowerHost.equals(_suffix);
            return hostMatches && matchesPort(hostPort.getPort());
        }
    }

    /**
     * Pattern that matches hostnames with a wildcard suffix (internal.*).
     */
    static class WildcardSuffixPattern extends HostPortPredicate
    {
        private final String _prefix;

        WildcardSuffixPattern(String pattern, String prefix, int port)
        {
            super(pattern, port);
            _prefix = prefix;
        }

        @Override
        public boolean test(HostPort hostPort)
        {
            if (hostPort == null)
                return false;
            String host = hostPort.getHost();
            if (host == null)
                return false;
            String lowerHost = host.toLowerCase(Locale.ENGLISH);
            // Match "internal.corp" against "internal." prefix
            // Also match "internal" itself
            boolean hostMatches = lowerHost.startsWith(_prefix + ".") || lowerHost.equals(_prefix);
            return hostMatches && matchesPort(hostPort.getPort());
        }
    }

    /**
     * Pattern that matches all hosts.
     */
    static class MatchAllPattern extends HostPortPredicate
    {
        MatchAllPattern(String pattern, int port)
        {
            super(pattern, port);
        }

        @Override
        public boolean test(HostPort hostPort)
        {
            if (hostPort == null)
                return false;
            return matchesPort(hostPort.getPort());
        }
    }

    /**
     * Pattern that wraps an InetAddressPattern for IP-based matching.
     */
    static class InetAddressHostPortPattern extends HostPortPredicate
    {
        private final InetAddressPattern _inetPattern;

        InetAddressHostPortPattern(String pattern, InetAddressPattern inetPattern, int port)
        {
            super(pattern, port);
            _inetPattern = inetPattern;
        }

        @Override
        public boolean test(HostPort hostPort)
        {
            if (hostPort == null)
                return false;

            if (!matchesPort(hostPort.getPort()))
                return false;

            String host = hostPort.getHost();
            if (host == null)
                return false;

            try
            {
                // Resolve hostname to InetAddress
                InetAddress address = InetAddress.getByName(host);
                return _inetPattern.test(address);
            }
            catch (UnknownHostException e)
            {
                // Cannot resolve - no match
                return false;
            }
        }
    }
}
