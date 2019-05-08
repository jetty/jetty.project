//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.InetSocketAddress;

import javax.servlet.ServletRequest;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.server.HttpConfiguration.Customizer;
import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import static java.lang.invoke.MethodType.methodType;


/* ------------------------------------------------------------ */

/**
 * Customize Requests for Proxy Forwarding.
 * <p>
 * This customizer looks at at HTTP request for headers that indicate
 * it has been forwarded by one or more proxies.  Specifically handled are
 * <ul>
 * <li>{@code Forwarded}, as defined by <a href="https://tools.ietf.org/html/rfc7239">rfc7239</a>
 * <li>{@code X-Forwarded-Host}</li>
 * <li>{@code X-Forwarded-Server}</li>
 * <li>{@code X-Forwarded-For}</li>
 * <li>{@code X-Forwarded-Proto}</li>
 * <li>{@code X-Proxied-Https}</li>
 * </ul>
 * <p>If these headers are present, then the {@link Request} object is updated
 * so that the proxy is not seen as the other end point of the connection on which
 * the request came</p>
 * <p>Headers can also be defined so that forwarded SSL Session IDs and Cipher
 * suites may be customised</p>
 *
 * @see <a href="http://en.wikipedia.org/wiki/X-Forwarded-For">Wikipedia: X-Forwarded-For</a>
 */
public class ForwardedRequestCustomizer implements Customizer
{
    private static final Logger LOG = Log.getLogger(ForwardedRequestCustomizer.class);

    private HostPortHttpField _forcedHost;
    private String _forwardedHeader = HttpHeader.FORWARDED.toString();
    private String _forwardedHostHeader = HttpHeader.X_FORWARDED_HOST.toString();
    private String _forwardedServerHeader = HttpHeader.X_FORWARDED_SERVER.toString();
    private String _forwardedProtoHeader = HttpHeader.X_FORWARDED_PROTO.toString();
    private String _forwardedForHeader = HttpHeader.X_FORWARDED_FOR.toString();
    private String _forwardedPortHeader = HttpHeader.X_FORWARDED_PORT.toString();
    private String _forwardedHttpsHeader = "X-Proxied-Https";
    private String _forwardedCipherSuiteHeader = "Proxy-auth-cert";
    private String _forwardedSslSessionIdHeader = "Proxy-ssl-id";
    private boolean _proxyAsAuthority = false;
    private boolean _sslIsSecure = true;
    private Trie<MethodHandle> _handles;

    public ForwardedRequestCustomizer()
    {
        updateHandles();
    }

    /**
     * @return true if the proxy address obtained via
     * {@code X-Forwarded-Server} or RFC7239 "by" is used as
     * the request authority. Default false
     */
    public boolean getProxyAsAuthority()
    {
        return _proxyAsAuthority;
    }

    /**
     * @param proxyAsAuthority if true, use the proxy address obtained via
     *                         {@code X-Forwarded-Server} or RFC7239 "by" as the request authority.
     */
    public void setProxyAsAuthority(boolean proxyAsAuthority)
    {
        _proxyAsAuthority = proxyAsAuthority;
    }

    /**
     * @param rfc7239only Configure to only support the RFC7239 Forwarded header and to
     *                    not support any {@code X-Forwarded-} headers.   This convenience method
     *                    clears all the non RFC headers if passed true and sets them to
     *                    the default values (if not already set) if passed false.
     */
    public void setForwardedOnly(boolean rfc7239only)
    {
        if (rfc7239only)
        {
            if (_forwardedHeader == null)
                _forwardedHeader = HttpHeader.FORWARDED.toString();
            _forwardedHostHeader = null;
            _forwardedServerHeader = null;
            _forwardedForHeader = null;
            _forwardedPortHeader = null;
            _forwardedProtoHeader = null;
            _forwardedHttpsHeader = null;
        }
        else
        {
            if (_forwardedHostHeader == null)
                _forwardedHostHeader = HttpHeader.X_FORWARDED_HOST.toString();
            if (_forwardedServerHeader == null)
                _forwardedServerHeader = HttpHeader.X_FORWARDED_SERVER.toString();
            if (_forwardedForHeader == null)
                _forwardedForHeader = HttpHeader.X_FORWARDED_FOR.toString();
            if (_forwardedPortHeader == null)
                _forwardedPortHeader = HttpHeader.X_FORWARDED_PORT.toString();
            if (_forwardedProtoHeader == null)
                _forwardedProtoHeader = HttpHeader.X_FORWARDED_PROTO.toString();
            if (_forwardedHttpsHeader == null)
                _forwardedHttpsHeader = "X-Proxied-Https";
        }

        updateHandles();
    }

