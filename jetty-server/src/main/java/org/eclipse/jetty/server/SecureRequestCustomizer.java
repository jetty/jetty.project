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

import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.servlet.ServletRequest;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslConnection.DecryptedEndPoint;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SniX509ExtendedKeyManager;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.X509;

/**
 * <p>Customizer that extracts the attribute from an {@link SSLContext}
 * and sets them on the request with {@link ServletRequest#setAttribute(String, Object)}
 * according to Servlet Specification Requirements.</p>
 */
public class SecureRequestCustomizer implements HttpConfiguration.Customizer
{
    private static final Logger LOG = Log.getLogger(SecureRequestCustomizer.class);
    public static final String JAVAX_SERVLET_REQUEST_X_509_CERTIFICATE = "javax.servlet.request.X509Certificate";
    public static final String JAVAX_SERVLET_REQUEST_CIPHER_SUITE = "javax.servlet.request.cipher_suite";
    public static final String JAVAX_SERVLET_REQUEST_KEY_SIZE = "javax.servlet.request.key_size";
    public static final String JAVAX_SERVLET_REQUEST_SSL_SESSION_ID = "javax.servlet.request.ssl_session_id";

    private String sslSessionAttribute = "org.eclipse.jetty.servlet.request.ssl_session";

    private boolean _sniRequired;
    private boolean _sniHostCheck;
    private long _stsMaxAge = -1;
    private boolean _stsIncludeSubDomains;
    private HttpField _stsField;

    public SecureRequestCustomizer()
    {
        this(true);
    }

    public SecureRequestCustomizer(@Name("sniHostCheck") boolean sniHostCheck)
    {
        this(sniHostCheck, -1, false);
    }

    /**
     * @param sniHostCheck True if the SNI Host name must match.
     * @param stsMaxAgeSeconds The max age in seconds for a Strict-Transport-Security response header. If set less than zero then no header is sent.
     * @param stsIncludeSubdomains If true, a include subdomain property is sent with any Strict-Transport-Security header
     */
    public SecureRequestCustomizer(
        @Name("sniHostCheck") boolean sniHostCheck,
        @Name("stsMaxAgeSeconds") long stsMaxAgeSeconds,
        @Name("stsIncludeSubdomains") boolean stsIncludeSubdomains)
    {
        this(false, sniHostCheck, stsMaxAgeSeconds, stsIncludeSubdomains);
    }

    /**
     * @param sniRequired True if a SNI certificate is required.
     * @param sniHostCheck True if the SNI Host name must match.
     * @param stsMaxAgeSeconds The max age in seconds for a Strict-Transport-Security response header. If set less than zero then no header is sent.
     * @param stsIncludeSubdomains If true, a include subdomain property is sent with any Strict-Transport-Security header
     */
    public SecureRequestCustomizer(
        @Name("sniRequired") boolean sniRequired,
        @Name("sniHostCheck") boolean sniHostCheck,
        @Name("stsMaxAgeSeconds") long stsMaxAgeSeconds,
        @Name("stsIncludeSubdomains") boolean stsIncludeSubdomains)
    {
        _sniRequired = sniRequired;
        _sniHostCheck = sniHostCheck;
        _stsMaxAge = stsMaxAgeSeconds;
        _stsIncludeSubDomains = stsIncludeSubdomains;
        formatSTS();
    }

    /**
     * @return True if the SNI Host name must match when there is an SNI certificate.
     */
    public boolean isSniHostCheck()
    {
        return _sniHostCheck;
    }

    /**
     * @param sniHostCheck True if the SNI Host name must match when there is an SNI certificate.
     */
    public void setSniHostCheck(boolean sniHostCheck)
    {
        _sniHostCheck = sniHostCheck;
    }

    /**
     * @return True if SNI is required, else requests will be rejected with 400 response.
     * @see SslContextFactory.Server#isSniRequired()
     */
    public boolean isSniRequired()
    {
        return _sniRequired;
    }

    /**
     * @param sniRequired True if SNI is required, else requests will be rejected with 400 response.
     * @see SslContextFactory.Server#setSniRequired(boolean)
     */
    public void setSniRequired(boolean sniRequired)
    {
        _sniRequired = sniRequired;
    }

    /**
     * @return The max age in seconds for a Strict-Transport-Security response header. If set less than zero then no header is sent.
     */
    public long getStsMaxAge()
    {
        return _stsMaxAge;
    }

    /**
     * Set the Strict-Transport-Security max age.
     *
     * @param stsMaxAgeSeconds The max age in seconds for a Strict-Transport-Security response header. If set less than zero then no header is sent.
     */
    public void setStsMaxAge(long stsMaxAgeSeconds)
    {
        _stsMaxAge = stsMaxAgeSeconds;
        formatSTS();
    }

