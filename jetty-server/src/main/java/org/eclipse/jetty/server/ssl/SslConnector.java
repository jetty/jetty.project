//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.ssl;

import java.io.File;
import java.security.SecureRandom;
import java.security.Security;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.ssl.SslContextFactory;


/* ------------------------------------------------------------ */
/** The interface for SSL connectors and their configuration methods.
 * 
 */
public interface SslConnector extends Connector
{
    @Deprecated
    public static final String DEFAULT_KEYSTORE_ALGORITHM=(Security.getProperty("ssl.KeyManagerFactory.algorithm")==null?"SunX509":Security.getProperty("ssl.KeyManagerFactory.algorithm"));
    @Deprecated
    public static final String DEFAULT_TRUSTSTORE_ALGORITHM=(Security.getProperty("ssl.TrustManagerFactory.algorithm")==null?"SunX509":Security.getProperty("ssl.TrustManagerFactory.algorithm"));

    /** Default value for the keystore location path. @deprecated */
    @Deprecated
    public static final String DEFAULT_KEYSTORE = System.getProperty("user.home") + File.separator + ".keystore";
    
    /** String name of key password property. @deprecated */
    @Deprecated
    public static final String KEYPASSWORD_PROPERTY = "org.eclipse.jetty.ssl.keypassword";
    
    /** String name of keystore password property. @deprecated */
    @Deprecated
    public static final String PASSWORD_PROPERTY = "org.eclipse.jetty.ssl.password";
    
    
    /* ------------------------------------------------------------ */
    /**
     * @return the instance of SslContextFactory associated with the connector
     */
    public SslContextFactory getSslContextFactory();
        
    /* ------------------------------------------------------------ */
    /**
     * @return The array of Ciphersuite names to exclude from 
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     * @deprecated
     */
    @Deprecated
    public abstract String[] getExcludeCipherSuites();

    /* ------------------------------------------------------------ */
    /**
     * @param cipherSuites The array of Ciphersuite names to exclude from 
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     * @deprecated
     */
    @Deprecated
    public abstract void setExcludeCipherSuites(String[] cipherSuites);

    /* ------------------------------------------------------------ */
    /**
     * @return The array of Ciphersuite names to include in
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     * @deprecated
     */
    @Deprecated
    public abstract String[] getIncludeCipherSuites();

    /* ------------------------------------------------------------ */
    /**
     * @param cipherSuites The array of Ciphersuite names to include in 
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     * @deprecated
     */
    @Deprecated
    public abstract void setIncludeCipherSuites(String[] cipherSuites);

    /* ------------------------------------------------------------ */
    /**
     * @param password The password for the key store
     * @deprecated
     */
    @Deprecated
    public abstract void setPassword(String password);

    /* ------------------------------------------------------------ */
    /**
     * @param password The password for the trust store
     * @deprecated
     */
    @Deprecated
    public abstract void setTrustPassword(String password);

    /* ------------------------------------------------------------ */
    /**
     * @param password The password (if any) for the specific key within 
     * the key store
     * @deprecated
     */
    @Deprecated
    public abstract void setKeyPassword(String password);

    /* ------------------------------------------------------------ */
    /**
     * @return The SSL protocol (default "TLS") passed to {@link SSLContext#getInstance(String, String)}
     * @deprecated
     */
    @Deprecated
    public abstract String getProtocol();

    /* ------------------------------------------------------------ */
    /**
     * @param protocol The SSL protocol (default "TLS") passed to {@link SSLContext#getInstance(String, String)}
     * @deprecated
     */
    @Deprecated
    public abstract void setProtocol(String protocol);

    /* ------------------------------------------------------------ */
    /**
     * @param keystore The file or URL of the SSL Key store.
     * @deprecated
     */
    @Deprecated
    public abstract void setKeystore(String keystore);

    /* ------------------------------------------------------------ */
    /**
     * @return The file or URL of the SSL Key store.
     * @deprecated
     */
    @Deprecated
    public abstract String getKeystore();

    /* ------------------------------------------------------------ */
    /**
     * @return The type of the key store (default "JKS")
     * @deprecated
     */
    @Deprecated
    public abstract String getKeystoreType();

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL needs client authentication.
     * @see SSLEngine#getNeedClientAuth()
     * @deprecated
     */
    @Deprecated
    public abstract boolean getNeedClientAuth();

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL wants client authentication.
     * @see SSLEngine#getWantClientAuth()
     * @deprecated
     */
    @Deprecated
    public abstract boolean getWantClientAuth();

    /* ------------------------------------------------------------ */
    /**
     * @param needClientAuth True if SSL needs client authentication.
     * @see SSLEngine#getNeedClientAuth()
     * @deprecated
     */
    @Deprecated
    public abstract void setNeedClientAuth(boolean needClientAuth);

