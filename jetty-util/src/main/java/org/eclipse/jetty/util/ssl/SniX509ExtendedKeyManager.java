//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
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
    private UnaryOperator<String> _aliasMapper = UnaryOperator.identity();

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

    /**
     * @return the function that transforms the alias
     * @see #setAliasMapper(UnaryOperator)
     */
    public UnaryOperator<String> getAliasMapper()
    {
        return _aliasMapper;
    }

    /**
     * <p>Sets a function that transforms the alias into a possibly different alias,
     * invoked when the SNI logic must choose the alias to pick the right certificate.</p>
     * <p>This function is required when using the
     * {@link SslContextFactory.Server#setKeyManagerFactoryAlgorithm(String) PKIX KeyManagerFactory algorithm}
     * which suffers from bug https://bugs.openjdk.java.net/browse/JDK-8246262,
     * where aliases are returned by the OpenJDK implementation to the application
     * in the form {@code N.0.alias} where {@code N} is an always increasing number.
     * Such mangled aliases won't match the aliases in the keystore, so that for
     * example SNI matching will always fail.</p>
     * <p>Other implementations such as BouncyCastle have been reported to mangle
     * the alias in a different way, namely {@code 0.alias.N}.</p>
     * <p>This function allows to "unmangle" the alias from the implementation
     * specific mangling back to just {@code alias} so that SNI matching will work
     * again.</p>
     *
     * @param aliasMapper the function that transforms the alias
     */
    public void setAliasMapper(UnaryOperator<String> aliasMapper)
    {
        _aliasMapper = Objects.requireNonNull(aliasMapper);
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
        String[] mangledAliases = _delegate.getServerAliases(keyType, issuers);
        if (mangledAliases == null || mangledAliases.length == 0)
            return null;

        // Apply the alias mapping, keeping the alias order.
        Map<String, String> aliasMap = new LinkedHashMap<>();
        Arrays.stream(mangledAliases)
            .forEach(alias -> aliasMap.put(getAliasMapper().apply(alias), alias));

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
            Collection<X509> certificates = aliasMap.keySet().stream()
                .map(_sslContextFactory::getX509)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            // Delegate the decision to accept to the sniSelector.
            SniSelector sniSelector = _sslContextFactory.getSNISelector();
            if (sniSelector == null)
                sniSelector = _sslContextFactory;
            String alias = sniSelector.sniSelect(keyType, issuers, session, host, certificates);

            // Check the selected alias.
            if (alias == null || alias == SniSelector.DELEGATE)
                return alias;

            // Make sure we got back an alias from the acceptable aliases.
            X509 x509 = _sslContextFactory.getX509(alias);
            if (!aliasMap.containsKey(alias) || x509 == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Invalid X509 match for SNI {}: {}", host, alias);
                return null;
            }

            if (session != null)
                session.putValue(SNI_X509, x509);

            // Convert the selected alias back to the original
            // value before the alias mapping performed above.
            String mangledAlias = aliasMap.get(alias);

            if (LOG.isDebugEnabled())
                LOG.debug("Matched SNI {} with alias {}, certificate {} from aliases {}", host, mangledAlias, x509, aliasMap.keySet());
            return mangledAlias;
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
        boolean delegate = alias == SniSelector.DELEGATE;
        if (delegate)
            alias = _delegate.chooseServerAlias(keyType, issuers, socket);
        if (LOG.isDebugEnabled())
            LOG.debug("Chose {} alias={} keyType={} on {}", delegate ? "delegate" : "explicit", String.valueOf(alias), keyType, socket);
        return alias;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine)
    {
        String alias = (engine == null)
            ? chooseServerAlias(keyType, issuers, Collections.emptyList(), null)
            : chooseServerAlias(keyType, issuers, engine.getSSLParameters().getSNIMatchers(), engine.getHandshakeSession());
        boolean delegate = alias == SniSelector.DELEGATE;
        if (delegate)
            alias = _delegate.chooseEngineServerAlias(keyType, issuers, engine);
        if (LOG.isDebugEnabled())
            LOG.debug("Chose {} alias={} keyType={} on {}", delegate ? "delegate" : "explicit", String.valueOf(alias), keyType, engine);
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
        public String sniSelect(String keyType, Principal[] issuers, SSLSession session, String sniHost, Collection<X509> certificates) throws SSLHandshakeException;
    }
}