    /**
     * Convenience method to call {@link #setStsMaxAge(long)}
     *
     * @param period The period in units
     * @param units The {@link TimeUnit} of the period
     */
    public void setStsMaxAge(long period, TimeUnit units)
    {
        _stsMaxAge = units.toSeconds(period);
        formatSTS();
    }

    /**
     * @return true if a include subdomain property is sent with any Strict-Transport-Security header
     */
    public boolean isStsIncludeSubDomains()
    {
        return _stsIncludeSubDomains;
    }

    /**
     * @param stsIncludeSubDomains If true, a include subdomain property is sent with any Strict-Transport-Security header
     */
    public void setStsIncludeSubDomains(boolean stsIncludeSubDomains)
    {
        _stsIncludeSubDomains = stsIncludeSubDomains;
        formatSTS();
    }

    private void formatSTS()
    {
        if (_stsMaxAge < 0)
            _stsField = null;
        else
            _stsField = new PreEncodedHttpField(HttpHeader.STRICT_TRANSPORT_SECURITY, String.format("max-age=%d%s", _stsMaxAge, _stsIncludeSubDomains ? "; includeSubDomains" : ""));
    }

    @Override
    public void customize(Connector connector, HttpConfiguration channelConfig, Request request)
    {
        EndPoint endp = request.getHttpChannel().getEndPoint();
        if (endp instanceof DecryptedEndPoint)
        {
            SslConnection.DecryptedEndPoint sslEndp = (DecryptedEndPoint)endp;
            SslConnection sslConnection = sslEndp.getSslConnection();
            SSLEngine sslEngine = sslConnection.getSSLEngine();
            customize(sslEngine, request);

            if (request.getHttpURI().getScheme() == null)
                request.setScheme(HttpScheme.HTTPS.asString());
        }
        else if (endp instanceof ProxyConnectionFactory.ProxyEndPoint)
        {
            ProxyConnectionFactory.ProxyEndPoint proxy = (ProxyConnectionFactory.ProxyEndPoint)endp;
            if (request.getHttpURI().getScheme() == null && proxy.getAttribute(ProxyConnectionFactory.TLS_VERSION) != null)
                request.setScheme(HttpScheme.HTTPS.asString());
        }

        if (HttpScheme.HTTPS.is(request.getScheme()))
            customizeSecure(request);
    }

    /**
     * Customizes the request attributes for general secure settings.
     * The default impl calls {@link Request#setSecure(boolean)} with true
     * and sets a response header if the Strict-Transport-Security options
     * are set.
     *
     * @param request the request being customized
     */
    protected void customizeSecure(Request request)
    {
        request.setSecure(true);

        if (_stsField != null)
            request.getResponse().getHttpFields().add(_stsField);
    }

    /**
     * <p>
     * Customizes the request attributes to be set for SSL requests.
     * </p>
     * <p>
     * The requirements of the Servlet specs are:
     * </p>
     * <ul>
     * <li>an attribute named "javax.servlet.request.ssl_session_id" of type String (since Servlet Spec 3.0).</li>
     * <li>an attribute named "javax.servlet.request.cipher_suite" of type String.</li>
     * <li>an attribute named "javax.servlet.request.key_size" of type Integer.</li>
     * <li>an attribute named "javax.servlet.request.X509Certificate" of type java.security.cert.X509Certificate[]. This
     * is an array of objects of type X509Certificate, the order of this array is defined as being in ascending order of
     * trust. The first certificate in the chain is the one set by the client, the next is the one used to authenticate
     * the first, and so on.</li>
     * </ul>
     *
     * @param sslEngine the sslEngine to be customized.
     * @param request HttpRequest to be customized.
     */
    protected void customize(SSLEngine sslEngine, Request request)
    {
        SSLSession sslSession = sslEngine.getSession();

        if (_sniHostCheck || _sniRequired)
        {
            X509 x509 = (X509)sslSession.getValue(SniX509ExtendedKeyManager.SNI_X509);
            if (LOG.isDebugEnabled())
                LOG.debug("Host {} with SNI {}", request.getServerName(), x509);

            if (x509 == null)
            {
                if (_sniRequired)
                    throw new BadMessageException(400, "SNI required");
            }
            else if (_sniHostCheck && !x509.matches(request.getServerName()))
            {
                throw new BadMessageException(400, "Host does not match SNI");
            }
        }

        request.setAttributes(new SslAttributes(request, sslSession, request.getAttributes()));
    }

