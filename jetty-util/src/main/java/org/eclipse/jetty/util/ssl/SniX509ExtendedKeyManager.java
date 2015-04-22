//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

/* ------------------------------------------------------------ */
/**
 * KeyManager to select a key with desired alias
 * while delegating processing to specified KeyManager
 * Can be used both with server and client sockets
 */
public class SniX509ExtendedKeyManager extends X509ExtendedKeyManager
{
    static final Logger LOG = Log.getLogger(SniX509ExtendedKeyManager.class);
    public final static String SNI_NAME = "org.eclipse.jetty.util.ssl.sniname";
    public final static String NO_MATCHERS="No Matchers";
    private final X509ExtendedKeyManager _delegate;

    /* ------------------------------------------------------------ */
    /**
     * Construct KeyManager instance
     * @param keyManager Instance of KeyManager to be wrapped
     * @param alias Alias of the key to be selected if no SNI selection
     * @throws Exception if unable to create X509ExtendedKeyManager
     */
    public SniX509ExtendedKeyManager(X509ExtendedKeyManager keyManager,String alias) throws Exception
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
             
        // Look for an SNI alias
        String alias=null;
        String host=null;
        if (matchers!=null)
        {
            for (SNIMatcher m : matchers)
            {
                if (m instanceof ExtendedSslContextFactory.AliasSNIMatcher)
                {
                    ExtendedSslContextFactory.AliasSNIMatcher matcher = (ExtendedSslContextFactory.AliasSNIMatcher)m;
                    alias=matcher.getAlias();
                    host=matcher.getServerName();
                    break;
                }
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("choose {} from {}",alias,Arrays.asList(aliases));
        
        // Check if the SNI selected alias is allowable
        if (alias!=null)
        {
            for (String a:aliases)
            {
                if (a.equals(alias))
                {
                    session.putValue(SNI_NAME,host);
                    return alias;
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
        
        String alias = chooseServerAlias(keyType,issuers,sslSocket.getSSLParameters().getSNIMatchers(),sslSocket.getHandshakeSession());
        return alias==NO_MATCHERS?_delegate.chooseServerAlias(keyType,issuers,socket):alias;
    }
        
    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine)
    {
        String alias = chooseServerAlias(keyType,issuers,engine.getSSLParameters().getSNIMatchers(),engine.getHandshakeSession());
        return alias==NO_MATCHERS?_delegate.chooseEngineServerAlias(keyType,issuers,engine):alias;
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
