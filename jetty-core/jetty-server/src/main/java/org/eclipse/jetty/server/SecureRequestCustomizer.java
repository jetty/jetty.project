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

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslConnection.DecryptedEndPoint;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.X509;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Customizer that extracts the attribute from an {@link SSLContext}
 * and sets them on the request with {@link Request#setAttribute(String, Object)}
 * according to Servlet Specification Requirements.</p>
 */
public class SecureRequestCustomizer implements HttpConfiguration.Customizer
{
    private static final Logger LOG = LoggerFactory.getLogger(SecureRequestCustomizer.class);
    public static final String X509_CERT = "org.eclipse.jetty.server.x509_cert";
    public static final String CERTIFICATES = "org.eclipse.jetty.server.certificates";
    private String _sslSessionAttribute = "org.eclipse.jetty.servlet.request.ssl_session"; // TODO better name?
    private String _sslSessionDataAttribute = _sslSessionAttribute + "_data"; // TODO better name?

    private boolean _sniRequired;
    private boolean _sniHostCheck;
    private long _stsMaxAge;
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
    public Request customize(Request request, HttpFields.Mutable responseHeaders)
    {
        EndPoint endp = request.getConnectionMetaData().getConnection().getEndPoint();
        HttpURI uri = request.getHttpURI();
        SSLEngine sslEngine;
        if (endp instanceof DecryptedEndPoint)
        {
            DecryptedEndPoint sslEndp = (DecryptedEndPoint)endp;
            SslConnection sslConnection = sslEndp.getSslConnection();
            uri = (HttpScheme.HTTPS.is(uri.getScheme()))
                ? uri : HttpURI.build(uri).scheme(HttpScheme.HTTPS);
            sslEngine = sslConnection.getSSLEngine();
        }
        else if (endp instanceof ProxyConnectionFactory.ProxyEndPoint)
        {
            ProxyConnectionFactory.ProxyEndPoint proxy = (ProxyConnectionFactory.ProxyEndPoint)endp;
            if (proxy.getAttribute(ProxyConnectionFactory.TLS_VERSION) == null)
                return request;
            uri = (HttpScheme.HTTPS.is(uri.getScheme()))
                ? uri : HttpURI.build(uri).scheme(HttpScheme.HTTPS);
            sslEngine = null;
        }
        else
        {
            return request;
        }

        if (_stsField != null)
            responseHeaders.add(_stsField);

        return newSecureRequest(request, uri, sslEngine);
    }

    protected SecureRequest newSecureRequest(Request request, HttpURI uri, SSLEngine sslEngine)
    {
        return new SecureRequest(request, uri, sslEngine);
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
        Objects.requireNonNull(attribute);
        _sslSessionAttribute = attribute;
        _sslSessionDataAttribute = attribute + "_data";
    }

    public String getSslSessionAttribute()
    {
        return _sslSessionAttribute;
    }

    /**
     * Get data belonging to the {@link SSLSession}.
     *
     * @return the SslSessionData
     */
    public static SslSessionData getSslSessionData(SSLSession session)
    {
        String key = SslSessionData.class.getName();
        return (SslSessionData)session.getValue(key);
    }

    protected void checkSni(Request request, SSLSession session)
    {
        if (isSniRequired() || isSniHostCheck())
        {
            String sniHost = (String)session.getValue(SslContextFactory.Server.SNI_HOST);

            X509 x509 = getX509(session);
            if (x509 == null)
                throw new BadMessageException(400, "Invalid SNI");
            String serverName = Request.getServerName(request);
            if (LOG.isDebugEnabled())
                LOG.debug("Host={}, SNI={}, SNI Certificate={}", serverName, sniHost, x509);

            if (isSniRequired() && (sniHost == null || !x509.matches(sniHost)))
                throw new BadMessageException(400, "Invalid SNI");

            if (isSniHostCheck() && !x509.matches(serverName))
                throw new BadMessageException(400, "Invalid SNI");
        }
    }

    private X509 getX509(SSLSession session)
    {
        X509 x509 = (X509)session.getValue(X509_CERT);
        if (x509 == null)
        {
            Certificate[] certificates = session.getLocalCertificates();
            if (certificates == null || certificates.length == 0 || !(certificates[0] instanceof X509Certificate))
                return null;
            x509 = new X509(null, (X509Certificate)certificates[0]);
            session.putValue(X509_CERT, x509);
        }
        return x509;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", this.getClass().getSimpleName(), hashCode());
    }

    protected class SecureRequest extends Request.Wrapper
    {
        private final HttpURI _uri;
        private final SSLSession _sslSession;
        private final SslSessionData _sslSessionData;

        private SecureRequest(Request request, HttpURI uri, SSLEngine sslEngine)
        {
            super(request);
            _uri = uri.asImmutable();

            if (sslEngine == null)
            {
                _sslSession = null;
                _sslSessionData = null;
            }
            else
            {
                _sslSession = sslEngine.getSession();
                checkSni(request, _sslSession);

                String key = SslSessionData.class.getName();
                SslSessionData sslSessionData = (SslSessionData)_sslSession.getValue(key);
                if (sslSessionData == null)
                {
                    try
                    {
                        String cipherSuite = _sslSession.getCipherSuite();
                        int keySize = SslContextFactory.deduceKeyLength(cipherSuite);

                        X509Certificate[] certs = getCertChain(getConnectionMetaData().getConnector(), _sslSession);

                        byte[] bytes = _sslSession.getId();
                        String idStr = StringUtil.toHexString(bytes);

                        sslSessionData = new SslSessionData(keySize, certs, idStr);
                        _sslSession.putValue(key, sslSessionData);
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Unable to get secure details ", e);
                    }
                }
                _sslSessionData = sslSessionData;
            }
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _uri;
        }

        @Override
        public boolean isSecure()
        {
            return true;
        }

        @Override
        public Object getAttribute(String name)
        {
            String sessionAttribute = getSslSessionAttribute();
            if (StringUtil.isNotBlank(sessionAttribute) && name.startsWith(sessionAttribute))
            {
                if (name.equals(sessionAttribute))
                    return _sslSession;
                if (name.equals(_sslSessionDataAttribute))
                    return _sslSessionData;
            }

            return switch (name)
            {
                case X509_CERT -> getX509(_sslSession);
                case CERTIFICATES -> _sslSessionData._certs;
                default -> super.getAttribute(name);
            };
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            String sessionAttribute = getSslSessionAttribute();
            Set<String> names = new HashSet<>(super.getAttributeNameSet());
            if (!StringUtil.isEmpty(sessionAttribute))
            {
                names.add(sessionAttribute);
                names.add(sessionAttribute + "_data");
            }
            return names;
        }
    }

    /**
     * Simple bundle of data that is cached in the SSLSession.
     */
    public static class SslSessionData
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

        public Integer getKeySize()
        {
            return _keySize;
        }

        public X509Certificate[] getX509Certificates()
        {
            return _certs;
        }

        public String getId()
        {
            return _idStr;
        }
    }
}
