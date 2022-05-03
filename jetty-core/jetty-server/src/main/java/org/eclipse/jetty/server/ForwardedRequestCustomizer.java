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

package org.eclipse.jetty.server;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.QuotedCSVParser;
import org.eclipse.jetty.server.internal.HttpConnection;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>If these headers are present, then the {@link Request} object is wrapped
 * so that the proxy is not seen as the other end point of the connection on which
 * the request came</p>
 * <p>Headers can also be defined so that forwarded SSL Session IDs and Cipher
 * suites may be customised</p>
 * <p>
 *     The Authority (host and port) is updated on the {@link Request} object based
 *     on the host / port information in the following search order.
 * </p>
 * <table style="border: 1px solid black; border-collapse: separate; border-spacing: 0px;">
 *     <caption style="font-weight: bold; font-size: 1.2em">Request Authority Search Order</caption>
 *     <colgroup>
 *         <col><col style="width: 15%"><col><col><col><col>
 *     </colgroup>
 *     <thead style="background-color: lightgray">
 *         <tr>
 *             <th>#</th>
 *             <th>Value Origin</th>
 *             <th>Host</th>
 *             <th>Port</th>
 *             <th>Protocol</th>
 *             <th>Notes</th>
 *         </tr>
 *     </thead>
 *     <tbody style="text-align: left; vertical-align: top;">
 *         <tr>
 *             <td>1</td>
 *             <td><code>Forwarded</code> Header</td>
 *             <td>"{@code host=<host>}" param (Required)</td>
 *             <td>"{@code host=<host>:<port>} param (Implied)</td>
 *             <td>"{@code proto=<value>}" param (Optional)</td>
 *             <td>From left-most relevant parameter (see <a href="https://tools.ietf.org/html/rfc7239">rfc7239</a>)</td>
 *         </tr>
 *         <tr>
 *             <td>2</td>
 *             <td><code>X-Forwarded-Host</code> Header</td>
 *             <td>Required</td>
 *             <td>Implied</td>
 *             <td>n/a</td>
 *             <td>left-most value</td>
 *         </tr>
 *         <tr>
 *             <td>3</td>
 *             <td><code>X-Forwarded-Port</code> Header</td>
 *             <td>n/a</td>
 *             <td>Required</td>
 *             <td>n/a</td>
 *             <td>left-most value (only if {@link #getForwardedPortAsAuthority()} is true)</td>
 *         </tr>
 *         <tr>
 *             <td>4</td>
 *             <td><code>X-Forwarded-Server</code> Header</td>
 *             <td>Required</td>
 *             <td>Optional</td>
 *             <td>n/a</td>
 *             <td>left-most value</td>
 *         </tr>
 *         <tr>
 *             <td>5</td>
 *             <td><code>X-Forwarded-Proto</code> Header</td>
 *             <td>n/a</td>
 *             <td>Implied from value</td>
 *             <td>Required</td>
 *             <td>
 *                 <p>left-most value becomes protocol.</p>
 *                 <ul>
 *                     <li>Value of "<code>http</code>" means port=80.</li>
 *                     <li>Value of "{@link HttpConfiguration#getSecureScheme()}" means port={@link HttpConfiguration#getSecurePort()}.</li>
 *                 </ul>
 *             </td>
 *         </tr>
 *         <tr>
 *             <td>6</td>
 *             <td><code>X-Proxied-Https</code> Header</td>
 *             <td>n/a</td>
 *             <td>Implied from value</td>
 *             <td>boolean</td>
 *             <td>
 *                 <p>left-most value determines protocol and port.</p>
 *                 <ul>
 *                     <li>Value of "<code>on</code>" means port={@link HttpConfiguration#getSecurePort()}, and protocol={@link HttpConfiguration#getSecureScheme()}).</li>
 *                     <li>Value of "<code>off</code>" means port=80, and protocol=http.</li>
 *                 </ul>
 *             </td>
 *         </tr>
 *     </tbody>
 * </table>
 *
 * @see <a href="http://en.wikipedia.org/wiki/X-Forwarded-For">Wikipedia: X-Forwarded-For</a>
 * @see <a href="https://tools.ietf.org/html/rfc7239">RFC 7239: Forwarded HTTP Extension</a>
 */
public class ForwardedRequestCustomizer implements HttpConfiguration.Customizer
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpConnection.class);

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
    private final Index.Mutable<MethodHandle> _handles = new Index.Builder<MethodHandle>()
        .caseSensitive(false)
        .mutable()
        .build();

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
     * Set a forced valued for the host header.
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
    public Request customize(Request request, HttpFields.Mutable responseHeaders)
    {
        HttpConfiguration httpConfig = request.getConnectionMetaData().getHttpConfiguration();
        HttpFields httpFields = request.getHeaders();

        // Do a single pass through the header fields as it is a more efficient single iteration.
        Forwarded forwarded = new Forwarded(request, httpConfig);
        boolean match = false;
        for (HttpField field : httpFields)
        {
            try
            {
                MethodHandle handle = _handles.get(field.getName());
                if (handle != null)
                {
                    match = true;
                    handle.invoke(forwarded, field);
                }
            }
            catch (Throwable t)
            {
                onError(field, t);
            }
        }

        if (!match)
            return request;

        HttpURI uri;
        boolean secure;
        HostPortHttpField authority;
        InetSocketAddress remote;

        HttpURI.Mutable builder = HttpURI.build(request.getHttpURI());
        boolean httpUriChanged = false;

        if (LOG.isDebugEnabled())
            LOG.debug("forwarded {} {}", builder, forwarded);

        // Is secure status configured from headers?
        secure = forwarded.isSecure();

        // Set Scheme from configured protocol
        if (forwarded._proto != null)
        {
            builder.scheme(forwarded._proto);
            httpUriChanged = true;
        }
        // Set scheme if header implies secure scheme is to be used (see #isSslIsSecure())
        else if (forwarded._secureScheme)
        {
            builder.scheme(httpConfig.getSecureScheme());
            httpUriChanged = true;
        }

        // Use authority from headers, if configured.
        if (forwarded._authority != null)
        {
            String host = forwarded._authority._host;
            int port = forwarded._authority._port;

            // Fall back to request metadata if needed.
            if (host == null)
            {
                host = builder.getHost();
            }

            if (port == MutableHostPort.UNSET) // is unset by headers
            {
                port = builder.getPort();
            }

            // Don't change port if port == IMPLIED.
            if (request.getHttpURI().getPort() == 0 && port > 0 && port == HttpScheme.CACHE.get(httpConfig.getSecureScheme()).getDefaultPort())
                port = 0;

            // Update authority if different from metadata
            if (!host.equalsIgnoreCase(builder.getHost()) ||
                port != builder.getPort())
            {
                authority = new HostPortHttpField(host, port);
                builder.authority(host, port);
                httpUriChanged = true;
            }
            else
            {
                authority = null;
            }
        }
        else
        {
            authority = null;
        }

        uri = httpUriChanged ? builder.asImmutable() : request.getHttpURI();

        // Set Remote Address
        if (forwarded.hasFor())
        {
            int forPort = forwarded._for._port;
            if (forPort <= 0)
            {
                // TODO utility methods for this would be nice.
                SocketAddress addr = request.getConnectionMetaData().getRemoteSocketAddress();
                if (addr instanceof InetSocketAddress)
                    forPort = ((InetSocketAddress)addr).getPort();
            }
            remote = InetSocketAddress.createUnresolved(forwarded._for._host, forPort);
        }
        else
        {
            remote = null;
        }

        ConnectionMetaData connectionMetaData = new ConnectionMetaData.Wrapper(request.getConnectionMetaData())
        {
            @Override
            public SocketAddress getRemoteSocketAddress()
            {
                return remote != null ? remote : super.getRemoteSocketAddress();
            }

            @Override
            public HostPort getServerAuthority()
            {
                if (authority != null)
                    return authority.getHostPort();

                return super.getServerAuthority();
            }

            @Override
            public String toString()
            {
                return "%s@%x{id=%s,remote=%s,authority=%s,%s}".formatted(
                    TypeUtil.toShortName(this.getClass()),
                    hashCode(),
                    getId(),
                    remote,
                    authority,
                    getWrappedConnectionMetaData()
                );
            }
        };

        HttpFields headers = authority == null
            ? request.getHeaders()
            : HttpFields.build(request.getHeaders(), authority);

        return new Request.Wrapper(request)
        {
            @Override
            public HttpURI getHttpURI()
            {
                return uri;
            }

            @Override
            public HttpFields getHeaders()
            {
                return headers;
            }

            @Override
            public boolean isSecure()
            {
                return secure || super.isSecure();
            }

            @Override
            public ConnectionMetaData getConnectionMetaData()
            {
                return connectionMetaData;
            }
        };
    }

    protected static int getSecurePort(HttpConfiguration config)
    {
        return config.getSecurePort() > 0 ? config.getSecurePort() : 443;
    }

    protected void onError(HttpField field, Throwable t)
    {
        throw new BadMessageException("Bad header value for " + field.getName(), t);
    }

    protected static String getLeftMost(String headerValue)
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

    public String getHostHeader()
    {
        return _forcedHost.getValue();
    }

    /**
     * Set a forced valued for the host header.
     *
     * @param hostHeader The value of the host header to force.
     */
    public void setHostHeader(String hostHeader)
    {
        _forcedHost = new HostPortHttpField(hostHeader);
    }

    private void updateHandles()
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try
        {
            updateForwardedHandle(lookup, getForwardedHeader(), "handleRFC7239");
            updateForwardedHandle(lookup, getForwardedHostHeader(), "handleForwardedHost");
            updateForwardedHandle(lookup, getForwardedForHeader(), "handleForwardedFor");
            updateForwardedHandle(lookup, getForwardedPortHeader(), "handleForwardedPort");
            updateForwardedHandle(lookup, getForwardedProtoHeader(), "handleProto");
            updateForwardedHandle(lookup, getForwardedHttpsHeader(), "handleHttps");
            updateForwardedHandle(lookup, getForwardedServerHeader(), "handleForwardedServer");
            updateForwardedHandle(lookup, getForwardedCipherSuiteHeader(), "handleCipherSuite");
            updateForwardedHandle(lookup, getForwardedSslSessionIdHeader(), "handleSslSessionId");
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private void updateForwardedHandle(MethodHandles.Lookup lookup, String headerName, String forwardedMethodName) throws NoSuchMethodException, IllegalAccessException
    {
        final MethodType type = methodType(void.class, HttpField.class);

        if (StringUtil.isBlank(headerName))
            return;

        _handles.put(headerName, lookup.findVirtual(Forwarded.class, forwardedMethodName, type));
    }

    private static class MutableHostPort
    {
        public static final int UNSET = -1;
        public static final int IMPLIED = 0;

        String _host;
        Source _hostSource = Source.UNSET;
        int _port = UNSET;
        Source _portSource = Source.UNSET;

        public void setHostPort(String host, int port, Source source)
        {
            setHost(host, source);
            setPort(port, source);
        }

        public void setHost(String host, Source source)
        {
            if (source.priority() > _hostSource.priority())
            {
                _host = host;
                _hostSource = source;
            }
        }

        public void setPort(int port, Source source)
        {
            if (source.priority() > _portSource.priority())
            {
                _port = port;
                _portSource = source;
            }
        }

        public void setHostPort(HostPort hostPort, Source source)
        {
            if (source.priority() > _hostSource.priority())
            {
                _host = hostPort.getHost();
                _hostSource = source;
            }

            int port = hostPort.getPort();
            // Is port supplied?
            if (port > 0 && source.priority() > _portSource.priority())
            {
                _port = hostPort.getPort();
                _portSource = source;
            }
            // Since we are Host:Port pair, the port could be unspecified
            // Meaning it's implied.
            // Make sure that we switch the tracked port from unset to implied
            else if (_port == UNSET)
            {
                // set port to implied (with no priority)
                _port = IMPLIED;
            }
        }

        @Override
        public String toString()
        {
            final StringBuilder sb = new StringBuilder("MutableHostPort{");
            sb.append("host='").append(_host).append("'/").append(_hostSource);
            sb.append(", port=").append(_port);
            sb.append("/").append(_portSource);
            sb.append('}');
            return sb.toString();
        }
    }

    /**
     * Ordered Source Enum.
     * <p>
     * Lowest first, Last/Highest priority wins
     * </p>
     */
    public enum Source
    {
        UNSET,
        XPROXIED_HTTPS,
        XFORWARDED_PROTO,
        XFORWARDED_SERVER,
        XFORWARDED_PORT,
        XFORWARDED_FOR,
        XFORWARDED_HOST,
        FORWARDED,
        FORCED;

        int priority()
        {
            return ordinal();
        }
    }

    private class Forwarded extends QuotedCSVParser
    {
        HttpConfiguration _config;
        Request _request;

        MutableHostPort _authority;
        MutableHostPort _for;
        String _proto;
        Source _protoSource = Source.UNSET;
        Boolean _secure;
        boolean _secureScheme = false;

        public Forwarded(Request request, HttpConfiguration config)
        {
            super(false);
            _request = request;
            _config = config;
            if (_forcedHost != null)
            {
                getAuthority().setHostPort(
                    _forcedHost.getHostPort().getHost(),
                    _forcedHost.getHostPort().getPort(),
                    Source.FORCED);
            }
        }

        public boolean isSecure()
        {
            return (_secure != null && _secure);
        }

        public boolean hasFor()
        {
            return _for != null && _for._host != null;
        }

        private MutableHostPort getAuthority()
        {
            if (_authority == null)
            {
                _authority = new MutableHostPort();
            }
            return _authority;
        }

        private MutableHostPort getFor()
        {
            if (_for == null)
            {
                _for = new MutableHostPort();
            }
            return _for;
        }

        /**
         * Called if header is <code>Proxy-auth-cert</code>
         */
        public void handleCipherSuite(HttpField field)
        {
            _request.setAttribute("jakarta.servlet.request.cipher_suite", field.getValue());

            // Is ForwardingRequestCustomizer configured to trigger isSecure and scheme change on this header?
            if (isSslIsSecure())
            {
                _secure = true;
                // track desire for secure scheme, actual protocol will be resolved later.
                _secureScheme = true;
            }
        }

        /**
         * Called if header is <code>Proxy-Ssl-Id</code>
         */
        public void handleSslSessionId(HttpField field)
        {
            _request.setAttribute("jakarta.servlet.request.ssl_session_id", field.getValue());

            // Is ForwardingRequestCustomizer configured to trigger isSecure and scheme change on this header?
            if (isSslIsSecure())
            {
                _secure = true;
                // track desire for secure scheme, actual protocol will be resolved later.
                _secureScheme = true;
            }
        }

        /**
         * Called if header is <code>X-Forwarded-Host</code>
         */
        public void handleForwardedHost(HttpField field)
        {
            updateAuthority(getLeftMost(field.getValue()), Source.XFORWARDED_HOST);
        }

        /**
         * Called if header is <code>X-Forwarded-For</code>
         */
        public void handleForwardedFor(HttpField field)
        {
            HostPort hostField = new HostPort(getLeftMost(field.getValue()));
            getFor().setHostPort(hostField, Source.XFORWARDED_FOR);
        }

        /**
         * Called if header is <code>X-Forwarded-Server</code>
         */
        public void handleForwardedServer(HttpField field)
        {
            if (getProxyAsAuthority())
                return;
            updateAuthority(getLeftMost(field.getValue()), Source.XFORWARDED_SERVER);
        }

        /**
         * Called if header is <code>X-Forwarded-Port</code>
         */
        public void handleForwardedPort(HttpField field)
        {
            int port = HostPort.parsePort(getLeftMost(field.getValue()));

            updatePort(port, Source.XFORWARDED_PORT);
        }

        /**
         * Called if header is <code>X-Forwarded-Proto</code>
         */
        public void handleProto(HttpField field)
        {
            updateProto(getLeftMost(field.getValue()), Source.XFORWARDED_PROTO);
        }

        /**
         * Called if header is <code>X-Proxied-Https</code>
         */
        public void handleHttps(HttpField field)
        {
            if ("on".equalsIgnoreCase(field.getValue()) || "true".equalsIgnoreCase(field.getValue()))
            {
                _secure = true;
                updateProto(HttpScheme.HTTPS.asString(), Source.XPROXIED_HTTPS);
                updatePort(getSecurePort(_config), Source.XPROXIED_HTTPS);
            }
            else if ("off".equalsIgnoreCase(field.getValue()) || "false".equalsIgnoreCase(field.getValue()))
            {
                _secure = false;
                updateProto(HttpScheme.HTTP.asString(), Source.XPROXIED_HTTPS);
                updatePort(MutableHostPort.IMPLIED, Source.XPROXIED_HTTPS);
            }
            else
            {
                throw new BadMessageException("Invalid value for " + field.getName());
            }
        }

        /**
         * Called if header is <code>Forwarded</code>
         */
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
                    {
                        if (!getProxyAsAuthority())
                            break;
                        if (value.startsWith("_") || "unknown".equals(value))
                            break;
                        HostPort hostField = new HostPort(value);
                        getAuthority().setHostPort(hostField.getHost(), hostField.getPort(), Source.FORWARDED);
                        break;
                    }
                    case "for":
                    {
                        if (value.startsWith("_") || "unknown".equals(value))
                            break;
                        HostPort hostField = new HostPort(value);
                        getFor().setHostPort(hostField.getHost(), hostField.getPort(), Source.FORWARDED);
                        break;
                    }
                    case "host":
                    {
                        if (value.startsWith("_") || "unknown".equals(value))
                            break;
                        HostPort hostField = new HostPort(value);
                        getAuthority().setHostPort(hostField.getHost(), hostField.getPort(), Source.FORWARDED);
                        break;
                    }
                    case "proto":
                        updateProto(value, Source.FORWARDED);
                        break;
                }
            }
        }

        private void updateAuthority(String value, Source source)
        {
            HostPort hostField = new HostPort(value);
            getAuthority().setHostPort(hostField, source);
        }

        private void updatePort(int port, Source source)
        {
            if (getForwardedPortAsAuthority())
            {
                getAuthority().setPort(port, source);
            }
            else
            {
                getFor().setPort(port, source);
            }
        }

        private void updateProto(String proto, Source source)
        {
            if (source.priority() > _protoSource.priority())
            {
                _proto = proto;
                _protoSource = source;

                if (_proto.equalsIgnoreCase(_config.getSecureScheme()))
                {
                    _secure = true;
                }
            }
        }

        @Override
        public String toString()
        {
            return String.format("Forwarded@%x[req=%s,auth=%s,for=%s,proto=%s,sec=%s/%s]",
                hashCode(),
                _request,
                _authority,
                _for,
                _proto,
                _secure,
                _secureScheme);
        }
    }
}
