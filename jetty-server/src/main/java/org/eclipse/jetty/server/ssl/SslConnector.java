package org.eclipse.jetty.server.ssl;

import java.io.File;
import java.security.SecureRandom;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jetty.server.Connector;


/* ------------------------------------------------------------ */
/** The interface for SSL connectors and their configuration methods.
 * 
 */
public interface SslConnector extends Connector
{

    /** Default value for the keystore location path. */
    public static final String DEFAULT_KEYSTORE = System.getProperty("user.home") + File.separator + ".keystore";
    
    /** String name of key password property. */
    public static final String KEYPASSWORD_PROPERTY = "org.eclipse.jetty.ssl.keypassword";
    
    /** String name of keystore password property. */
    public static final String PASSWORD_PROPERTY = "org.eclipse.jetty.ssl.password";

    
    /* ------------------------------------------------------------ */
    /**
     * @return The array of Ciphersuite names to exclude from 
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public abstract String[] getExcludeCipherSuites();

    /* ------------------------------------------------------------ */
    /**
     * @param cipherSuites The array of Ciphersuite names to exclude from 
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public abstract void setExcludeCipherSuites(String[] cipherSuites);

    /* ------------------------------------------------------------ */
    /**
     * @param password The password for the key store
     */
    public abstract void setPassword(String password);

    /* ------------------------------------------------------------ */
    /**
     * @param password The password for the trust store
     */
    public abstract void setTrustPassword(String password);

    /* ------------------------------------------------------------ */
    /**
     * @param password The password (if any) for the specific key within 
     * the key store
     */
    public abstract void setKeyPassword(String password);

    /* ------------------------------------------------------------ */
    /**
     * @return The SSL protocol (default "TLS") passed to {@link SSLContext#getInstance(String, String)}
     */
    public abstract String getProtocol();

    /* ------------------------------------------------------------ */
    /**
     * @param protocol The SSL protocol (default "TLS") passed to {@link SSLContext#getInstance(String, String)}

     */
    public abstract void setProtocol(String protocol);

    /* ------------------------------------------------------------ */
    /**
     * @param keystore The file or URL of the SSL Key store.
     */
    public abstract void setKeystore(String keystore);

    /* ------------------------------------------------------------ */
    /**
     * @return The file or URL of the SSL Key store.
     */
    public abstract String getKeystore();

    /* ------------------------------------------------------------ */
    /**
     * @return The type of the key store (default "JKS")
     */
    public abstract String getKeystoreType();

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL needs client authentication.
     * @see SSLEngine#getNeedClientAuth()
     */
    public abstract boolean getNeedClientAuth();

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL wants client authentication.
     * @see SSLEngine#getWantClientAuth()
     */
    public abstract boolean getWantClientAuth();

    /* ------------------------------------------------------------ */
    /**
     * @param needClientAuth True if SSL needs client authentication.
     * @see SSLEngine#getNeedClientAuth()
     */
    public abstract void setNeedClientAuth(boolean needClientAuth);

    /* ------------------------------------------------------------ */
    /**
     * @param wantClientAuth True if SSL wants client authentication.
     * @see SSLEngine#getWantClientAuth()
     */
    public abstract void setWantClientAuth(boolean wantClientAuth);

    /* ------------------------------------------------------------ */
    /**
     * @param keystoreType The type of the key store (default "JKS")
     */
    public abstract void setKeystoreType(String keystoreType);

    /* ------------------------------------------------------------ */
    /**
     * @return The SSL provider name, which if set is passed to 
     * {@link SSLContext#getInstance(String, String)}
     */
    public abstract String getProvider();

    /* ------------------------------------------------------------ */
    /**
     * @return The algorithm name, which if set is passed to 
     * {@link SecureRandom#getInstance(String)} to obtain the {@link SecureRandom}
     * instance passed to {@link SSLContext#init(javax.net.ssl.KeyManager[], javax.net.ssl.TrustManager[], SecureRandom)}
     */
    public abstract String getSecureRandomAlgorithm();

    /* ------------------------------------------------------------ */
    /**
     * @return The algorithm name (default "SunX509") used by the {@link KeyManagerFactory}
     */
    public abstract String getSslKeyManagerFactoryAlgorithm();

    /* ------------------------------------------------------------ */
    /**
     * @return The algorithm name (default "SunX509") used by the {@link TrustManagerFactory}
     */
    public abstract String getSslTrustManagerFactoryAlgorithm();

    /* ------------------------------------------------------------ */
    /**
     * @return The file name or URL of the trust store location
     */
    public abstract String getTruststore();

    /* ------------------------------------------------------------ */
    /**
     * @return The type of the trust store (default "JKS")
     */
    public abstract String getTruststoreType();

    /* ------------------------------------------------------------ */
    /**
     * @param provider The SSL provider name, which if set is passed to 
     * {@link SSLContext#getInstance(String, String)}
     */
    public abstract void setProvider(String provider);

    /* ------------------------------------------------------------ */
    /**
     * @param algorithm The algorithm name, which if set is passed to 
     * {@link SecureRandom#getInstance(String)} to obtain the {@link SecureRandom}
     * instance passed to {@link SSLContext#init(javax.net.ssl.KeyManager[], javax.net.ssl.TrustManager[], SecureRandom)}
    
     */
    public abstract void setSecureRandomAlgorithm(String algorithm);

    /* ------------------------------------------------------------ */
    /**
     * @param algorithm The algorithm name (default "SunX509") used by 
     * the {@link KeyManagerFactory}
     */
    public abstract void setSslKeyManagerFactoryAlgorithm(String algorithm);

    /* ------------------------------------------------------------ */
    /**
     * @param algorithm The algorithm name (default "SunX509") used by the {@link TrustManagerFactory}
     */
    public abstract void setSslTrustManagerFactoryAlgorithm(String algorithm);

    /* ------------------------------------------------------------ */
    /**
     * @param truststore The file name or URL of the trust store location
     */
    public abstract void setTruststore(String truststore);

    /* ------------------------------------------------------------ */
    /**
     * @param truststoreType The type of the trust store (default "JKS")
     */
    public abstract void setTruststoreType(String truststoreType);

    /* ------------------------------------------------------------ */
    /**
     * @param sslContext Set a preconfigured SSLContext
     */
    public abstract void setSslContext(SSLContext sslContext);
    
    /* ------------------------------------------------------------ */
    /**
     * @return The SSLContext
     */
    public abstract SSLContext getSslContext();
    

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL re-negotiation is allowed (default false)
     */
    public boolean isAllowRenegotiate();

    /* ------------------------------------------------------------ */
    /**
     * Set if SSL re-negotiation is allowed. CVE-2009-3555 discovered
     * a vulnerability in SSL/TLS with re-negotiation.  If your JVM
     * does not have CVE-2009-3555 fixed, then re-negotiation should 
     * not be allowed.
     * @param allowRenegotiate true if re-negotiation is allowed (default false)
     */
    public void setAllowRenegotiate(boolean allowRenegotiate);
}