    public String getForcedHost()
    {
        return _forcedHost.getValue();
    }

    /**
     * Set a forced valued for the host header to control what is returned by {@link ServletRequest#getServerName()} and {@link ServletRequest#getServerPort()}.
     *
     * @param hostAndPort The value of the host header to force.
     */
    public void setForcedHost(String hostAndPort)
    {
        _forcedHost = new HostPortHttpField(hostAndPort);
    }

    /**
     * @return The header name for RFC forwarded (default Forwarded)
     */
    public String getForwardedHeader()
    {
        return _forwardedHeader;
    }

    /**
     * @param forwardedHeader The header name for RFC forwarded (default Forwarded)
     */
    public void setForwardedHeader(String forwardedHeader)
    {
        if (_forwardedHeader == null || !_forwardedHeader.equals(forwardedHeader))
        {
            _forwardedHeader = forwardedHeader;
            updateHandles();
        }
    }

    public String getForwardedHostHeader()
    {
        return _forwardedHostHeader;
    }

    /**
     * @param forwardedHostHeader The header name for forwarded hosts (default {@code X-Forwarded-Host})
     */
    public void setForwardedHostHeader(String forwardedHostHeader)
    {
        if (_forwardedHostHeader == null || !_forwardedHostHeader.equalsIgnoreCase(forwardedHostHeader))
        {
            _forwardedHostHeader = forwardedHostHeader;
            updateHandles();
        }
    }

    /**
     * @return the header name for forwarded server.
     */
    public String getForwardedServerHeader()
    {
        return _forwardedServerHeader;
    }

    /**
     * @param forwardedServerHeader The header name for forwarded server (default {@code X-Forwarded-Server})
     */
    public void setForwardedServerHeader(String forwardedServerHeader)
    {
        if (_forwardedServerHeader == null || !_forwardedServerHeader.equalsIgnoreCase(forwardedServerHeader))
        {
            _forwardedServerHeader = forwardedServerHeader;
            updateHandles();
        }
    }

    /**
     * @return the forwarded for header
     */
    public String getForwardedForHeader()
    {
        return _forwardedForHeader;
    }

    /**
     * @param forwardedRemoteAddressHeader The header name for forwarded for (default {@code X-Forwarded-For})
     */
    public void setForwardedForHeader(String forwardedRemoteAddressHeader)
    {
        if (_forwardedForHeader == null || !_forwardedForHeader.equalsIgnoreCase(forwardedRemoteAddressHeader))
        {
            _forwardedForHeader = forwardedRemoteAddressHeader;
            updateHandles();
        }
    }

    public String getForwardedPortHeader()
    {
        return _forwardedHostHeader;
    }

    /**
     * @param forwardedPortHeader The header name for forwarded hosts (default {@code X-Forwarded-Port})
     */
    public void setForwardedPortHeader(String forwardedPortHeader)
    {
        if (_forwardedHostHeader == null || !_forwardedHostHeader.equalsIgnoreCase(forwardedPortHeader))
        {
            _forwardedHostHeader = forwardedPortHeader;
            updateHandles();
        }
    }

    /**
     * Get the forwardedProtoHeader.
     *
     * @return the forwardedProtoHeader (default {@code X-Forwarded-Proto})
     */
    public String getForwardedProtoHeader()
    {
        return _forwardedProtoHeader;
    }

    /**
     * Set the forwardedProtoHeader.
     *
     * @param forwardedProtoHeader the forwardedProtoHeader to set (default {@code X-Forwarded-Proto})
     */
    public void setForwardedProtoHeader(String forwardedProtoHeader)
    {
        if (_forwardedProtoHeader == null || !_forwardedProtoHeader.equalsIgnoreCase(forwardedProtoHeader))
        {
            _forwardedProtoHeader = forwardedProtoHeader;
            updateHandles();
        }
    }

