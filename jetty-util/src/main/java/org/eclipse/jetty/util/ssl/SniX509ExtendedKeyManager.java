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

package org.eclipse.jetty.util.ssl;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A {@link X509ExtendedKeyManager} that selects a key with an alias
 * retrieved from SNI information, delegating other processing to a nested X509ExtendedKeyManager.</p>
 * <p>Can only be used on server side.</p>
 */
public class SniX509ExtendedKeyManager extends X509ExtendedKeyManager
{
    public static final String SNI_X509 = "org.eclipse.jetty.util.ssl.snix509";
    private static final Logger LOG = Log.getLogger(SniX509ExtendedKeyManager.class);

    private final X509ExtendedKeyManager _delegate;
    private final SslContextFactory.Server _sslContextFactory;

    /**
     * @deprecated not supported, you must have a {@link SslContextFactory.Server} for this to work.
     */
    @Deprecated
    public SniX509ExtendedKeyManager(X509ExtendedKeyManager keyManager)
    {
        this(keyManager, null);
    }

    public SniX509ExtendedKeyManager(X509ExtendedKeyManager keyManager, SslContextFactory.Server sslContextFactory)
    {
        _delegate = keyManager;
        _sslContextFactory = Objects.requireNonNull(sslContextFactory, "SslContextFactory.Server must be provided");
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
        String[] aliases = _delegate.getServerAliases(keyType, issuers);
        if (aliases == null || aliases.length == 0)
            return null;

        // Find our SNIMatcher.  There should only be one and it always matches (always returns true
        // from AliasSNIMatcher.matches), but it will capture the SNI Host if one was presented.
        String host = matchers == null ? null :  matchers.stream()
            .filter(SslContextFactory.AliasSNIMatcher.class::isInstance)
            .map(SslContextFactory.AliasSNIMatcher.class::cast)
            .findFirst()
            .map(SslContextFactory.AliasSNIMatcher::getHost)
            .orElse(null);

        try
        {
            // Filter the certificates by alias.
            Collection<X509> certificates = Arrays.stream(aliases)
                .map(_sslContextFactory::getX509)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            // delegate the decision to accept to the sniSelector
            SniSelector sniSelector = _sslContextFactory.getSNISelector();
            if (sniSelector == null)
                sniSelector = _sslContextFactory;
            String alias = sniSelector.sniSelect(keyType, issuers, session, host, certificates);

            // Check selected alias
            if (alias != null && alias != SniSelector.DELEGATE)
            {
                // Make sure we got back an alias from the acceptable aliases.
                X509 x509 = _sslContextFactory.getX509(alias);
                if (!Arrays.asList(aliases).contains(alias) || x509 == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Invalid X509 match for SNI {}: {}", host, alias);
                    return null;
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("Matched SNI {} with X509 {} from {}", host, x509, Arrays.asList(aliases));
                if (session != null)
                    session.putValue(SNI_X509, x509);
            }
            return alias;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failure matching X509 for SNI " + host, x);
            return null;
        }
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket)
    {
        SSLSocket sslSocket = (SSLSocket)socket;
        String alias = (socket == null)
            ? chooseServerAlias(keyType, issuers, Collections.emptyList(), null)
            : chooseServerAlias(keyType, issuers, sslSocket.getSSLParameters().getSNIMatchers(), sslSocket.getHandshakeSession());
        if (alias == SniSelector.DELEGATE)
            alias = _delegate.chooseServerAlias(keyType, issuers, socket);
        if (LOG.isDebugEnabled())
            LOG.debug("Chose alias {}/{} on {}", alias, keyType, socket);
        return alias;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine)
    {
        String alias = (engine == null)
            ? chooseServerAlias(keyType, issuers, Collections.emptyList(), null)
            : chooseServerAlias(keyType, issuers, engine.getSSLParameters().getSNIMatchers(), engine.getHandshakeSession());
        if (alias == SniSelector.DELEGATE)
            alias = _delegate.chooseEngineServerAlias(keyType, issuers, engine);
        if (LOG.isDebugEnabled())
            LOG.debug("Chose alias {}/{} on {}", alias, keyType, engine);
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
         * (because they need to match the {@code keyType}.</p>
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
        public String sniSelect(String keyType, Principal[] issuers, SSLSession session, String sniHost, Collection<X509> certificates) throws SSLHandshakeException;
    }
}
