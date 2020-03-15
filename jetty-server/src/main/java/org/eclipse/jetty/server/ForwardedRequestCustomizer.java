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

package org.eclipse.jetty.server;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.InetSocketAddress;
import javax.servlet.ServletRequest;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.QuotedCSVParser;
import org.eclipse.jetty.server.HttpConfiguration.Customizer;
import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;

import static java.lang.invoke.MethodType.methodType;

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
    private HostPortHttpField _forcedHost;
    private boolean _proxyAsAuthority = false;
    private boolean _forwardedPortAsAuthority = true;
    private String _forwardedHeader = HttpHeader.FORWARDED.toString();
    private String _forwardedHostHeader = HttpHeader.X_FORWARDED_HOST.toString();
    private String _forwardedServerHeader = HttpHeader.X_FORWARDED_SERVER.toString();
    private String _forwardedProtoHeader = HttpHeader.X_FORWARDED_PROTO.toString();
    private String _forwardedForHeader = HttpHeader.X_FORWARDED_FOR.toString();
    private String _forwardedPortHeader = HttpHeader.X_FORWARDED_PORT.toString();
    private String _forwardedHttpsHeader = "X-Proxied-Https";
    private String _forwardedCipherSuiteHeader = "Proxy-auth-cert";
    private String _forwardedSslSessionIdHeader = "Proxy-ssl-id";
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
     * {@code X-Forwarded-Server} or RFC7239 "by" as the request authority.
     */
    public void setProxyAsAuthority(boolean proxyAsAuthority)
    {
        _proxyAsAuthority = proxyAsAuthority;
    }

    /**
     * @param rfc7239only Configure to only support the RFC7239 Forwarded header and to
     * not support any {@code X-Forwarded-} headers.   This convenience method
     * clears all the non RFC headers if passed true and sets them to
     * the default values (if not already set) if passed false.
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
        _forcedHost = new HostPortHttpField(new ForcedHostPort(hostAndPort));
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
        return _forwardedPortHeader;
    }

    /**
     * @param forwardedPortHeader The header name for forwarded hosts (default {@code X-Forwarded-Port})
     */
    public void setForwardedPortHeader(String forwardedPortHeader)
    {
        if (_forwardedPortHeader == null || !_forwardedPortHeader.equalsIgnoreCase(forwardedPortHeader))
        {
            _forwardedPortHeader = forwardedPortHeader;
            updateHandles();
        }
    }

    /**
     * @return if true, the X-Forwarded-Port header applies to the authority,
     * else it applies to the remote client address
     */
    public boolean getForwardedPortAsAuthority()
    {
        return _forwardedPortAsAuthority;
    }

    /**
     * Set if the X-Forwarded-Port header will be used for Authority
     *
     * @param forwardedPortAsAuthority if true, the X-Forwarded-Port header applies to the authority,
     * else it applies to the remote client address
     */
    public void setForwardedPortAsAuthority(boolean forwardedPortAsAuthority)
    {
        _forwardedPortAsAuthority = forwardedPortAsAuthority;
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
     * @return true if the presence of an SSL session or certificate header is sufficient
     * to indicate a secure request (default is true)
     */
    public boolean isSslIsSecure()
    {
        return _sslIsSecure;
    }

    /**
     * @param sslIsSecure true if the presence of an SSL session or certificate header is sufficient
     * to indicate a secure request (default is true)
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
        for (HttpField field : httpFields)
        {
            try
            {
                MethodHandle handle = _handles.get(field.getName());
                if (handle != null)
                    handle.invoke(forwarded, field);
            }
            catch (Throwable t)
            {
                onError(field, t);
            }
        }

        if (forwarded._proto != null)
        {
            request.setScheme(forwarded._proto);
            if (forwarded._proto.equalsIgnoreCase(config.getSecureScheme()))
                request.setSecure(true);
        }

        if (forwarded._server != null && forwarded._host instanceof PortSetHostPort)
        {
            httpFields.put(new HostPortHttpField(forwarded._server, forwarded._host.getPort()));
            request.setAuthority(forwarded._server, forwarded._host.getPort());
        }
        else if (forwarded._host != null)
        {
            httpFields.put(new HostPortHttpField(forwarded._host));
            request.setAuthority(forwarded._host.getHost(), forwarded._host.getPort());
        }
        else if (forwarded._server != null)
        {
            httpFields.put(new HostPortHttpField(forwarded._server));
            request.setAuthority(forwarded._server, 0);
        }

        if (forwarded._for != null)
        {
            int port = forwarded._for.getPort() > 0 ? forwarded._for.getPort() : request.getRemotePort();
            request.setRemoteAddr(InetSocketAddress.createUnresolved(forwarded._for.getHost(), port));
        }
    }

    protected void onError(HttpField field, Throwable t)
    {
        throw new BadMessageException("Bad header value for " + field.getName(), t);
    }

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

    private void updateHandles()
    {
        int size = 0;
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        // Loop to grow capacity of ArrayTrie for all headers
        while (true)
        {
            try
            {
                size += 128; // experimented good baseline size
                _handles = new ArrayTrie<>(size);

                if (updateForwardedHandle(lookup, getForwardedCipherSuiteHeader(), "handleCipherSuite"))
                    continue;
                if (updateForwardedHandle(lookup, getForwardedSslSessionIdHeader(), "handleSslSessionId"))
                    continue;
                if (updateForwardedHandle(lookup, getForwardedHeader(), "handleRFC7239"))
                    continue;
                if (updateForwardedHandle(lookup, getForwardedForHeader(), "handleFor"))
                    continue;
                if (updateForwardedHandle(lookup, getForwardedPortHeader(), "handlePort"))
                    continue;
                if (updateForwardedHandle(lookup, getForwardedHostHeader(), "handleHost"))
                    continue;
                if (updateForwardedHandle(lookup, getForwardedProtoHeader(), "handleProto"))
                    continue;
                if (updateForwardedHandle(lookup, getForwardedHttpsHeader(), "handleHttps"))
                    continue;
                if (updateForwardedHandle(lookup, getForwardedServerHeader(), "handleServer"))
                    continue;
                break;
            }
            catch (NoSuchMethodException | IllegalAccessException e)
            {
                throw new IllegalStateException(e);
            }
        }
    }

    private boolean updateForwardedHandle(MethodHandles.Lookup lookup, String headerName, String forwardedMethodName) throws NoSuchMethodException, IllegalAccessException
    {
        final MethodType type = methodType(void.class, HttpField.class);

        if (StringUtil.isBlank(headerName))
            return false;

        return !_handles.put(headerName, lookup.findVirtual(Forwarded.class, forwardedMethodName, type));
    }

    private static class ForcedHostPort extends HostPort
    {
        ForcedHostPort(String authority)
        {
            super(authority);
        }
    }

    private static class PossiblyPartialHostPort extends HostPort
    {
        PossiblyPartialHostPort(String authority)
        {
            super(authority);
        }

        protected PossiblyPartialHostPort(String host, int port)
        {
            super(host, port);
        }
    }

    private static class PortSetHostPort extends PossiblyPartialHostPort
    {
        PortSetHostPort(String host, int port)
        {
            super(host, port);
        }
    }

    private static class Rfc7239HostPort extends HostPort
    {
        Rfc7239HostPort(String authority)
        {
            super(authority);
        }
    }

    private class Forwarded extends QuotedCSVParser
    {
        HttpConfiguration _config;
        Request _request;

        boolean _protoRfc7239;
        String _proto;
        HostPort _for;
        HostPort _host;
        String _server;

        public Forwarded(Request request, HttpConfiguration config)
        {
            super(false);
            _request = request;
            _config = config;
            if (_forcedHost != null)
                _host = _forcedHost.getHostPort();
        }

        @SuppressWarnings("unused")
        public void handleCipherSuite(HttpField field)
        {
            _request.setAttribute("javax.servlet.request.cipher_suite", field.getValue());
            if (isSslIsSecure())
            {
                _request.setSecure(true);
                _request.setScheme(_config.getSecureScheme());
            }
        }

        @SuppressWarnings("unused")
        public void handleSslSessionId(HttpField field)
        {
            _request.setAttribute("javax.servlet.request.ssl_session_id", field.getValue());
            if (isSslIsSecure())
            {
                _request.setSecure(true);
                _request.setScheme(_config.getSecureScheme());
            }
        }

        @SuppressWarnings("unused")
        public void handleHost(HttpField field)
        {
            if (getForwardedPortAsAuthority() && !StringUtil.isEmpty(getForwardedPortHeader()))
            {
                if (_host == null)
                    _host = new PossiblyPartialHostPort(getLeftMost(field.getValue()));
                else if (_host instanceof PortSetHostPort)
                    _host = new HostPort(HostPort.normalizeHost(getLeftMost(field.getValue())), _host.getPort());
            }
            else if (_host == null)
            {
                _host = new HostPort(getLeftMost(field.getValue()));
            }
        }

        @SuppressWarnings("unused")
        public void handleServer(HttpField field)
        {
            if (getProxyAsAuthority())
                return;
            _server = getLeftMost(field.getValue());
        }

        @SuppressWarnings("unused")
        public void handleProto(HttpField field)
        {
            if (_proto == null)
                _proto = getLeftMost(field.getValue());
        }

        @SuppressWarnings("unused")
        public void handleFor(HttpField field)
        {
            String authority = getLeftMost(field.getValue());
            if (!getForwardedPortAsAuthority() && !StringUtil.isEmpty(getForwardedPortHeader()))
            {
                if (_for == null)
                    _for = new PossiblyPartialHostPort(authority);
                else if (_for instanceof PortSetHostPort)
                    _for = new HostPort(HostPort.normalizeHost(authority), _for.getPort());
            }
            else if (_for == null)
            {
                _for = new HostPort(authority);
            }
        }

        @SuppressWarnings("unused")
        public void handlePort(HttpField field)
        {
            int port = HostPort.parsePort(getLeftMost(field.getValue()));
            if (!getForwardedPortAsAuthority())
            {
                if (_for == null)
                    _for = new PortSetHostPort(_request.getRemoteHost(), port);
                else if (_for instanceof PossiblyPartialHostPort && _for.getPort() <= 0)
                    _for = new HostPort(HostPort.normalizeHost(_for.getHost()), port);
            }
            else
            {
                if (_host == null)
                    _host = new PortSetHostPort(_request.getServerName(), port);
                else if (_host instanceof PossiblyPartialHostPort && _host.getPort() <= 0)
                    _host = new HostPort(HostPort.normalizeHost(_host.getHost()), port);
            }
        }

        @SuppressWarnings("unused")
        public void handleHttps(HttpField field)
        {
            if (_proto == null && ("on".equalsIgnoreCase(field.getValue()) || "true".equalsIgnoreCase(field.getValue())))
                _proto = HttpScheme.HTTPS.asString();
        }

        @SuppressWarnings("unused")
        public void handleRFC7239(HttpField field)
        {
            addValue(field.getValue());
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
                        if (!getProxyAsAuthority())
                            break;
                        if (value.startsWith("_") || "unknown".equals(value))
                            break;
                        if (_host == null || !(_host instanceof Rfc7239HostPort))
                            _host = new Rfc7239HostPort(value);
                        break;
                    case "for":
                        if (value.startsWith("_") || "unknown".equals(value))
                            break;
                        if (_for == null || !(_for instanceof Rfc7239HostPort))
                            _for = new Rfc7239HostPort(value);
                        break;
                    case "host":
                        if (value.startsWith("_") || "unknown".equals(value))
                            break;
                        if (_host == null || !(_host instanceof Rfc7239HostPort))
                            _host = new Rfc7239HostPort(value);
                        break;
                    case "proto":
                        if (_proto == null || !_protoRfc7239)
                        {
                            _protoRfc7239 = true;
                            _proto = value;
                        }
                        break;
                }
            }
        }
    }
}