    /**
     * @return The header name holding a forwarded cipher suite (default {@code Proxy-auth-cert})
     */
    public String getForwardedCipherSuiteHeader()
    {
        return _forwardedCipherSuiteHeader;
    }

    /**
     * @param forwardedCipherSuiteHeader The header name holding a forwarded cipher suite (default {@code Proxy-auth-cert})
     */
    public void setForwardedCipherSuiteHeader(String forwardedCipherSuiteHeader)
    {
        if (_forwardedCipherSuiteHeader == null || !_forwardedCipherSuiteHeader.equalsIgnoreCase(forwardedCipherSuiteHeader))
        {
            _forwardedCipherSuiteHeader = forwardedCipherSuiteHeader;
            updateHandles();
        }
    }

    /**
     * @return The header name holding a forwarded SSL Session ID (default {@code Proxy-ssl-id})
     */
    public String getForwardedSslSessionIdHeader()
    {
        return _forwardedSslSessionIdHeader;
    }

    /**
     * @param forwardedSslSessionIdHeader The header name holding a forwarded SSL Session ID (default {@code Proxy-ssl-id})
     */
    public void setForwardedSslSessionIdHeader(String forwardedSslSessionIdHeader)
    {
        if (_forwardedSslSessionIdHeader == null || !_forwardedSslSessionIdHeader.equalsIgnoreCase(forwardedSslSessionIdHeader))
        {
            _forwardedSslSessionIdHeader = forwardedSslSessionIdHeader;
            updateHandles();
        }
    }

    /**
     * @return The header name holding a forwarded Https status indicator (on|off true|false) (default {@code X-Proxied-Https})
     */
    public String getForwardedHttpsHeader()
    {
        return _forwardedHttpsHeader;
    }

    /**
     * @param forwardedHttpsHeader the header name holding a forwarded Https status indicator(default {@code X-Proxied-Https})
     */
    public void setForwardedHttpsHeader(String forwardedHttpsHeader)
    {
        if (_forwardedHttpsHeader == null || !_forwardedHttpsHeader.equalsIgnoreCase(forwardedHttpsHeader))
        {
            _forwardedHttpsHeader = forwardedHttpsHeader;
            updateHandles();
        }
    }

    /**
     * @return true if the presence of a SSL session or certificate header is sufficient
     * to indicate a secure request (default is true)
     */
    public boolean isSslIsSecure()
    {
        return _sslIsSecure;
    }

    /**
     * @param sslIsSecure true if the presence of a SSL session or certificate header is sufficient
     *                    to indicate a secure request (default is true)
     */
    public void setSslIsSecure(boolean sslIsSecure)
    {
        _sslIsSecure = sslIsSecure;
    }

