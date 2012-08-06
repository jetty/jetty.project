package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetSocketAddress;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.servlet.ServletRequest;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.ssl.SslCertificates;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;


public class HttpConfiguration extends AggregateLifeCycle
{
    static final Logger LOG = Log.getLogger(HttpConfiguration.class);

    private final SslContextFactory _sslContextFactory;
    private final boolean _ssl;

    private String _integralScheme = HttpScheme.HTTPS.asString();
    private int _integralPort = 0;
    private String _confidentialScheme = HttpScheme.HTTPS.asString();
    private int _confidentialPort = 0;
    private boolean _forwarded;
    private String _hostHeader;
    private String _forwardedHostHeader = HttpHeader.X_FORWARDED_HOST.toString();
    private String _forwardedServerHeader = HttpHeader.X_FORWARDED_SERVER.toString();
    private String _forwardedForHeader = HttpHeader.X_FORWARDED_FOR.toString();
    private String _forwardedProtoHeader = HttpHeader.X_FORWARDED_PROTO.toString();
    private String _forwardedCipherSuiteHeader;
    private String _forwardedSslSessionIdHeader;
    private int _requestHeaderSize=6*1024;
    private int _requestBufferSize=16*1024;
    private int _responseHeaderSize=6*1024;
    private int _responseBufferSize=16*1024;

    public HttpConfiguration(SslContextFactory sslContextFactory,boolean ssl)
    {
        _sslContextFactory=sslContextFactory!=null?sslContextFactory:ssl?new SslContextFactory(SslContextFactory.DEFAULT_KEYSTORE_PATH):null;
        _ssl=ssl;
        if (_sslContextFactory!=null)
            addBean(_sslContextFactory,sslContextFactory==null);
    }

    public SslContextFactory getSslContextFactory()
    {
        return _sslContextFactory;
    }

    public boolean isSecure()
    {
        return _ssl;
    }

    public int getRequestHeaderSize()
    {
        return _requestHeaderSize;
    }

    public void setRequestHeaderSize(int requestHeaderSize)
    {
        _requestHeaderSize = requestHeaderSize;
    }

    public int getRequestBufferSize()
    {
        return _requestBufferSize;
    }

    public void setRequestBufferSize(int requestBufferSize)
    {
        _requestBufferSize = requestBufferSize;
    }

    public int getResponseHeaderSize()
    {
        return _responseHeaderSize;
    }

    public void setResponseHeaderSize(int responseHeaderSize)
    {
        _responseHeaderSize = responseHeaderSize;
    }

    public int getResponseBufferSize()
    {
        return _responseBufferSize;
    }

