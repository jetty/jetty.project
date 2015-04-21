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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    public final static Pattern __cnPattern = Pattern.compile(".*[cC][nN]=\\h*([^,\\h]*).*");
    private final Map<String,String> _aliases = new HashMap<>();
    private final Map<String,String> _wild = new HashMap<>();
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
                    boolean named=false;
                    
                    Collection<List<?>> altNames = x509.getSubjectAlternativeNames();
                    if (altNames!=null)
                    {
                        for (List<?> list : altNames)
                        {
                            if (((Number)list.get(0)).intValue() == 2 )
                            {
                                String cn = list.get(1).toString();
                                LOG.info("Certificate san alias={} cn={} in {}",alias,cn,_factory);
                                if (cn!=null)
                                {
                                    named=true;
                                    _aliases.put(cn,alias);
                                }
                            }
                        }
                    }
                    
                    if (!named)
                    {
                        Matcher matcher = __cnPattern.matcher(x509.getSubjectX500Principal().getName("CANONICAL"));
                        if (matcher.matches())
                        {
                            String cn = matcher.group(1);
                            LOG.info("Certificate cn alias={} cn={} in {}",alias,cn,_factory);
                            if (cn!=null)
                                _aliases.put(cn,alias);
                        }
                    }
                }                    
            }
        }
        
        // find wild aliases
        _wild.clear();
        for (String name : _aliases.keySet())
            if (name.startsWith("*."))
                _wild.put(name.substring(1),_aliases.get(name));
        
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

                // Try an exact match
                _alias = _aliases.get(_name.getAsciiName());
                if (_alias!=null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("matched {}->{}",_name.getAsciiName(),_alias);
                    return true;
                }
                
                // Try wild card matches
                for (String wild:_wild.keySet())
                {
                    String domain = _name.getAsciiName();
                    domain=domain.substring(domain.indexOf('.'));

                    if (wild.equals(domain))
                    {
                        _alias=_wild.get(wild);
                        if (LOG.isDebugEnabled())
                            LOG.debug("wild match {}->{}",_name.getAsciiName(),_alias);
                        return true;
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
