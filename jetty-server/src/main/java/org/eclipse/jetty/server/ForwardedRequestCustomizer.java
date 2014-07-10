//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.server.HttpConfiguration.Customizer;


/* ------------------------------------------------------------ */
/** Customize Requests for Proxy Forwarding.
 * <p>
 * This customizer looks at at HTTP request for headers that indicate
 * it has been forwarded by one or more proxies.  Specifically handled are:
 * <ul>
 * <li>X-Forwarded-Host</li>
 * <li>X-Forwarded-Server</li>
 * <li>X-Forwarded-For</li>
 * <li>X-Forwarded-Proto</li>
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
    private String _hostHeader;
    private String _forwardedHostHeader = HttpHeader.X_FORWARDED_HOST.toString();
    private String _forwardedServerHeader = HttpHeader.X_FORWARDED_SERVER.toString();
    private String _forwardedForHeader = HttpHeader.X_FORWARDED_FOR.toString();
    private String _forwardedProtoHeader = HttpHeader.X_FORWARDED_PROTO.toString();
    private String _forwardedCipherSuiteHeader;
    private String _forwardedSslSessionIdHeader;
    

    /* ------------------------------------------------------------ */
    public String getHostHeader()
    {
        return _hostHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set a forced valued for the host header to control what is returned by {@link ServletRequest#getServerName()} and {@link ServletRequest#getServerPort()}.
     *
     * @param hostHeader
     *            The value of the host header to force.
     */
    public void setHostHeader(String hostHeader)
    {
        _hostHeader = hostHeader;
    }

    /* ------------------------------------------------------------ */
    /*
     *
     * @see #setForwarded(boolean)
     */
    public String getForwardedHostHeader()
    {
        return _forwardedHostHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedHostHeader
     *            The header name for forwarded hosts (default x-forwarded-host)
     */
    public void setForwardedHostHeader(String forwardedHostHeader)
    {
        _forwardedHostHeader = forwardedHostHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the header name for forwarded server.
     */
    public String getForwardedServerHeader()
    {
        return _forwardedServerHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedServerHeader
     *            The header name for forwarded server (default x-forwarded-server)
     */
    public void setForwardedServerHeader(String forwardedServerHeader)
    {
        _forwardedServerHeader = forwardedServerHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the forwarded for header
     */
    public String getForwardedForHeader()
    {
        return _forwardedForHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedRemoteAddressHeader
     *            The header name for forwarded for (default x-forwarded-for)
     */
    public void setForwardedForHeader(String forwardedRemoteAddressHeader)
    {
        _forwardedForHeader = forwardedRemoteAddressHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the forwardedProtoHeader.
     *
     * @return the forwardedProtoHeader (default X-Forwarded-For)
     */
    public String getForwardedProtoHeader()
    {
        return _forwardedProtoHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the forwardedProtoHeader.
     *
     * @param forwardedProtoHeader
     *            the forwardedProtoHeader to set (default X-Forwarded-For)
     */
    public void setForwardedProtoHeader(String forwardedProtoHeader)
    {
        _forwardedProtoHeader = forwardedProtoHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The header name holding a forwarded cipher suite (default null)
     */
    public String getForwardedCipherSuiteHeader()
    {
        return _forwardedCipherSuiteHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedCipherSuite
     *            The header name holding a forwarded cipher suite (default null)
     */
    public void setForwardedCipherSuiteHeader(String forwardedCipherSuite)
    {
        _forwardedCipherSuiteHeader = forwardedCipherSuite;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The header name holding a forwarded SSL Session ID (default null)
     */
    public String getForwardedSslSessionIdHeader()
    {
        return _forwardedSslSessionIdHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedSslSessionId
     *            The header name holding a forwarded SSL Session ID (default null)
     */
    public void setForwardedSslSessionIdHeader(String forwardedSslSessionId)
    {
        _forwardedSslSessionIdHeader = forwardedSslSessionId;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void customize(Connector connector, HttpConfiguration config, Request request)
    {
        HttpFields httpFields = request.getHttpFields();

        // Do SSL first
        if (getForwardedCipherSuiteHeader()!=null)
        {
            String cipher_suite=httpFields.getStringField(getForwardedCipherSuiteHeader());
            if (cipher_suite!=null)
                request.setAttribute("javax.servlet.request.cipher_suite",cipher_suite);
        }
        if (getForwardedSslSessionIdHeader()!=null)
        {
            String ssl_session_id=httpFields.getStringField(getForwardedSslSessionIdHeader());
            if(ssl_session_id!=null)
            {
                request.setAttribute("javax.servlet.request.ssl_session_id", ssl_session_id);
                request.setScheme(HttpScheme.HTTPS.asString());
            }
        }

        // Retrieving headers from the request
        String forwardedHost = getLeftMostFieldValue(httpFields,getForwardedHostHeader());
        String forwardedServer = getLeftMostFieldValue(httpFields,getForwardedServerHeader());
        String forwardedFor = getLeftMostFieldValue(httpFields,getForwardedForHeader());
        String forwardedProto = getLeftMostFieldValue(httpFields,getForwardedProtoHeader());

        if (_hostHeader != null)
        {
            // Update host header
            httpFields.put(HttpHeader.HOST.toString(),_hostHeader);
            request.setServerName(null);
            request.setServerPort(-1);
            request.getServerName();
        }
        else if (forwardedHost != null)
        {
            // Update host header
            httpFields.put(HttpHeader.HOST.toString(),forwardedHost);
            request.setServerName(null);
            request.setServerPort(-1);
            request.getServerName();
        }
        else if (forwardedServer != null)
        {
            // Use provided server name
            request.setServerName(forwardedServer);
        }

        if (forwardedFor != null)
        {
            request.setRemoteAddr(InetSocketAddress.createUnresolved(forwardedFor,request.getRemotePort()));
        }

        if (forwardedProto != null)
        {
            request.setScheme(forwardedProto);
            if (forwardedProto.equals(config.getSecureScheme()))
                request.setSecure(true);
        }
    }

    /* ------------------------------------------------------------ */
    protected String getLeftMostFieldValue(HttpFields fields, String header)
    {
        if (header == null)
            return null;

        String headerValue = fields.getStringField(header);

        if (headerValue == null)
            return null;

        int commaIndex = headerValue.indexOf(',');

        if (commaIndex == -1)
        {
            // Single value
            return headerValue;
        }

        // The left-most value is the farthest downstream client
        return headerValue.substring(0,commaIndex);
    }


    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s@%x",this.getClass().getSimpleName(),hashCode());
    }
}