    @Override
    public void customize(Connector connector, HttpConfiguration config, Request request)
    {
        HttpFields httpFields = request.getHttpFields();

        // Do a single pass through the header fields as it is a more efficient single iteration.
        Forwarded forwarded = new Forwarded(request, config);
        try
        {
            for (HttpField field : httpFields)
            {
                MethodHandle handle = _handles.get(field.getName());
                if (handle != null)
                    handle.invoke(forwarded, field);
            }
        }
        catch (Throwable e)
        {
            throw new RuntimeException(e);
        }

        // Determine host
        if (_forcedHost != null)
        {
            // Update host header
            httpFields.put(_forcedHost);
            request.setAuthority(_forcedHost.getHost(), _forcedHost.getPort());
        }
        else if (forwarded._rfc7239 != null && forwarded._rfc7239._host != null)
        {
            HostPortHttpField auth = forwarded._rfc7239._host;
            httpFields.put(auth);
            request.setAuthority(auth.getHost(), auth.getPort());
        }
        else if (forwarded._forwardedHost != null)
        {
            HostPortHttpField auth = new HostPortHttpField(forwarded._forwardedHost);
            httpFields.put(auth);
            request.setAuthority(auth.getHost(), auth.getPort());
        }
        else if (_proxyAsAuthority)
        {
            if (forwarded._rfc7239 != null && forwarded._rfc7239._by != null)
            {
                HostPortHttpField auth = forwarded._rfc7239._by;
                httpFields.put(auth);
                request.setAuthority(auth.getHost(), auth.getPort());
            }
            else if (forwarded._forwardedServer != null)
            {
                request.setAuthority(forwarded._forwardedServer, request.getServerPort());
            }
        }

        // handle remote end identifier
        if (forwarded._rfc7239 != null && forwarded._rfc7239._for != null)
        {
            request.setRemoteAddr(InetSocketAddress.createUnresolved(forwarded._rfc7239._for.getHost(), forwarded._rfc7239._for.getPort()));
        }
        else if (forwarded._forwardedFor != null)
        {
            int port = (forwarded._forwardedPort > 0)
                ? forwarded._forwardedPort
                : (forwarded._forwardedFor.getPort() > 0)
                ? forwarded._forwardedFor.getPort()
                : request.getRemotePort();
            request.setRemoteAddr(InetSocketAddress.createUnresolved(forwarded._forwardedFor.getHost(), port));
        }

        // handle protocol identifier
        if (forwarded._rfc7239 != null && forwarded._rfc7239._proto != null)
        {
            request.setScheme(forwarded._rfc7239._proto);
            if (forwarded._rfc7239._proto.equals(config.getSecureScheme()))
                request.setSecure(true);
        }
        else if (forwarded._forwardedProto != null)
        {
            request.setScheme(forwarded._forwardedProto);
            if (forwarded._forwardedProto.equals(config.getSecureScheme()))
                request.setSecure(true);
        }
        else if (forwarded._forwardedHttps != null && ("on".equalsIgnoreCase(forwarded._forwardedHttps) || "true".equalsIgnoreCase(forwarded._forwardedHttps)))
        {
            request.setScheme(HttpScheme.HTTPS.asString());
            if (HttpScheme.HTTPS.asString().equals(config.getSecureScheme()))
                request.setSecure(true);
        }
    }

    /* ------------------------------------------------------------ */
    protected String getLeftMost(String headerValue)
    {
        if (headerValue == null)
            return null;

        int commaIndex = headerValue.indexOf(',');

        if (commaIndex == -1)
        {
            // Single value
            return headerValue;
        }

        // The left-most value is the farthest downstream client
        return headerValue.substring(0, commaIndex).trim();
    }