    public void setResponseBufferSize(int responseBufferSize)
    {
        _responseBufferSize = responseBufferSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Allow the Listener a chance to customise the request. before the server
     * does its stuff. <br>
     * This allows the required attributes to be set for SSL requests. <br>
     * The requirements of the Servlet specs are:
     * <ul>
     * <li> an attribute named "javax.servlet.request.ssl_session_id" of type
     * String (since Servlet Spec 3.0).</li>
     * <li> an attribute named "javax.servlet.request.cipher_suite" of type
     * String.</li>
     * <li> an attribute named "javax.servlet.request.key_size" of type Integer.</li>
     * <li> an attribute named "javax.servlet.request.X509Certificate" of type
     * java.security.cert.X509Certificate[]. This is an array of objects of type
     * X509Certificate, the order of this array is defined as being in ascending
     * order of trust. The first certificate in the chain is the one set by the
     * client, the next is the one used to authenticate the first, and so on.
     * </li>
     * </ul>
     */
    public void customize(Request request) throws IOException
    {
        if (isSecure())
        {
            request.setScheme(HttpScheme.HTTPS.asString());
            SslConnection.DecryptedEndPoint ssl_endp = (SslConnection.DecryptedEndPoint)request.getHttpChannel().getEndPoint();
            SslConnection sslConnection = ssl_endp.getSslConnection();
            SSLEngine sslEngine=sslConnection.getSSLEngine();
            SslCertificates.customize(sslEngine,request);
        }

        request.setTimeStamp(System.currentTimeMillis());
        if (isForwarded())
            checkForwardedHeaders(request);
    }

    /* ------------------------------------------------------------ */
    protected void checkForwardedHeaders(Request request) throws IOException
    {
        HttpFields httpFields = request.getHttpChannel().getRequestFields();

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
            request.setRemoteAddr(new InetSocketAddress(forwardedFor,request.getRemotePort()));
        }

        if (forwardedProto != null)
        {
            request.setScheme(forwardedProto);
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
    /*
     * @see org.eclipse.jetty.server.Connector#getConfidentialPort()
     */
    public int getConfidentialPort()
    {
        return _confidentialPort;
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Connector#getConfidentialScheme()
     */
    public String getConfidentialScheme()
    {
        return _confidentialScheme;
    }

    /* ------------------------------------------------------------ */
    /**
     * The request is integral IFF it is secure AND the server port
     * matches any configured {@link #getIntegralPort()}.
     * This allows separation of listeners providing INTEGRAL versus
     * CONFIDENTIAL constraints, such as one SSL listener configured to require
     * client certs providing CONFIDENTIAL, whereas another SSL listener not
     * requiring client certs providing mere INTEGRAL constraints.
     * <p>
     * The request is secure if it is SSL or it {@link #isForwarded()} is true
     * and the scheme matches {@link #getIntegralScheme()()}
     */
    public boolean isIntegral(Request request)
    {
        boolean https = isSecure() || _forwarded && _integralScheme.equalsIgnoreCase(request.getScheme());
        int iPort=getIntegralPort();
        boolean port = iPort<=0||iPort==request.getServerPort();
        return https && port;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Connector#getConfidentialPort()
     */
    public int getIntegralPort()
    {
        return _integralPort;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.Connector#getIntegralScheme()
     */
    public String getIntegralScheme()
    {
        return _integralScheme;
    }

    /* ------------------------------------------------------------ */
    /**
     * The request is confidential IFF it is secure AND the server port
     * matches any configured {@link #getConfidentialPort()}.
     * This allows separation of listeners providing INTEGRAL versus
     * CONFIDENTIAL constraints, such as one SSL listener configured to require
     * client certs providing CONFIDENTIAL, whereas another SSL listener not
     * requiring client certs providing mere INTEGRAL constraints.
     * <p>
     * The request is secure if it is SSL or it {@link #isForwarded()} is true
     * and the scheme matches {@link #getConfidentialScheme()}
     */
    public boolean isConfidential(Request request)
    {
        boolean https = isSecure() || _forwarded && _confidentialScheme.equalsIgnoreCase(request.getScheme());
        int confidentialPort=getConfidentialPort();
        boolean port = confidentialPort<=0||confidentialPort==request.getServerPort();
        return https && port;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param confidentialPort
     *            The confidentialPort to set.
     */
    public void setConfidentialPort(int confidentialPort)
    {
        _confidentialPort = confidentialPort;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param confidentialScheme
     *            The confidentialScheme to set.
     */
    public void setConfidentialScheme(String confidentialScheme)
    {
        _confidentialScheme = confidentialScheme;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param integralPort
     *            The integralPort to set.
     */
    public void setIntegralPort(int integralPort)
    {
        _integralPort = integralPort;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param integralScheme
     *            The integralScheme to set.
     */
    public void setIntegralScheme(String integralScheme)
    {
        _integralScheme = integralScheme;
    }

    /* ------------------------------------------------------------ */
    /**
     * Is reverse proxy handling on?
     *
     * @return true if this connector is checking the x-forwarded-for/host/server headers
     */
    public boolean isForwarded()
    {
        return _forwarded;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set reverse proxy handling. If set to true, then the X-Forwarded headers (or the headers set in their place) are looked for to set the request protocol,
     * host, server and client ip.
     *
     * @param check true if this connector is checking the x-forwarded-for/host/server headers
     * @see #setForwardedForHeader(String)
     * @see #setForwardedHostHeader(String)
     * @see #setForwardedProtoHeader(String)
     * @see #setForwardedServerHeader(String)
     */
    public void setForwarded(boolean check)
    {
        if (check)
            LOG.debug("{} is forwarded",this);
        _forwarded = check;
    }

    /* ------------------------------------------------------------ */
    public String getHostHeader()
    {
        return _hostHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set a forced valued for the host header to control what is returned by {@link ServletRequest#getServerName()} and {@link ServletRequest#getServerPort()}.
     * This value is only used if {@link #isForwarded()} is true.
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
     * @see #setForwarded(boolean)
     */
    public void setForwardedHostHeader(String forwardedHostHeader)
    {
        _forwardedHostHeader = forwardedHostHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the header name for forwarded server.
     * @see #setForwarded(boolean)
     */
    public String getForwardedServerHeader()
    {
        return _forwardedServerHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedServerHeader
     *            The header name for forwarded server (default x-forwarded-server)
     * @see #setForwarded(boolean)
     */
    public void setForwardedServerHeader(String forwardedServerHeader)
    {
        _forwardedServerHeader = forwardedServerHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the forwarded for header
     * @see #setForwarded(boolean)
     */
    public String getForwardedForHeader()
    {
        return _forwardedForHeader;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param forwardedRemoteAddressHeader
     *            The header name for forwarded for (default x-forwarded-for)
     * @see #setForwarded(boolean)
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
     * @see #setForwarded(boolean)
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
     * @see #setForwarded(boolean)
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
    protected void doStart() throws Exception
    {
        if (_sslContextFactory!=null)
        {
            _sslContextFactory.checkKeyStore();

            super.doStart();

            SSLEngine sslEngine = _sslContextFactory.newSSLEngine();

            sslEngine.setUseClientMode(false);

            SSLSession sslSession = sslEngine.getSession();

            if (getRequestHeaderSize()<sslSession.getApplicationBufferSize())
                setRequestHeaderSize(sslSession.getApplicationBufferSize());
            if (getRequestBufferSize()<sslSession.getApplicationBufferSize())
                setRequestBufferSize(sslSession.getApplicationBufferSize());
        }
        else
            super.doStart();
    }
}
