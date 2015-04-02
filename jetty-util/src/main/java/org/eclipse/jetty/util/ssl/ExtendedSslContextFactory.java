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

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.X509ExtendedKeyManager;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * The Extended SSL ContextFactory supports additional SslContext features
 * that are only available in Java-8, specifically: <ul>
 * <li>{@link #setUseCipherSuitesOrder(boolean)}</li>
 * <li>SNI - Server Name Indicator</li>
 * </ul>
 * 
 * <p>If the KeyStore contains multiple X509 certificates, then the CN element 
 * of the distinguished name is used to select the certificate alias to use for
 * a connection.  Simple wildcard names (eg *.domain.com) are supported.
 * 
 */
public class ExtendedSslContextFactory extends SslContextFactory
{
    static final Logger LOG = Log.getLogger(ExtendedSslContextFactory.class);
    private final Map<String,String> _aliases = new HashMap<>();
    private boolean _useCipherSuitesOrder=true;

    public boolean isUseCipherSuitesOrder()
    {
        return _useCipherSuitesOrder;
    }

    public void setUseCipherSuitesOrder(boolean useCipherSuitesOrder)
    {
        _useCipherSuitesOrder = useCipherSuitesOrder;
    }

    /**
     * Create the SSLContext object and start the lifecycle
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        _aliases.clear();
        if (_factory._keyStore!=null)
        {
            for (String alias : Collections.list(_factory._keyStore.aliases()))
            {
                Certificate certificate = _factory._keyStore.getCertificate(alias);
                if ("X.509".equals(certificate.getType()))
                {
                    X509Certificate x509 = (X509Certificate)certificate;
                    String cn = x509.getSubjectX500Principal().getName("CANONICAL");

                    if (cn.startsWith("cn="))
                    {
                        cn=cn.substring(3,cn.indexOf(","));
                        _aliases.put(alias,cn);
                    }
                }                    
            }
        }
        
        LOG.info("aliases={} for {}",_aliases,this);
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        _aliases.clear();
    }

    protected KeyManager[] getKeyManagers(KeyStore keyStore) throws Exception
    {
        KeyManager[] managers = super.getKeyManagers(keyStore);
        if (managers!=null)
        {
            for (int idx = 0; idx < managers.length; idx++)
            {
                if (managers[idx] instanceof X509ExtendedKeyManager)
                    managers[idx]=new SniX509ExtendedKeyManager((X509ExtendedKeyManager)managers[idx],getCertAlias());
            }
        }

        LOG.debug("managers={} for {}",managers,this);
        return managers;
    }

    public void customize(SSLEngine sslEngine)
    {
        super.customize(sslEngine);
        SSLParameters sslParams = sslEngine.getSSLParameters();
        
        sslParams.setUseCipherSuitesOrder(_useCipherSuitesOrder);
        sslParams.setSNIMatchers(Collections.singletonList((SNIMatcher)new AliasSNIMatcher()));  
        sslEngine.setSSLParameters(sslParams);   
    }

    class AliasSNIMatcher extends SNIMatcher
    {
        private String _alias;
        private SNIHostName _name;
        
        protected AliasSNIMatcher()
        {
            super(StandardConstants.SNI_HOST_NAME);
        }

        @Override
        public boolean matches(SNIServerName serverName)
        {
            LOG.debug("matches={} for {}",serverName,this);
            if (serverName instanceof SNIHostName)
            {
                _name=(SNIHostName)serverName;

                // If we don't have a SNI name, or didn't see any certificate aliases,
                // just say true as it will either somehow work or fail elsewhere
                if (_name==null || _aliases.size()==0)
                    return true;

                for (String alias:_aliases.keySet())
                {
                    String cn = _aliases.get(alias);
                    
                    if (cn.equals(_name.getAsciiName()))
                    {
                        _alias=alias;
                        LOG.debug("matches={}",alias);
                        return true;
                    }

                    if (cn.startsWith("*."))
                    {
                        String domain = _name.getAsciiName();
                        domain=domain.substring(domain.indexOf('.'));
                        
                        if (cn.substring(1).equals(domain))
                        {
                            _alias=alias;
                            LOG.debug("matches={}",alias);
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        public String getAlias()
        {
            return _alias;
        }
        
        public SNIHostName getServerName()
        {
            return _name;
        }
    }
}