    protected HostPort getRemoteAddr(String headerValue)
    {
        String leftMost = getLeftMost(headerValue);
        if (leftMost == null)
        {
            return null;
        }

        try
        {
            return new HostPort(leftMost);
        }
        catch (Exception e)
        {
            // failed to parse in host[:port] format
            LOG.ignore(e);
            return null;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", this.getClass().getSimpleName(), hashCode());
    }

    @Deprecated
    public String getHostHeader()
    {
        return _forcedHost.getValue();
    }

    /**
     * Set a forced valued for the host header to control what is returned by {@link ServletRequest#getServerName()} and {@link ServletRequest#getServerPort()}.
     *
     * @param hostHeader The value of the host header to force.
     */
    @Deprecated
    public void setHostHeader(String hostHeader)
    {
        _forcedHost = new HostPortHttpField(hostHeader);
    }

    private final class RFC7239 extends QuotedCSV
    {
        HostPortHttpField _by;
        HostPortHttpField _for;
        HostPortHttpField _host;
        String _proto;

        private RFC7239()
        {
            super(false);
        }

        @Override
        protected void parsedParam(StringBuffer buffer, int valueLength, int paramName, int paramValue)
        {
            if (valueLength == 0 && paramValue > paramName)
            {
                String name = StringUtil.asciiToLowerCase(buffer.substring(paramName, paramValue - 1));
                String value = buffer.substring(paramValue);
                switch (name)
                {
                    case "by":
                        if (_by == null && !value.startsWith("_") && !"unknown".equals(value))
                            _by = new HostPortHttpField(value);
                        break;
                    case "for":
                        if (_for == null && !value.startsWith("_") && !"unknown".equals(value))
                            _for = new HostPortHttpField(value);
                        break;
                    case "host":
                        if (_host == null)
                            _host = new HostPortHttpField(value);
                        break;
                    case "proto":
                        if (_proto == null)
                            _proto = value;
                        break;
                }
            }
        }
    }

    private void updateHandles()
    {
        int size = 0;
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType type = methodType(Void.TYPE, HttpField.class);

        while (true)
        {
            try
            {
                size += 128;
                _handles = new ArrayTrie<>(size);

                if (_forwardedCipherSuiteHeader != null && !_handles.put(_forwardedCipherSuiteHeader, lookup.findVirtual(Forwarded.class, "handleCipherSuite", type)))
                    continue;
                if (_forwardedSslSessionIdHeader != null && !_handles.put(_forwardedSslSessionIdHeader, lookup.findVirtual(Forwarded.class, "handleSslSessionId", type)))
                    continue;
                if (_forwardedHeader != null && !_handles.put(_forwardedHeader, lookup.findVirtual(Forwarded.class, "handleRFC7239", type)))
                    continue;
                if (_forwardedForHeader != null && !_handles.put(_forwardedForHeader, lookup.findVirtual(Forwarded.class, "handleFor", type)))
                    continue;
                if (_forwardedPortHeader != null && !_handles.put(_forwardedPortHeader, lookup.findVirtual(Forwarded.class, "handlePort", type)))
                    continue;
                if (_forwardedHostHeader != null && !_handles.put(_forwardedHostHeader, lookup.findVirtual(Forwarded.class, "handleHost", type)))
                    continue;
                if (_forwardedProtoHeader != null && !_handles.put(_forwardedProtoHeader, lookup.findVirtual(Forwarded.class, "handleProto", type)))
                    continue;
                if (_forwardedHttpsHeader != null && !_handles.put(_forwardedHttpsHeader, lookup.findVirtual(Forwarded.class, "handleHttps", type)))
                    continue;
                if (_forwardedServerHeader != null && !_handles.put(_forwardedServerHeader, lookup.findVirtual(Forwarded.class, "handleServer", type)))
                    continue;
                break;
            }
            catch (NoSuchMethodException | IllegalAccessException e)
            {
                throw new IllegalStateException(e);
            }
        }
    }

    private class Forwarded
    {
        HttpConfiguration _config;
        Request _request;

        RFC7239 _rfc7239 = null;
        String _forwardedHost = null;
        String _forwardedServer = null;
        String _forwardedProto = null;
        HostPort _forwardedFor = null;
        int _forwardedPort = -1;
        String _forwardedHttps = null;

        public Forwarded(Request request, HttpConfiguration config)
        {
            _request = request;
            _config = config;
        }

        public void handleCipherSuite(HttpField field)
        {
            _request.setAttribute("javax.servlet.request.cipher_suite", field.getValue());
            if (isSslIsSecure())
            {
                _request.setSecure(true);
                _request.setScheme(_config.getSecureScheme());
            }
        }

        public void handleSslSessionId(HttpField field)
        {
            _request.setAttribute("javax.servlet.request.ssl_session_id", field.getValue());
            if (isSslIsSecure())
            {
                _request.setSecure(true);
                _request.setScheme(_config.getSecureScheme());
            }
        }

        public void handleHost(HttpField field)
        {
            _forwardedHost = getLeftMost(field.getValue());
        }

        public void handleServer(HttpField field)
        {
            _forwardedServer = getLeftMost(field.getValue());
        }

        public void handleProto(HttpField field)
        {
            _forwardedProto = getLeftMost(field.getValue());
        }

        public void handleFor(HttpField field)
        {
            _forwardedFor = getRemoteAddr(field.getValue());
        }

        public void handlePort(HttpField field)
        {
            _forwardedPort = field.getIntValue();
        }

        public void handleHttps(HttpField field)
        {
            _forwardedHttps = getLeftMost(field.getValue());
        }

        public void handleRFC7239(HttpField field)
        {
            if (_rfc7239 == null)
                _rfc7239 = new RFC7239();
            _rfc7239.addValue(field.getValue());
        }
    }
}
