//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

    public SniX509ExtendedKeyManager(X509ExtendedKeyManager keyManager)
    {
        _delegate = keyManager;
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket)
    {
        return _delegate.chooseClientAlias(keyType,issuers,socket);
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine)
    {
        return _delegate.chooseEngineClientAlias(keyType,issuers,engine);
    }

    protected String chooseServerAlias(String keyType, Principal[] issuers, Collection<SNIMatcher> matchers, SSLSession session)
    {
        // Look for the aliases that are suitable for the keytype and issuers
        String[] aliases = _delegate.getServerAliases(keyType,issuers);
        if (aliases==null || aliases.length==0)
            return null;

        // Look for the SNI information.
        String host=null;
        X509 x509=null;
        if (matchers!=null)
        {
            for (SNIMatcher m : matchers)
            {
                if (m instanceof SslContextFactory.AliasSNIMatcher)
                {
                    SslContextFactory.AliasSNIMatcher matcher = (SslContextFactory.AliasSNIMatcher)m;
                    host=matcher.getHost();
                    x509=matcher.getX509();
                    break;
                }
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Matched {} with {} from {}",host,x509,Arrays.asList(aliases));

        // Check if the SNI selected alias is allowable
        if (x509!=null)
        {
            for (String a:aliases)
            {
                if (a.equals(x509.getAlias()))
                {
                    session.putValue(SNI_X509,x509);
                    return a;
                }
            }
            return null;
        }
        return NO_MATCHERS;
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket)
    {
        SSLSocket sslSocket = (SSLSocket)socket;
        String alias = socket==null?NO_MATCHERS:chooseServerAlias(keyType,issuers,sslSocket.getSSLParameters().getSNIMatchers(),sslSocket.getHandshakeSession());
        if (alias==NO_MATCHERS)
            alias=_delegate.chooseServerAlias(keyType,issuers,socket);
        if (LOG.isDebugEnabled())
            LOG.debug("Chose alias {}/{} on {}",alias,keyType,socket);
        return alias;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine)
    {
        String alias = engine==null?NO_MATCHERS:chooseServerAlias(keyType,issuers,engine.getSSLParameters().getSNIMatchers(),engine.getHandshakeSession());
        if (alias==NO_MATCHERS)
            alias=_delegate.chooseEngineServerAlias(keyType,issuers,engine);
        if (LOG.isDebugEnabled())
            LOG.debug("Chose alias {}/{} on {}",alias,keyType,engine);
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
        return _delegate.getClientAliases(keyType,issuers);
    }

    @Override
    public PrivateKey getPrivateKey(String alias)
    {
        return _delegate.getPrivateKey(alias);
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers)
    {
        return _delegate.getServerAliases(keyType,issuers);
    }
}
