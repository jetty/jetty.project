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

package org.eclipse.jetty.util.ssl;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link X509ExtendedKeyManager} that selects a key with an alias
 * retrieved from SNI information, delegating other processing to a nested
 * X509ExtendedKeyManager.</p>
 * <p>Can only be used on server side.</p>
 */
public class SniX509ExtendedKeyManager extends X509ExtendedKeyManager
{
    private static final Logger LOG = LoggerFactory.getLogger(SniX509ExtendedKeyManager.class);

    private final X509ExtendedKeyManager _delegate;
    private final SslContextFactory.Server _sslContextFactory;

    public SniX509ExtendedKeyManager(X509ExtendedKeyManager keyManager, SslContextFactory.Server sslContextFactory)
    {
        _delegate = keyManager;
        _sslContextFactory = Objects.requireNonNull(sslContextFactory, "SslContextFactory.Server must be provided");
    }

    /**
     * @return the function that transforms the alias
     * @deprecated use {@link SslContextFactory#getAliasMapper()} instead
     */
    @Deprecated(since = "12.1.15", forRemoval = true)
    public UnaryOperator<String> getAliasMapper()
    {
        return _sslContextFactory.getAliasMapper();
    }

    /**
     * @param aliasMapper the function that transforms the alias
     * @deprecated use {@link SslContextFactory#setAliasMapper(UnaryOperator)} instead
     */
    @Deprecated(since = "12.1.15", forRemoval = true)
    public void setAliasMapper(UnaryOperator<String> aliasMapper)
    {
        _sslContextFactory.setAliasMapper(aliasMapper);
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket)
    {
        return _delegate.chooseClientAlias(keyType, issuers, socket);
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine)
    {
        return _delegate.chooseEngineClientAlias(keyType, issuers, engine);
    }

