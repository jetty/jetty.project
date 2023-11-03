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

package org.eclipse.jetty.server;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslConnection.SslEndPoint;
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
    /**
     * <p>The request attribute name to use to obtain the local certificate
     * as an {@link X509} object.</p>
     */
    public static final String X509_ATTRIBUTE = "org.eclipse.jetty.server.x509";
    public static final String SSL_SESSION_ATTRIBUTE = "org.eclipse.jetty.server.SslSession";
    public static final String SSL_SESSION_DATA_ATTRIBUTE = "org.eclipse.jetty.server.SslSessionData";

    private static final Logger LOG = LoggerFactory.getLogger(SecureRequestCustomizer.class);

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
     * Sets the Strict-Transport-Security max age in seconds.
     *
     * @param stsMaxAgeSeconds the max age in seconds for the Strict-Transport-Security response header.
     * If less than zero then no Strict-Transport-Security response header is set.
     */
    public void setStsMaxAge(long stsMaxAgeSeconds)
    {
        setStsMaxAge(stsMaxAgeSeconds, TimeUnit.SECONDS);
    }

    /**
     * Sets the Strict-Transport-Security max age in the given time unit.
     *
     * @param period The max age value
     * @param units The {@link TimeUnit} of the max age
     */
    public void setStsMaxAge(long period, TimeUnit units)
    {
        _stsMaxAge = units.toSeconds(period);
        formatSTS();
    }

    /**
     * @return whether the {@code includeSubdomains} attribute is sent with the Strict-Transport-Security response header
     */
    public boolean isStsIncludeSubDomains()
    {
        return _stsIncludeSubDomains;
    }

    /**
     * Set whether the {@code includeSubdomains} attribute is sent with the Strict-Transport-Security response header.
     * @param stsIncludeSubDomains whether the {@code includeSubdomains} attribute is sent with the Strict-Transport-Security response header
     */
    public void setStsIncludeSubDomains(boolean stsIncludeSubDomains)
    {
        _stsIncludeSubDomains = stsIncludeSubDomains;
        formatSTS();
    }

    private void formatSTS()
    {
        long stsMaxAge = getStsMaxAge();
        if (stsMaxAge < 0)
            _stsField = null;
        else
            _stsField = new PreEncodedHttpField(HttpHeader.STRICT_TRANSPORT_SECURITY, String.format("max-age=%d%s", stsMaxAge, isStsIncludeSubDomains() ? "; includeSubDomains" : ""));
    }

    @Override
    public Request customize(Request request, HttpFields.Mutable responseHeaders)
    {
        EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();
        if (endPoint instanceof SslEndPoint sslEndPoint)
        {
            SslConnection sslConnection = sslEndPoint.getSslConnection();
            SSLEngine sslEngine = sslConnection.getSSLEngine();
            request = newSecureRequest(request, sslEngine);
        }
        else if (endPoint instanceof ProxyConnectionFactory.ProxyEndPoint proxyEndPoint)
        {
            SslSessionData sslSessionData = proxyEndPoint.getSslSessionData();
            if (sslSessionData != null)
                request = newSecureRequest(request, sslSessionData);
        }

        if (_stsField != null)
            responseHeaders.add(_stsField);

        return request;
    }

    protected Request newSecureRequest(Request request, SSLEngine sslEngine)
    {
        SSLSession sslSession = sslEngine.getSession();
        checkSni(request, sslSession);

        String key = SslSessionData.class.getName();
        SslSessionData sslSessionData = (SslSessionData)sslSession.getValue(key);
        if (sslSessionData == null)
        {
            try
            {
                String cipherSuite = sslSession.getCipherSuite();
                int keySize = SslContextFactory.deduceKeyLength(cipherSuite);

                X509Certificate[] peerCertificates = getCertChain(request.getConnectionMetaData().getConnector(), sslSession);

                byte[] bytes = sslSession.getId();
                String idStr = StringUtil.toHexString(bytes);

                sslSessionData = new SslSessionData(idStr, cipherSuite, keySize > 0 ? keySize : null, peerCertificates);
                sslSession.putValue(key, sslSessionData);
            }
            catch (Exception e)
            {
                LOG.warn("Unable to get secure details ", e);
            }
        }
        return new SecureRequestWithSslSessionData(request, sslSession, sslSessionData);
    }

    protected Request newSecureRequest(Request request, SslSessionData sslSessionData)
    {
        return new SecureRequestWithSslSessionData(request, null, sslSessionData);
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

    @Deprecated
    public void setSslSessionAttribute(String attribute)
    {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    public String getSslSessionAttribute()
    {
        return SSL_SESSION_ATTRIBUTE;
    }

    @Deprecated
    public String getSslSessionDataAttribute()
    {
        return SslSessionData.ATTRIBUTE;
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
        X509 x509 = (X509)session.getValue(X509_ATTRIBUTE);
        if (x509 == null)
        {
            Certificate[] certificates = session.getLocalCertificates();
            if (certificates == null || certificates.length == 0 || !(certificates[0] instanceof X509Certificate))
                return null;
            x509 = new X509(null, (X509Certificate)certificates[0]);
            session.putValue(X509_ATTRIBUTE, x509);
        }
        return x509;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", this.getClass().getSimpleName(), hashCode());
    }

    protected static class SecureRequest extends Request.Wrapper
    {
        public SecureRequest(Request wrapped)
        {
            super(wrapped);
        }

        @Override
        public boolean isSecure()
        {
            return true;
        }
    }

    protected class SecureRequestWithSslSessionData extends SecureRequest
    {
        private final SSLSession _sslSession;
        private final SslSessionData _sslSessionData;

        public SecureRequestWithSslSessionData(Request request, SSLSession sslSession, SslSessionData sslSessionData)
        {
            super(request);
            _sslSession = sslSession;
            _sslSessionData = sslSessionData;
        }

        @Override
        public Object getAttribute(String name)
        {
            return switch (name)
            {
                case SSL_SESSION_ATTRIBUTE -> _sslSession;
                case SSL_SESSION_DATA_ATTRIBUTE -> _sslSessionData;
                case X509_ATTRIBUTE -> getX509(_sslSession);
                default -> super.getAttribute(name);
            };
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            Set<String> names = new HashSet<>(super.getAttributeNameSet());

            if (_sslSession != null)
            {
                names.add(SSL_SESSION_ATTRIBUTE);
                if (getX509(_sslSession) != null)
                    names.add(X509_ATTRIBUTE);
            }
            if (_sslSessionData != null)
                names.add(SSL_SESSION_DATA_ATTRIBUTE);

            return names;
        }
    }
}
