//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.net.InetSocketAddress;

import javax.servlet.ServletRequest;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.server.HttpConfiguration.Customizer;
import org.eclipse.jetty.util.StringUtil;


/* ------------------------------------------------------------ */
/** Customize Requests for Proxy Forwarding.
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
 * @see <a href="http://en.wikipedia.org/wiki/X-Forwarded-For">Wikipedia: X-Forwarded-For</a>
 */
public class ForwardedRequestCustomizer implements Customizer
{
    private HostPortHttpField _forcedHost;
    private String _forwardedHeader = HttpHeader.FORWARDED.toString();
    private String _forwardedHostHeader = HttpHeader.X_FORWARDED_HOST.toString();
    private String _forwardedServerHeader = HttpHeader.X_FORWARDED_SERVER.toString();
    private String _forwardedForHeader = HttpHeader.X_FORWARDED_FOR.toString();
    private String _forwardedProtoHeader = HttpHeader.X_FORWARDED_PROTO.toString();
    private String _forwardedHttpsHeader = "X-Proxied-Https";
    private String _forwardedCipherSuiteHeader = "Proxy-auth-cert";
    private String _forwardedSslSessionIdHeader = "Proxy-ssl-id";
    private boolean _proxyAsAuthority=false;
    private boolean _sslIsSecure=true;
    
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
     * Configure to only support the RFC7239 Forwarded header and to
     * not support any {@code X-Forwarded-} headers.   This convenience method
     * clears all the non RFC headers if passed true and sets them to
     * the default values (if not already set) if passed false.
     */
    public void setForwardedOnly(boolean rfc7239only)
    {
        if (rfc7239only)
        {
            if (_forwardedHeader==null)
                _forwardedHeader=HttpHeader.FORWARDED.toString();
            _forwardedHostHeader=null;
            _forwardedHostHeader=null;
            _forwardedServerHeader=null;
            _forwardedForHeader=null;
            _forwardedProtoHeader=null;
            _forwardedHttpsHeader=null;
        }
        else
        {
            if (_forwardedHostHeader==null)
                _forwardedHostHeader = HttpHeader.X_FORWARDED_HOST.toString();
            if (_forwardedServerHeader==null)
                _forwardedServerHeader = HttpHeader.X_FORWARDED_SERVER.toString();
            if (_forwardedForHeader==null)
                _forwardedForHeader = HttpHeader.X_FORWARDED_FOR.toString();
            if (_forwardedProtoHeader==null)
                _forwardedProtoHeader = HttpHeader.X_FORWARDED_PROTO.toString();
            if (_forwardedHttpsHeader==null)
                _forwardedHttpsHeader = "X-Proxied-Https";
        }
    }
    
    public String getForcedHost()
    {
        return _forcedHost.getValue();
    }
    
    /**
     * Set a forced valued for the host header to control what is returned by {@link ServletRequest#getServerName()} and {@link ServletRequest#getServerPort()}.
     *
     * @param hostAndPort
     *            The value of the host header to force.
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
     * @param forwardedHeader 
     *            The header name for RFC forwarded (default Forwarded)
     */
    public void setForwardedHeader(String forwardedHeader)
    {
        _forwardedHeader = forwardedHeader;
    }

    public String getForwardedHostHeader()
    {
        return _forwardedHostHeader;
    }

    /**
     * @param forwardedHostHeader
     *            The header name for forwarded hosts (default {@code X-Forwarded-Host})
     */
    public void setForwardedHostHeader(String forwardedHostHeader)
    {
        _forwardedHostHeader = forwardedHostHeader;
    }

    /**
     * @return the header name for forwarded server.
     */
    public String getForwardedServerHeader()
    {
        return _forwardedServerHeader;
    }

    /**
     * @param forwardedServerHeader
     *            The header name for forwarded server (default {@code X-Forwarded-Server})
     */
    public void setForwardedServerHeader(String forwardedServerHeader)
    {
        _forwardedServerHeader = forwardedServerHeader;
    }

    /**
     * @return the forwarded for header
     */
    public String getForwardedForHeader()
    {
        return _forwardedForHeader;
    }