    private X509Certificate[] getCertChain(Connector connector, SSLSession sslSession)
    {
        // The in-use SslContextFactory should be present in the Connector's SslConnectionFactory
        SslConnectionFactory sslConnectionFactory = connector.getConnectionFactory(SslConnectionFactory.class);
        if (sslConnectionFactory != null)
        {
            SslContextFactory sslContextFactory = sslConnectionFactory.getSslContextFactory();
            if (sslContextFactory != null)
                return sslContextFactory.getX509CertChain(sslSession);
        }

        // Fallback, either no SslConnectionFactory or no SslContextFactory instance found
        return SslContextFactory.getCertChain(sslSession);
    }

    public void setSslSessionAttribute(String attribute)
    {
        this.sslSessionAttribute = attribute;
    }

    public String getSslSessionAttribute()
    {
        return sslSessionAttribute;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", this.getClass().getSimpleName(), hashCode());
    }

    private class SslAttributes extends Attributes.Wrapper
    {
        private final Request _request;
        private final SSLSession _session;

        private X509Certificate[] _certs;
        private String _cipherSuite;
        private Integer _keySize;
        private String _sessionId;
        private String _sessionAttribute;

        public SslAttributes(Request request, SSLSession sslSession, Attributes attributes)
        {
            super(attributes);
            this._request = request;
            this._session = sslSession;

            try
            {
                _certs = getSslSessionData().getCerts();
                _cipherSuite = _session.getCipherSuite();
                _keySize = getSslSessionData().getKeySize();
                _sessionId = getSslSessionData().getIdStr();
                _sessionAttribute = getSslSessionAttribute();
            }
            catch (Exception e)
            {
                LOG.warn("Unable to get secure details ", e);
            }
        }

        @Override
        public Object getAttribute(String name)
        {
            switch (name)
            {
                case JAVAX_SERVLET_REQUEST_X_509_CERTIFICATE:
                    return _certs;
                case JAVAX_SERVLET_REQUEST_CIPHER_SUITE:
                    return _cipherSuite;
                case JAVAX_SERVLET_REQUEST_KEY_SIZE:
                    return _keySize;
                case JAVAX_SERVLET_REQUEST_SSL_SESSION_ID:
                    return _sessionId;
                default:
                    if (!StringUtil.isEmpty(_sessionAttribute) && _sessionAttribute.equals(name))
                        return _session;
            }

            return _attributes.getAttribute(name);
        }

        /**
         * Get data belonging to the {@link SSLSession}.
         *
         * @return the SslSessionData
         */
        private SslSessionData getSslSessionData()
        {
            String key = SslSessionData.class.getName();
            SslSessionData sslSessionData = (SslSessionData)_session.getValue(key);
            if (sslSessionData == null)
            {
                String cipherSuite = _session.getCipherSuite();
                int keySize = SslContextFactory.deduceKeyLength(cipherSuite);

                X509Certificate[] certs = getCertChain(_request.getHttpChannel().getConnector(), _session);

                byte[] bytes = _session.getId();
                String idStr = TypeUtil.toHexString(bytes);

                sslSessionData = new SslSessionData(keySize, certs, idStr);
                _session.putValue(key, sslSessionData);
            }
            return sslSessionData;
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            Set<String> names = new HashSet<>(_attributes.getAttributeNameSet());
            names.remove(JAVAX_SERVLET_REQUEST_X_509_CERTIFICATE);
            names.remove(JAVAX_SERVLET_REQUEST_CIPHER_SUITE);
            names.remove(JAVAX_SERVLET_REQUEST_KEY_SIZE);
            names.remove(JAVAX_SERVLET_REQUEST_SSL_SESSION_ID);

            if (_certs != null)
                names.add(JAVAX_SERVLET_REQUEST_X_509_CERTIFICATE);
            if (_cipherSuite != null)
                names.add(JAVAX_SERVLET_REQUEST_CIPHER_SUITE);
            if (_keySize != null)
                names.add(JAVAX_SERVLET_REQUEST_KEY_SIZE);
            if (_sessionId != null)
                names.add(JAVAX_SERVLET_REQUEST_SSL_SESSION_ID);
            if (!StringUtil.isEmpty(_sessionAttribute))
                names.add(_sessionAttribute);

            return names;
        }
    }

    /**
     * Simple bundle of data that is cached in the SSLSession.
     */
    private static class SslSessionData
    {
        private final Integer _keySize;
        private final X509Certificate[] _certs;
        private final String _idStr;

        private SslSessionData(Integer keySize, X509Certificate[] certs, String idStr)
        {
            this._keySize = keySize;
            this._certs = certs;
            this._idStr = idStr;
        }

        private Integer getKeySize()
        {
            return _keySize;
        }

        private X509Certificate[] getCerts()
        {
            return _certs;
        }

        private String getIdStr()
        {
            return _idStr;
        }
    }
}