    protected String chooseServerAlias(String keyType, Principal[] issuers, Collection<SNIMatcher> matchers, SSLSession session)
    {
        // Look for the aliases that are suitable for the keyType and issuers.
        String[] keyManagerAliases = _delegate.getServerAliases(keyType, issuers);
        if (keyManagerAliases == null || keyManagerAliases.length == 0)
            return null;

        String host = null;
        if (session instanceof ExtendedSSLSession extended)
        {
            for (SNIServerName serverName : extended.getRequestedServerNames())
            {
                if (serverName instanceof SNIHostName hostName)
                {
                    host = hostName.getAsciiName();
                    break;
                }
            }
        }
        if (host == null)
        {
            // Find our SNIMatcher.  There should only be one and it always matches (always returns true
            // from AliasSNIMatcher.matches), but it will capture the SNI Host if one was presented.
            if (matchers != null)
            {
                for (SNIMatcher matcher : matchers)
                {
                    if (matcher instanceof SslContextFactory.AliasSNIMatcher aliasMatcher)
                    {
                        host = aliasMatcher.getHost();
                        break;
                    }
                }
            }
        }
        if (session != null && host != null)
            session.putValue(SslContextFactory.Server.SNI_HOST, host);

        try
        {
            Map<String, X509> aliasCerts = _sslContextFactory.aliasCerts(session);

            // Map the KeyManager aliases to the KeyStore aliases
            // and their certificates, keeping the alias order.
            Map<String, String> aliasMap = new LinkedHashMap<>();
            Map<String, X509> certificates = new LinkedHashMap<>();
            for (String keyManagerAlias : keyManagerAliases)
            {
                String keyStoreAlias = _sslContextFactory.toKeyStoreAlias(_delegate, keyManagerAlias, aliasCerts);
                X509 x509 = keyStoreAlias == null ? null : aliasCerts.get(keyStoreAlias);
                if (x509 != null && aliasMap.putIfAbsent(keyStoreAlias, keyManagerAlias) == null)
                    certificates.put(keyStoreAlias, x509);
            }

            // Delegate the decision to accept to the sniSelector.
            SniSelector sniSelector = _sslContextFactory.getSNISelector();
            if (sniSelector == null)
                sniSelector = _sslContextFactory;
            String keyStoreAlias = sniSelector.sniSelect(keyType, issuers, session, host, Collections.unmodifiableCollection(certificates.values()));

            // Check the selected alias.
            if (keyStoreAlias == null)
                return null;
            if (keyStoreAlias == SniSelector.DELEGATE)
            {
                // Without a configured certificate alias, let the delegate choose.
                String certAlias = _sslContextFactory.getCertAlias();
                if (certAlias == null)
                    return SniSelector.DELEGATE;
                // Otherwise, choose only the configured certificate alias,
                // which must be among the acceptable aliases checked below.
                keyStoreAlias = certAlias;
            }

            // Make sure we got back an alias from the acceptable aliases.
            X509 x509 = certificates.get(keyStoreAlias);
            if (x509 == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Invalid X509 match for SNI {}: {}", host, keyStoreAlias);
                return null;
            }

            // Convert the selected KeyStore alias back to the KeyManager alias.
            String keyManagerAlias = aliasMap.get(keyStoreAlias);

            if (LOG.isDebugEnabled())
                LOG.debug("Matched SNI {} with alias {}, certificate {} from aliases {}", host, keyManagerAlias, x509, aliasMap.keySet());
            return keyManagerAlias;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failure matching X509 for SNI {}", host, x);
            return null;
        }
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket)
    {
        SSLSocket sslSocket = (SSLSocket)socket;
        SSLSession session = sslSocket == null ? null : sslSocket.getHandshakeSession();
        String alias = (socket == null)
            ? chooseServerAlias(keyType, issuers, Collections.emptyList(), null)
            : chooseServerAlias(keyType, issuers, sslSocket.getSSLParameters().getSNIMatchers(), sslSocket.getHandshakeSession());
        boolean delegate = alias == SniSelector.DELEGATE;
        if (delegate)
            alias = _delegate.chooseServerAlias(keyType, issuers, socket);
        // An alias was chosen, the cached certificates are not needed anymore.
        if (alias != null && session != null)
            session.removeValue(SslContextFactory.ALIAS_X509);
        if (LOG.isDebugEnabled())
            LOG.debug("Chose {} alias={} keyType={} on {}", delegate ? "delegate" : "explicit", alias, keyType, socket);
        return alias;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine)
    {
        SSLSession session = engine == null ? null : engine.getHandshakeSession();
        String alias = (engine == null)
            ? chooseServerAlias(keyType, issuers, Collections.emptyList(), null)
            : chooseServerAlias(keyType, issuers, engine.getSSLParameters().getSNIMatchers(), engine.getHandshakeSession());
        boolean delegate = alias == SniSelector.DELEGATE;
        if (delegate)
            alias = _delegate.chooseEngineServerAlias(keyType, issuers, engine);
        // An alias was chosen, the cached certificates are not needed anymore.
        if (alias != null && session != null)
            session.removeValue(SslContextFactory.ALIAS_X509);
        if (LOG.isDebugEnabled())
            LOG.debug("Chose {} alias={} keyType={} on {}", delegate ? "delegate" : "explicit", alias, keyType, engine);
        return alias;
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias)
    {
        return _delegate.getCertificateChain(alias);
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers)
    {
        return _delegate.getClientAliases(keyType, issuers);
    }

    @Override
    public PrivateKey getPrivateKey(String alias)
    {
        return _delegate.getPrivateKey(alias);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers)
    {
        return _delegate.getServerAliases(keyType, issuers);
    }

    /**
     * <p>Selects a certificate based on SNI information.</p>
     */
    @FunctionalInterface
    public interface SniSelector
    {
        String DELEGATE = "delegate_no_sni_match";

        /**
         * <p>Selects a certificate based on SNI information.</p>
         * <p>This method may be invoked multiple times during the TLS handshake, with different parameters.
         * For example, the {@code keyType} could be different, and subsequently the collection of certificates
         * (because they need to match the {@code keyType}).</p>
         *
         * @param keyType the key algorithm type name
         * @param issuers the list of acceptable CA issuer subject names or null if it does not matter which issuers are used
         * @param session the TLS handshake session or null if not known.
         * @param sniHost the server name indication sent by the client, or null if the client did not send the server name indication
         * @param certificates the list of certificates matching {@code keyType} and {@code issuers} known to this SslContextFactory
         * @return the alias of the certificate to return to the client, from the {@code certificates} list,
         * or {@link SniSelector#DELEGATE} if the certificate choice should be delegated to the
         * nested key manager or null for no match.
         * @throws SSLHandshakeException if the TLS handshake should be aborted
         */
        String sniSelect(String keyType, Principal[] issuers, SSLSession session, String sniHost, Collection<X509> certificates) throws SSLHandshakeException;
    }
}