    /**
     * @param forwardedRemoteAddressHeader
     *            The header name for forwarded for (default {@code X-Forwarded-For})
     */
    public void setForwardedForHeader(String forwardedRemoteAddressHeader)
    {
        _forwardedForHeader = forwardedRemoteAddressHeader;
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
     * @param forwardedProtoHeader
     *            the forwardedProtoHeader to set (default {@code X-Forwarded-Proto})
     */
    public void setForwardedProtoHeader(String forwardedProtoHeader)
    {
        _forwardedProtoHeader = forwardedProtoHeader;
    }

    /**
     * @return The header name holding a forwarded cipher suite (default {@code Proxy-auth-cert})
     */
    public String getForwardedCipherSuiteHeader()
    {
        return _forwardedCipherSuiteHeader;
    }

    /**
     * @param forwardedCipherSuite
     *            The header name holding a forwarded cipher suite (default {@code Proxy-auth-cert})
     */
    public void setForwardedCipherSuiteHeader(String forwardedCipherSuite)
    {
        _forwardedCipherSuiteHeader = forwardedCipherSuite;
    }

    /**
     * @return The header name holding a forwarded SSL Session ID (default {@code Proxy-ssl-id})
     */
    public String getForwardedSslSessionIdHeader()
    {
        return _forwardedSslSessionIdHeader;
    }

    /**
     * @param forwardedSslSessionId
     *            The header name holding a forwarded SSL Session ID (default {@code Proxy-ssl-id})
     */
    public void setForwardedSslSessionIdHeader(String forwardedSslSessionId)
    {
        _forwardedSslSessionIdHeader = forwardedSslSessionId;
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
        _forwardedHttpsHeader = forwardedHttpsHeader;
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

        RFC7239 rfc7239 = null;
        String forwardedHost = null;
        String forwardedServer = null;
        String forwardedFor = null;
        String forwardedProto = null;
        String forwardedHttps = null;
        
        // Do a single pass through the header fields as it is a more efficient single iteration.
        for (HttpField field : httpFields)
        {
            String name = field.getName();
            
            if (getForwardedCipherSuiteHeader()!=null && getForwardedCipherSuiteHeader().equalsIgnoreCase(name))
            {
                request.setAttribute("javax.servlet.request.cipher_suite",field.getValue());
                if (isSslIsSecure())
                {
                    request.setSecure(true);
                    request.setScheme(config.getSecureScheme());
                }
            }
            
            if (getForwardedSslSessionIdHeader()!=null && getForwardedSslSessionIdHeader().equalsIgnoreCase(name))
            {
                request.setAttribute("javax.servlet.request.ssl_session_id", field.getValue());
                if (isSslIsSecure())
                {
                    request.setSecure(true);
                    request.setScheme(config.getSecureScheme());
                }
            }
            
            if (forwardedHost==null && _forwardedHostHeader!=null && _forwardedHostHeader.equalsIgnoreCase(name))
                forwardedHost = getLeftMost(field.getValue());
            
            if (forwardedServer==null && _forwardedServerHeader!=null && _forwardedServerHeader.equalsIgnoreCase(name))
                forwardedServer = getLeftMost(field.getValue());
            
            if (forwardedFor==null && _forwardedForHeader!=null && _forwardedForHeader.equalsIgnoreCase(name))
                forwardedFor = getLeftMost(field.getValue());
            
            if (forwardedProto==null && _forwardedProtoHeader!=null && _forwardedProtoHeader.equalsIgnoreCase(name))
                forwardedProto = getLeftMost(field.getValue());
            
            if (forwardedHttps==null && _forwardedHttpsHeader!=null && _forwardedHttpsHeader.equalsIgnoreCase(name))
                forwardedHttps = getLeftMost(field.getValue());
            
            if (_forwardedHeader!=null && _forwardedHeader.equalsIgnoreCase(name))
            {
                if (rfc7239==null)
                    rfc7239= new RFC7239();
                rfc7239.addValue(field.getValue());
            }
        }
        
        // Handle host header if if not available any RFC7230.by or X-ForwardedServer header      
        if (_forcedHost != null)
        {
            // Update host header
            httpFields.put(_forcedHost);
            request.setAuthority(_forcedHost.getHost(),_forcedHost.getPort());
        }
        else if (rfc7239!=null && rfc7239._host!=null)
        {
            HostPortHttpField auth = rfc7239._host;
            httpFields.put(auth);
            request.setAuthority(auth.getHost(),auth.getPort());
        }
        else if (forwardedHost != null)
        {
            HostPortHttpField auth = new HostPortHttpField(forwardedHost);
            httpFields.put(auth);
            request.setAuthority(auth.getHost(),auth.getPort());
        }
        else if (_proxyAsAuthority)
        {
            if (rfc7239!=null && rfc7239._by!=null)
            {
                HostPortHttpField auth = rfc7239._by;
                httpFields.put(auth);
                request.setAuthority(auth.getHost(),auth.getPort());
            }
            else if (forwardedServer != null)
            {
                request.setAuthority(forwardedServer,request.getServerPort());
            }
        }

        // handle remote end identifier
        if (rfc7239!=null && rfc7239._for!=null)
        {
            request.setRemoteAddr(InetSocketAddress.createUnresolved(rfc7239._for.getHost(),rfc7239._for.getPort()));
        }
        else if (forwardedFor != null)
        {
            request.setRemoteAddr(InetSocketAddress.createUnresolved(forwardedFor,request.getRemotePort()));
        }

        // handle protocol identifier
        if (rfc7239!=null && rfc7239._proto!=null)
        {
            request.setScheme(rfc7239._proto);
            if (rfc7239._proto.equals(config.getSecureScheme()))
                request.setSecure(true);
        }
        else if (forwardedProto != null)
        {
            request.setScheme(forwardedProto);
            if (forwardedProto.equals(config.getSecureScheme()))
                request.setSecure(true);
        }
        else if (forwardedHttps !=null && ("on".equalsIgnoreCase(forwardedHttps)||"true".equalsIgnoreCase(forwardedHttps)))
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
        return headerValue.substring(0,commaIndex).trim();
    }
    
    @Override
    public String toString()
    {
        return String.format("%s@%x",this.getClass().getSimpleName(),hashCode());
    }

    @Deprecated
    public String getHostHeader()
    {
        return _forcedHost.getValue();
    }
    
    /**
     * Set a forced valued for the host header to control what is returned by {@link ServletRequest#getServerName()} and {@link ServletRequest#getServerPort()}.
     *
     * @param hostHeader
     *            The value of the host header to force.
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
            if (valueLength==0 && paramValue>paramName)
            {
                String name=StringUtil.asciiToLowerCase(buffer.substring(paramName,paramValue-1));
                String value=buffer.substring(paramValue);
                switch(name)
                {
                    case "by":
                        if (_by==null && !value.startsWith("_") && !"unknown".equals(value))
                            _by=new HostPortHttpField(value);
                        break;
                    case "for":
                        if (_for==null && !value.startsWith("_") && !"unknown".equals(value))
                            _for=new HostPortHttpField(value);
                        break;
                    case "host":
                        if (_host==null)
                            _host=new HostPortHttpField(value);
                        break;
                    case "proto":
                        if (_proto==null)
                            _proto=value;
                        break;
                }
            }
        }
    }
}
