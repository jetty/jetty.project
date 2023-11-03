package org.eclipse.jetty.server;

import java.security.cert.X509Certificate;

/**
 * Simple bundle of data that is cached in the SSLSession.
 */
public record SslSessionData(String sessionId, String cipherSuite, Integer keySize, X509Certificate[] peerCertificates)
{
    public static final String ATTRIBUTE = SecureRequestCustomizer.SSL_SESSION_DATA_ATTRIBUTE;

    public static SslSessionData from(SslSessionData dftSslSessionData, String sessionId, String cipherSuite, Integer keySize, X509Certificate[] peerCertificates)
    {
        return (dftSslSessionData == null)
            ? new SslSessionData(sessionId, cipherSuite, keySize, peerCertificates)
            : new SslSessionData(
                sessionId != null ? sessionId : dftSslSessionData.sessionId,
                cipherSuite != null ? cipherSuite : dftSslSessionData.cipherSuite,
                keySize != null && keySize > 0 ? keySize : dftSslSessionData.keySize,
                peerCertificates != null ? peerCertificates : dftSslSessionData.peerCertificates());
    }
}