    /* ------------------------------------------------------------ */
    /**
     * @param wantClientAuth True if SSL wants client authentication.
     * @see SSLEngine#getWantClientAuth()
     * @deprecated
     */
    @Deprecated
    public abstract void setWantClientAuth(boolean wantClientAuth);

    /* ------------------------------------------------------------ */
    /**
     * @param keystoreType The type of the key store (default "JKS")
     * @deprecated
     */
    @Deprecated
    public abstract void setKeystoreType(String keystoreType);

    /* ------------------------------------------------------------ */
    /**
     * @return The SSL provider name, which if set is passed to 
     * {@link SSLContext#getInstance(String, String)}
     * @deprecated
     */
    @Deprecated
    public abstract String getProvider();

    /* ------------------------------------------------------------ */
    /**
     * @return The algorithm name, which if set is passed to 
     * {@link SecureRandom#getInstance(String)} to obtain the {@link SecureRandom}
     * instance passed to {@link SSLContext#init(javax.net.ssl.KeyManager[], javax.net.ssl.TrustManager[], SecureRandom)}
     * @deprecated
     */
    @Deprecated
    public abstract String getSecureRandomAlgorithm();

    /* ------------------------------------------------------------ */
    /**
     * @return The algorithm name (default "SunX509") used by the {@link KeyManagerFactory}
     * @deprecated
     */
    @Deprecated
    public abstract String getSslKeyManagerFactoryAlgorithm();

    /* ------------------------------------------------------------ */
    /**
     * @return The algorithm name (default "SunX509") used by the {@link TrustManagerFactory}
     * @deprecated
     */
    @Deprecated
    public abstract String getSslTrustManagerFactoryAlgorithm();

    /* ------------------------------------------------------------ */
    /**
     * @return The file name or URL of the trust store location
     * @deprecated
     */
    @Deprecated
    public abstract String getTruststore();

    /* ------------------------------------------------------------ */
    /**
     * @return The type of the trust store (default "JKS")
     * @deprecated
     */
    @Deprecated
    public abstract String getTruststoreType();

    /* ------------------------------------------------------------ */
    /**
     * @param provider The SSL provider name, which if set is passed to 
     * {@link SSLContext#getInstance(String, String)}
     * @deprecated
     */
    @Deprecated
    public abstract void setProvider(String provider);

    /* ------------------------------------------------------------ */
    /**
     * @param algorithm The algorithm name, which if set is passed to 
     * {@link SecureRandom#getInstance(String)} to obtain the {@link SecureRandom}
     * instance passed to {@link SSLContext#init(javax.net.ssl.KeyManager[], javax.net.ssl.TrustManager[], SecureRandom)}
     * @deprecated
     */
    @Deprecated
    public abstract void setSecureRandomAlgorithm(String algorithm);

    /* ------------------------------------------------------------ */
    /**
     * @param algorithm The algorithm name (default "SunX509") used by 
     * the {@link KeyManagerFactory}
     * @deprecated
     */
    @Deprecated
    public abstract void setSslKeyManagerFactoryAlgorithm(String algorithm);

    /* ------------------------------------------------------------ */
    /**
     * @param algorithm The algorithm name (default "SunX509") used by the {@link TrustManagerFactory}
     * @deprecated
     */
    @Deprecated
    public abstract void setSslTrustManagerFactoryAlgorithm(String algorithm);

    /* ------------------------------------------------------------ */
    /**
     * @param truststore The file name or URL of the trust store location
     * @deprecated
     */
    @Deprecated
    public abstract void setTruststore(String truststore);

    /* ------------------------------------------------------------ */
    /**
     * @param truststoreType The type of the trust store (default "JKS")
     * @deprecated
     */
    @Deprecated
    public abstract void setTruststoreType(String truststoreType);

    /* ------------------------------------------------------------ */
    /**
     * @param sslContext Set a preconfigured SSLContext
     * @deprecated
     */
    @Deprecated
    public abstract void setSslContext(SSLContext sslContext);
    
    /* ------------------------------------------------------------ */
    /**
     * @return The SSLContext
     * @deprecated
     */
    @Deprecated
    public abstract SSLContext getSslContext();
    

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL re-negotiation is allowed (default false)
     * @deprecated
     */
    @Deprecated
    public boolean isAllowRenegotiate();

    /* ------------------------------------------------------------ */
    /**
     * Set if SSL re-negotiation is allowed. CVE-2009-3555 discovered
     * a vulnerability in SSL/TLS with re-negotiation.  If your JVM
     * does not have CVE-2009-3555 fixed, then re-negotiation should 
     * not be allowed.
     * @param allowRenegotiate true if re-negotiation is allowed (default false)
     * @deprecated
     */
    @Deprecated
    public void setAllowRenegotiate(boolean allowRenegotiate);
}
