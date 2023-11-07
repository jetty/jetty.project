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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.X509;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Customizer that extracts the attribute of an {@link SSLContext}
 * and makes them available via {@link Request#getAttribute(String)}
 * using the names:
 * <ul>
 *     <li>{@link #SSL_SESSION_ATTRIBUTE} for the {@link SSLSession} itself</li>
 *     <li>{@link EndPoint.SslSessionData#ATTRIBUTE} for {@link SSLSession} metadata</li>
 *     <li>{@link #X509_ATTRIBUTE} for the local certificate as a {@link X509} instance</li>
 * </ul>
 * @see EndPoint.SslSessionData
 */
public class SecureRequestCustomizer implements HttpConfiguration.Customizer
{
    public static final String X509_ATTRIBUTE = "org.eclipse.jetty.server.x509";
    public static final String SSL_SESSION_ATTRIBUTE = "org.eclipse.jetty.server.SslSession";
    public static final String SSL_SESSION_DATA_ATTRIBUTE = EndPoint.SslSessionData.ATTRIBUTE;

    /**
     * @deprecated use {@link EndPoint.SslSessionData#ATTRIBUTE} attribute with {@link EndPoint.SslSessionData#cipherSuite()}
     */
    @Deprecated(forRemoval = true)
    public static final String CIPHER_SUITE_ATTRIBUTE = "org.eclipse.jetty.server.cipher";
    /**
     * @deprecated use {@link EndPoint.SslSessionData#ATTRIBUTE} attribute with {@link EndPoint.SslSessionData#keySize()}
     */
    @Deprecated(forRemoval = true)
    public static final String KEY_SIZE_ATTRIBUTE = "org.eclipse.jetty.server.keySize";
    /**
     * @deprecated use {@link EndPoint.SslSessionData#ATTRIBUTE} attribute with {@link EndPoint.SslSessionData#sessionId()}
     */
    @Deprecated(forRemoval = true)
    public static final String SSL_SESSION_ID_ATTRIBUTE = "org.eclipse.jetty.server.sslSessionId";
    /**
     * @deprecated use {@link EndPoint.SslSessionData#ATTRIBUTE} attribute with {@link EndPoint.SslSessionData#peerCertificates()}
     */
    @Deprecated(forRemoval = true)
    public static final String PEER_CERTIFICATES_ATTRIBUTE = "org.eclipse.jetty.server.peerCertificates";

    private static final Logger LOG = LoggerFactory.getLogger(SecureRequestCustomizer.class);

    private boolean _sniRequired;
    private boolean _sniHostCheck;
    private long _stsMaxAge;
    private boolean _stsIncludeSubDomains;
    private HttpField _stsField;
    private final boolean _legacyAttributes;

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
        this(sniRequired, sniHostCheck, stsMaxAgeSeconds, stsIncludeSubdomains, true);
    }

    /**
     * @param sniRequired True if a SNI certificate is required.
     * @param sniHostCheck True if the SNI Host name must match.
     * @param stsMaxAgeSeconds The max age in seconds for a Strict-Transport-Security response header. If set less than zero then no header is sent.
     * @param stsIncludeSubdomains If true, a include subdomain property is sent with any Strict-Transport-Security header
     * @param legacyAttributes If true, the deprecated legacy attributes are supported.
     */
    @Deprecated(forRemoval = true)
    public SecureRequestCustomizer(
        @Name("sniRequired") boolean sniRequired,
        @Name("sniHostCheck") boolean sniHostCheck,
        @Name("stsMaxAgeSeconds") long stsMaxAgeSeconds,
        @Name("stsIncludeSubdomains") boolean stsIncludeSubdomains,
        boolean legacyAttributes)
    {
        _sniRequired = sniRequired;
        _sniHostCheck = sniHostCheck;
        _stsMaxAge = stsMaxAgeSeconds;
        _stsIncludeSubDomains = stsIncludeSubdomains;
        _legacyAttributes = legacyAttributes;
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
        EndPoint.SslSessionData sslSessionData = endPoint != null ? endPoint.getSslSessionData() : null;
        if (sslSessionData != null)
            request = newSecureRequest(request, sslSessionData);

        if (_stsField != null)
            responseHeaders.add(_stsField);

        return request;
    }

    protected Request newSecureRequest(Request request, EndPoint.SslSessionData sslSessionData)
    {
        if (sslSessionData.sslSession() != null)
            checkSni(request, sslSessionData.sslSession());
        return new SecureRequestWithSslSessionData(request, sslSessionData, _legacyAttributes);
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
        return EndPoint.SslSessionData.ATTRIBUTE;
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
        private static final Set<String> ATTRIBUTES = Set.of(
            SSL_SESSION_ATTRIBUTE,
            EndPoint.SslSessionData.ATTRIBUTE,
            X509_ATTRIBUTE,
            CIPHER_SUITE_ATTRIBUTE,
            KEY_SIZE_ATTRIBUTE,
            SSL_SESSION_ID_ATTRIBUTE,
            PEER_CERTIFICATES_ATTRIBUTE
        );

        private final Attributes.Synthetic _secureAttributes;
        private final EndPoint.SslSessionData _sslSessionData;

        protected SecureRequestWithSslSessionData(Request request, EndPoint.SslSessionData sslSessionData, boolean legacyAttributes)
        {
            super(request);
            _sslSessionData = Objects.requireNonNull(sslSessionData);
            _secureAttributes = new Synthetic(request)
            {
                @Override
                protected Object getSyntheticAttribute(String name)
                {
                    return switch (name)
                    {
                        case SSL_SESSION_ATTRIBUTE -> _sslSessionData.sslSession();
                        case EndPoint.SslSessionData.ATTRIBUTE -> _sslSessionData;
                        case X509_ATTRIBUTE -> getX509(_sslSessionData.sslSession());
                        case CIPHER_SUITE_ATTRIBUTE -> legacyAttributes ? sslSessionData.cipherSuite() : null;
                        case KEY_SIZE_ATTRIBUTE -> legacyAttributes ? sslSessionData.keySize() : null;
                        case SSL_SESSION_ID_ATTRIBUTE -> legacyAttributes ? sslSessionData.sessionId() : null;
                        case PEER_CERTIFICATES_ATTRIBUTE -> legacyAttributes ? sslSessionData.peerCertificates() : null;
                        default -> null;
                    };
                }

                @Override
                protected Set<String> getSyntheticNameSet()
                {
                    return ATTRIBUTES;
                }
            };
        }

        @Override
        public Object removeAttribute(String name)
        {
            return _secureAttributes.removeAttribute(name);
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            return _secureAttributes.setAttribute(name, attribute);
        }

        @Override
        public Object getAttribute(String name)
        {
            return _secureAttributes.getAttribute(name);
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            return _secureAttributes.getAttributeNameSet();
        }

        @Override
        public void clearAttributes()
        {
            _secureAttributes.clearAttributes();
        }
    }
}
