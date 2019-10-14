//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SSLEngine;
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
    private static final String NO_MATCHERS = "no_matchers";
    private static final Logger LOG = Log.getLogger(SniX509ExtendedKeyManager.class);

    private final X509ExtendedKeyManager _delegate;
    private final SslContextFactory.Server _sslContextFactory;

    public SniX509ExtendedKeyManager(X509ExtendedKeyManager keyManager)
    {
        this(keyManager, null);
    }

    public SniX509ExtendedKeyManager(X509ExtendedKeyManager keyManager, SslContextFactory.Server sslContextFactory)
    {
        _delegate = keyManager;
        _sslContextFactory = sslContextFactory;
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

        // Look for the SNI information.
        String host = null;
        if (matchers != null)
        {
            for (SNIMatcher m : matchers)
            {
                if (m instanceof SslContextFactory.AliasSNIMatcher)
                {
                    SslContextFactory.AliasSNIMatcher matcher = (SslContextFactory.AliasSNIMatcher)m;
                    host = matcher.getHost();
                    break;
                }
            }
        }

        SslContextFactory.SNISelector sniSelector = _sslContextFactory.getSNISelector();
        if (sniSelector == null)
            sniSelector = _sslContextFactory;

        try
        {
            // Filter the certificates by alias.
            Collection<X509> certificates = new ArrayList<>();
            for (String alias : aliases)
            {
                X509 x509 = _sslContextFactory.getX509(alias);
                if (x509 != null)
                    certificates.add(x509);
            }
            X509 x509 = sniSelector.sniSelect(keyType, issuers, session, host, certificates);
            if (x509 == null)
            {
                return NO_MATCHERS;
            }
            else
            {
                // Make sure we got back an X509 from the collection we passed in.
                if (!certificates.contains(x509))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Invalid X509 match for SNI {}: {}", host, x509);
                    return null;
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("Matched SNI {} with X509 {} from {}", host, x509, Arrays.asList(aliases));
                session.putValue(SNI_X509, x509);
                return x509.getAlias();
            }
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
        String alias = socket == null ? NO_MATCHERS : chooseServerAlias(keyType, issuers, sslSocket.getSSLParameters().getSNIMatchers(), sslSocket.getHandshakeSession());
        if (alias == NO_MATCHERS)
            alias = _delegate.chooseServerAlias(keyType, issuers, socket);
        if (LOG.isDebugEnabled())
            LOG.debug("Chose alias {}/{} on {}", alias, keyType, socket);
        return alias;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine)
    {
        String alias = engine == null ? NO_MATCHERS : chooseServerAlias(keyType, issuers, engine.getSSLParameters().getSNIMatchers(), engine.getHandshakeSession());
        if (alias == NO_MATCHERS)
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
}
