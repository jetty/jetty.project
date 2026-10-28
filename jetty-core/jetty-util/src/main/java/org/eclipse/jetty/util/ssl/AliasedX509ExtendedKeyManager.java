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
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * <p>An {@link X509ExtendedKeyManager} that select a key with desired alias,
 * delegating other processing to a nested X509ExtendedKeyManager.</p>
 * <p>Can be used both with server and client sockets.</p>
 */
public class AliasedX509ExtendedKeyManager extends X509ExtendedKeyManager
{
    private final String _alias;
    private final X509ExtendedKeyManager _delegate;
    private final SslContextFactory _sslContextFactory;

    /**
     * <p>Creates an instance that selects the given {@link KeyManager} alias.</p>
     *
     * @param keyManager the nested {@link X509ExtendedKeyManager}
     * @param keyAlias the {@link KeyManager} alias to select
     * @deprecated use {@link #AliasedX509ExtendedKeyManager(X509ExtendedKeyManager, SslContextFactory)} instead
     */
    @Deprecated(since = "12.1.15", forRemoval = true)
    public AliasedX509ExtendedKeyManager(X509ExtendedKeyManager keyManager, String keyAlias)
    {
        _alias = keyAlias;
        _delegate = keyManager;
        _sslContextFactory = null;
    }

    /**
     * <p>Creates an instance that selects the {@link KeyStore} alias returned by
     * {@link SslContextFactory#getCertAlias()}, converting {@link KeyManager} aliases
     * to {@link KeyStore} aliases as specified by {@link SslContextFactory#setAliasMapper(java.util.function.UnaryOperator)}.</p>
     *
     * @param keyManager the nested {@link X509ExtendedKeyManager}
     * @param sslContextFactory the {@link SslContextFactory} that configures this instance
     */
    public AliasedX509ExtendedKeyManager(X509ExtendedKeyManager keyManager, SslContextFactory sslContextFactory)
    {
        _sslContextFactory = Objects.requireNonNull(sslContextFactory);
        _alias = sslContextFactory.getCertAlias();
        _delegate = keyManager;
    }

    public X509ExtendedKeyManager getDelegate()
    {
        return _delegate;
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket)
    {
        if (_alias == null)
            return _delegate.chooseClientAlias(keyType, issuers, socket);
        return chooseAlias(keyType, kt -> _delegate.getClientAliases(kt, issuers));
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket)
    {
        if (_alias == null)
            return _delegate.chooseServerAlias(keyType, issuers, socket);
        return chooseAlias(new String[]{keyType}, kt -> _delegate.getServerAliases(kt, issuers));
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers)
    {
        return _delegate.getClientAliases(keyType, issuers);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers)
    {
        return _delegate.getServerAliases(keyType, issuers);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias)
    {
        return _delegate.getCertificateChain(alias);
    }

    @Override
    public PrivateKey getPrivateKey(String alias)
    {
        return _delegate.getPrivateKey(alias);
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine)
    {
        if (_alias == null)
            return _delegate.chooseEngineServerAlias(keyType, issuers, engine);
        return chooseAlias(new String[]{keyType}, kt -> _delegate.getServerAliases(kt, issuers));
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine)
    {
        if (_alias == null)
            return _delegate.chooseEngineClientAlias(keyType, issuers, engine);
        return chooseAlias(keyType, kt -> _delegate.getClientAliases(kt, issuers));
    }

    /**
     * @param keyTypes the key types to try, in order
     * @param keyManagerAliases the function that returns the {@link KeyManager} aliases for a key type
     * @return the {@link KeyManager} alias corresponding to the configured alias, or {@code null}
     */
    private String chooseAlias(String[] keyTypes, Function<String, String[]> keyManagerAliases)
    {
        Map<String, X509> aliasCerts = _sslContextFactory == null ? Map.of() : _sslContextFactory.aliasCerts(null);
        for (String keyType : keyTypes)
        {
            String[] aliases = keyManagerAliases.apply(keyType);
            if (aliases == null)
                continue;
            for (String keyManagerAlias : aliases)
            {
                String keyStoreAlias = _sslContextFactory == null
                    ? keyManagerAlias
                    : _sslContextFactory.toKeyStoreAlias(_delegate, keyManagerAlias, aliasCerts);
                // Return the KeyManager alias, which may be different from the KeyStore alias.
                if (_alias.equals(keyStoreAlias))
                    return keyManagerAlias;
            }
        }
        return null;
    }
}
