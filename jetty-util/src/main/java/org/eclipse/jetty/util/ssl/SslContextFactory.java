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

package org.eclipse.jetty.util.ssl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.InvalidParameterException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CRL;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.CertificateUtils;
import org.eclipse.jetty.util.security.CertificateValidator;
import org.eclipse.jetty.util.security.Password;


/* ------------------------------------------------------------ */
/**
 * SslContextFactory is used to configure SSL connectors
 * as well as HttpClient. It holds all SSL parameters and
 * creates SSL context based on these parameters to be
 * used by the SSL connectors.
 */
public class SslContextFactory extends AbstractLifeCycle
{
    public final static TrustManager[] TRUST_ALL_CERTS = new X509TrustManager[]{new X509TrustManager()
    {
        public java.security.cert.X509Certificate[] getAcceptedIssuers()
        {
            return new java.security.cert.X509Certificate[]{};
        }

        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType)
        {
        }

        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType)
        {
        }
    }};

    private static final Logger LOG = Log.getLogger(SslContextFactory.class);

    public static final String DEFAULT_KEYMANAGERFACTORY_ALGORITHM =
        (Security.getProperty("ssl.KeyManagerFactory.algorithm") == null ?
                "SunX509" : Security.getProperty("ssl.KeyManagerFactory.algorithm"));
    public static final String DEFAULT_TRUSTMANAGERFACTORY_ALGORITHM =
        (Security.getProperty("ssl.TrustManagerFactory.algorithm") == null ?
                "SunX509" : Security.getProperty("ssl.TrustManagerFactory.algorithm"));

    /** Default value for the keystore location path. */
    public static final String DEFAULT_KEYSTORE_PATH =
        System.getProperty("user.home") + File.separator + ".keystore";

    /** String name of key password property. */
    public static final String KEYPASSWORD_PROPERTY = "org.eclipse.jetty.ssl.keypassword";

    /** String name of keystore password property. */
    public static final String PASSWORD_PROPERTY = "org.eclipse.jetty.ssl.password";

    /** Excluded protocols. */
    private final Set<String> _excludeProtocols = new LinkedHashSet<String>();
    /** Included protocols. */
    private Set<String> _includeProtocols = null;

    /** Excluded cipher suites. */
    private final Set<String> _excludeCipherSuites = new LinkedHashSet<String>();
    /** Included cipher suites. */
    private Set<String> _includeCipherSuites = null;

    /** Keystore path. */
    private String _keyStorePath;
    /** Keystore provider name */
    private String _keyStoreProvider;
    /** Keystore type */
    private String _keyStoreType = "JKS";
    /** Keystore input stream */
    private InputStream _keyStoreInputStream;

    /** SSL certificate alias */
    private String _certAlias;

    /** Truststore path */
    private String _trustStorePath;
    /** Truststore provider name */
    private String _trustStoreProvider;
    /** Truststore type */
    private String _trustStoreType = "JKS";
    /** Truststore input stream */
    private InputStream _trustStoreInputStream;

    /** Set to true if client certificate authentication is required */
    private boolean _needClientAuth = false;
    /** Set to true if client certificate authentication is desired */
    private boolean _wantClientAuth = false;

    /** Set to true if renegotiation is allowed */
    private boolean _allowRenegotiate = true;

    /** Keystore password */
    private transient Password _keyStorePassword;
    /** Key manager password */
    private transient Password _keyManagerPassword;
    /** Truststore password */
    private transient Password _trustStorePassword;

    /** SSL provider name */
    private String _sslProvider;
    /** SSL protocol name */
    private String _sslProtocol = "TLS";

    /** SecureRandom algorithm */
    private String _secureRandomAlgorithm;
    /** KeyManager factory algorithm */
    private String _keyManagerFactoryAlgorithm = DEFAULT_KEYMANAGERFACTORY_ALGORITHM;
    /** TrustManager factory algorithm */
    private String _trustManagerFactoryAlgorithm = DEFAULT_TRUSTMANAGERFACTORY_ALGORITHM;

    /** Set to true if SSL certificate validation is required */
    private boolean _validateCerts;
    /** Set to true if SSL certificate of the peer validation is required */
    private boolean _validatePeerCerts;
    /** Maximum certification path length (n - number of intermediate certs, -1 for unlimited) */
    private int _maxCertPathLength = -1;
    /** Path to file that contains Certificate Revocation List */
    private String _crlPath;
    /** Set to true to enable CRL Distribution Points (CRLDP) support */
    private boolean _enableCRLDP = false;
    /** Set to true to enable On-Line Certificate Status Protocol (OCSP) support */
    private boolean _enableOCSP = false;
    /** Location of OCSP Responder */
    private String _ocspResponderURL;

    /** SSL keystore */
    private KeyStore _keyStore;
    /** SSL truststore */
    private KeyStore _trustStore;
    /** Set to true to enable SSL Session caching */
    private boolean _sessionCachingEnabled = true;
    /** SSL session cache size */
    private int _sslSessionCacheSize;
    /** SSL session timeout */
    private int _sslSessionTimeout;

    /** SSL context */
    private SSLContext _context;

    private boolean _trustAll;

    /* ------------------------------------------------------------ */
    /**
     * Construct an instance of SslContextFactory
     * Default constructor for use in XmlConfiguration files
     */
    public SslContextFactory()
    {
        _trustAll=true;
    }

    /* ------------------------------------------------------------ */
    /**
     * Construct an instance of SslContextFactory
     * Default constructor for use in XmlConfiguration files
     * @param trustAll whether to blindly trust all certificates
     * @see #setTrustAll(boolean)
     */
    public SslContextFactory(boolean trustAll)
    {
        _trustAll=trustAll;
    }

    /* ------------------------------------------------------------ */
    /**
     * Construct an instance of SslContextFactory
     * @param keyStorePath default keystore location
     */
    public SslContextFactory(String keyStorePath)
    {
        _keyStorePath = keyStorePath;
    }

    /* ------------------------------------------------------------ */
    /**
     * Create the SSLContext object and start the lifecycle
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        if (_context == null)
        {
            if (_keyStore==null && _keyStoreInputStream == null && _keyStorePath == null &&
                _trustStore==null && _trustStoreInputStream == null && _trustStorePath == null )
            {
                TrustManager[] trust_managers=null;

                if (_trustAll)
                {
                    LOG.debug("No keystore or trust store configured.  ACCEPTING UNTRUSTED CERTIFICATES!!!!!");
                    // Create a trust manager that does not validate certificate chains
                    trust_managers = TRUST_ALL_CERTS;
                }

                SecureRandom secureRandom = (_secureRandomAlgorithm == null)?null:SecureRandom.getInstance(_secureRandomAlgorithm);
                _context = SSLContext.getInstance(_sslProtocol);
                _context.init(null, trust_managers, secureRandom);
            }
            else
            {
                // verify that keystore and truststore
                // parameters are set up correctly
                checkKeyStore();

                KeyStore keyStore = loadKeyStore();
                KeyStore trustStore = loadTrustStore();

                Collection<? extends CRL> crls = loadCRL(_crlPath);

                if (_validateCerts && keyStore != null)
                {
                    if (_certAlias == null)
                    {
                        List<String> aliases = Collections.list(keyStore.aliases());
                        _certAlias = aliases.size() == 1 ? aliases.get(0) : null;
                    }

                    Certificate cert = _certAlias == null?null:keyStore.getCertificate(_certAlias);
                    if (cert == null)
                    {
                        throw new Exception("No certificate found in the keystore" + (_certAlias==null ? "":" for alias " + _certAlias));
                    }

                    CertificateValidator validator = new CertificateValidator(trustStore, crls);
                    validator.setMaxCertPathLength(_maxCertPathLength);
                    validator.setEnableCRLDP(_enableCRLDP);
                    validator.setEnableOCSP(_enableOCSP);
                    validator.setOcspResponderURL(_ocspResponderURL);
                    validator.validate(keyStore, cert);
                }

                KeyManager[] keyManagers = getKeyManagers(keyStore);
                TrustManager[] trustManagers = getTrustManagers(trustStore,crls);

                SecureRandom secureRandom = (_secureRandomAlgorithm == null)?null:SecureRandom.getInstance(_secureRandomAlgorithm);
                _context = (_sslProvider == null)?SSLContext.getInstance(_sslProtocol):SSLContext.getInstance(_sslProtocol,_sslProvider);
                _context.init(keyManagers,trustManagers,secureRandom);

                SSLEngine engine=newSslEngine();

                LOG.info("Enabled Protocols {} of {}",Arrays.asList(engine.getEnabledProtocols()),Arrays.asList(engine.getSupportedProtocols()));
                if (LOG.isDebugEnabled())
                    LOG.debug("Enabled Ciphers   {} of {}",Arrays.asList(engine.getEnabledCipherSuites()),Arrays.asList(engine.getSupportedCipherSuites()));
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The array of protocol names to exclude from
     * {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public String[] getExcludeProtocols()
    {
        return _excludeProtocols.toArray(new String[_excludeProtocols.size()]);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param protocols
     *            The array of protocol names to exclude from
     *            {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public void setExcludeProtocols(String... protocols)
    {
        checkNotStarted();

        _excludeProtocols.clear();
        _excludeProtocols.addAll(Arrays.asList(protocols));
    }

    /* ------------------------------------------------------------ */
    /**
     * @param protocol Protocol names to add to {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public void addExcludeProtocols(String... protocol)
    {
        checkNotStarted();
        _excludeProtocols.addAll(Arrays.asList(protocol));
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The array of protocol names to include in
     * {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public String[] getIncludeProtocols()
    {
        return _includeProtocols.toArray(new String[_includeProtocols.size()]);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param protocols
     *            The array of protocol names to include in
     *            {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public void setIncludeProtocols(String... protocols)
    {
        checkNotStarted();

        _includeProtocols = new LinkedHashSet<String>(Arrays.asList(protocols));
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The array of cipher suite names to exclude from
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public String[] getExcludeCipherSuites()
    {
        return _excludeCipherSuites.toArray(new String[_excludeCipherSuites.size()]);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param cipherSuites
     *            The array of cipher suite names to exclude from
     *            {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public void setExcludeCipherSuites(String... cipherSuites)
    {
        checkNotStarted();
        _excludeCipherSuites.clear();
        _excludeCipherSuites.addAll(Arrays.asList(cipherSuites));
    }

    /* ------------------------------------------------------------ */
    /**
     * @param cipher Cipher names to add to {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public void addExcludeCipherSuites(String... cipher)
    {
        checkNotStarted();
        _excludeCipherSuites.addAll(Arrays.asList(cipher));
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The array of cipher suite names to include in
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public String[] getIncludeCipherSuites()
    {
        return _includeCipherSuites.toArray(new String[_includeCipherSuites.size()]);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param cipherSuites
     *            The array of cipher suite names to include in
     *            {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public void setIncludeCipherSuites(String... cipherSuites)
    {
        checkNotStarted();

        _includeCipherSuites = new LinkedHashSet<String>(Arrays.asList(cipherSuites));
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The file or URL of the SSL Key store.
     */
    public String getKeyStorePath()
    {
        return _keyStorePath;
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public String getKeyStore()
    {
        return _keyStorePath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param keyStorePath
     *            The file or URL of the SSL Key store.
     */
    public void setKeyStorePath(String keyStorePath)
    {
        checkNotStarted();

        _keyStorePath = keyStorePath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param keyStorePath the file system path or URL of the keystore
     * @deprecated Use {@link #setKeyStorePath(String)}
     */
    @Deprecated
    public void setKeyStore(String keyStorePath)
    {
        checkNotStarted();

        _keyStorePath = keyStorePath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The provider of the key store
     */
    public String getKeyStoreProvider()
    {
        return _keyStoreProvider;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param keyStoreProvider
     *            The provider of the key store
     */
    public void setKeyStoreProvider(String keyStoreProvider)
    {
        checkNotStarted();

        _keyStoreProvider = keyStoreProvider;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The type of the key store (default "JKS")
     */
    public String getKeyStoreType()
    {
        return (_keyStoreType);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param keyStoreType
     *            The type of the key store (default "JKS")
     */
    public void setKeyStoreType(String keyStoreType)
    {
        checkNotStarted();

        _keyStoreType = keyStoreType;
    }

    /* ------------------------------------------------------------ */
    /** Get the _keyStoreInputStream.
     * @return the _keyStoreInputStream
     *
     * @deprecated
     */
    @Deprecated
    public InputStream getKeyStoreInputStream()
    {
        checkKeyStore();

        return _keyStoreInputStream;
    }

    /* ------------------------------------------------------------ */
    /** Set the keyStoreInputStream.
     * @param keyStoreInputStream the InputStream to the KeyStore
     *
     * @deprecated Use {@link #setKeyStore(KeyStore)}
     */
    @Deprecated
    public void setKeyStoreInputStream(InputStream keyStoreInputStream)
    {
        checkNotStarted();

        _keyStoreInputStream = keyStoreInputStream;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Alias of SSL certificate for the connector
     */
    public String getCertAlias()
    {
        return _certAlias;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param certAlias
     *            Alias of SSL certificate for the connector
     */
    public void setCertAlias(String certAlias)
    {
        checkNotStarted();

        _certAlias = certAlias;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The file name or URL of the trust store location
     */
    public String getTrustStore()
    {
        return _trustStorePath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param trustStorePath
     *            The file name or URL of the trust store location
     */
    public void setTrustStore(String trustStorePath)
    {
        checkNotStarted();

        _trustStorePath = trustStorePath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The provider of the trust store
     */
    public String getTrustStoreProvider()
    {
        return _trustStoreProvider;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param trustStoreProvider
     *            The provider of the trust store
     */
    public void setTrustStoreProvider(String trustStoreProvider)
    {
        checkNotStarted();

        _trustStoreProvider = trustStoreProvider;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The type of the trust store (default "JKS")
     */
    public String getTrustStoreType()
    {
        return _trustStoreType;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param trustStoreType
     *            The type of the trust store (default "JKS")
     */
    public void setTrustStoreType(String trustStoreType)
    {
        checkNotStarted();

        _trustStoreType = trustStoreType;
    }

    /* ------------------------------------------------------------ */
    /** Get the _trustStoreInputStream.
     * @return the _trustStoreInputStream
     *
     * @deprecated
     */
    @Deprecated
    public InputStream getTrustStoreInputStream()
    {
        checkKeyStore();

        return _trustStoreInputStream;
    }

    /* ------------------------------------------------------------ */
    /** Set the _trustStoreInputStream.
     * @param trustStoreInputStream the InputStream to the TrustStore
     *
     * @deprecated
     */
    @Deprecated
    public void setTrustStoreInputStream(InputStream trustStoreInputStream)
    {
        checkNotStarted();

        _trustStoreInputStream = trustStoreInputStream;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL needs client authentication.
     * @see SSLEngine#getNeedClientAuth()
     */
    public boolean getNeedClientAuth()
    {
        return _needClientAuth;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param needClientAuth
     *            True if SSL needs client authentication.
     * @see SSLEngine#getNeedClientAuth()
     */
    public void setNeedClientAuth(boolean needClientAuth)
    {
        checkNotStarted();

        _needClientAuth = needClientAuth;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL wants client authentication.
     * @see SSLEngine#getWantClientAuth()
     */
    public boolean getWantClientAuth()
    {
        return _wantClientAuth;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param wantClientAuth
     *            True if SSL wants client authentication.
     * @see SSLEngine#getWantClientAuth()
     */
    public void setWantClientAuth(boolean wantClientAuth)
    {
        checkNotStarted();

        _wantClientAuth = wantClientAuth;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if SSL certificate has to be validated
     * @deprecated
     */
    @Deprecated
    public boolean getValidateCerts()
    {
        return _validateCerts;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if SSL certificate has to be validated
     */
    public boolean isValidateCerts()
    {
        return _validateCerts;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param validateCerts
     *            true if SSL certificates have to be validated
     */
    public void setValidateCerts(boolean validateCerts)
    {
        checkNotStarted();

        _validateCerts = validateCerts;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if SSL certificates of the peer have to be validated
     */
    public boolean isValidatePeerCerts()
    {
        return _validatePeerCerts;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param validatePeerCerts
     *            true if SSL certificates of the peer have to be validated
     */
    public void setValidatePeerCerts(boolean validatePeerCerts)
    {
        checkNotStarted();

        _validatePeerCerts = validatePeerCerts;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL re-negotiation is allowed (default false)
     */
    public boolean isAllowRenegotiate()
    {
        return _allowRenegotiate;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set if SSL re-negotiation is allowed. CVE-2009-3555 discovered
     * a vulnerability in SSL/TLS with re-negotiation.  If your JVM
     * does not have CVE-2009-3555 fixed, then re-negotiation should
     * not be allowed.  CVE-2009-3555 was fixed in Sun java 1.6 with a ban
     * of renegotiates in u19 and with RFC5746 in u22.
     *
     * @param allowRenegotiate
     *            true if re-negotiation is allowed (default false)
     */
    public void setAllowRenegotiate(boolean allowRenegotiate)
    {
        checkNotStarted();

        _allowRenegotiate = allowRenegotiate;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param password
     *            The password for the key store
     */
    public void setKeyStorePassword(String password)
    {
        checkNotStarted();

        _keyStorePassword = Password.getPassword(PASSWORD_PROPERTY,password,null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param password
     *            The password (if any) for the specific key within the key store
     */
    public void setKeyManagerPassword(String password)
    {
        checkNotStarted();

        _keyManagerPassword = Password.getPassword(KEYPASSWORD_PROPERTY,password,null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param password
     *            The password for the trust store
     */
    public void setTrustStorePassword(String password)
    {
        checkNotStarted();

        _trustStorePassword = Password.getPassword(PASSWORD_PROPERTY,password,null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The SSL provider name, which if set is passed to
     * {@link SSLContext#getInstance(String, String)}
     */
    public String getProvider()
    {
        return _sslProvider;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param provider
     *            The SSL provider name, which if set is passed to
     *            {@link SSLContext#getInstance(String, String)}
     */
    public void setProvider(String provider)
    {
        checkNotStarted();

        _sslProvider = provider;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The SSL protocol (default "TLS") passed to
     * {@link SSLContext#getInstance(String, String)}
     */
    public String getProtocol()
    {
        return _sslProtocol;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param protocol
     *            The SSL protocol (default "TLS") passed to
     *            {@link SSLContext#getInstance(String, String)}
     */
    public void setProtocol(String protocol)
    {
        checkNotStarted();

        _sslProtocol = protocol;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The algorithm name, which if set is passed to
     * {@link SecureRandom#getInstance(String)} to obtain the {@link SecureRandom} instance passed to
     * {@link SSLContext#init(javax.net.ssl.KeyManager[], javax.net.ssl.TrustManager[], SecureRandom)}
     */
    public String getSecureRandomAlgorithm()
    {
        return _secureRandomAlgorithm;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param algorithm
     *            The algorithm name, which if set is passed to
     *            {@link SecureRandom#getInstance(String)} to obtain the {@link SecureRandom} instance passed to
     *            {@link SSLContext#init(javax.net.ssl.KeyManager[], javax.net.ssl.TrustManager[], SecureRandom)}
     */
    public void setSecureRandomAlgorithm(String algorithm)
    {
        checkNotStarted();

        _secureRandomAlgorithm = algorithm;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The algorithm name (default "SunX509") used by the {@link KeyManagerFactory}
     */
    public String getSslKeyManagerFactoryAlgorithm()
    {
        return (_keyManagerFactoryAlgorithm);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param algorithm
     *            The algorithm name (default "SunX509") used by the {@link KeyManagerFactory}
     */
    public void setSslKeyManagerFactoryAlgorithm(String algorithm)
    {
        checkNotStarted();

        _keyManagerFactoryAlgorithm = algorithm;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The algorithm name (default "SunX509") used by the {@link TrustManagerFactory}
     */
    public String getTrustManagerFactoryAlgorithm()
    {
        return (_trustManagerFactoryAlgorithm);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if all certificates should be trusted if there is no KeyStore or TrustStore
     */
    public boolean isTrustAll()
    {
        return _trustAll;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param trustAll True if all certificates should be trusted if there is no KeyStore or TrustStore
     */
    public void setTrustAll(boolean trustAll)
    {
        _trustAll = trustAll;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param algorithm
     *            The algorithm name (default "SunX509") used by the {@link TrustManagerFactory}
     *            Use the string "TrustAll" to install a trust manager that trusts all.
     */
    public void setTrustManagerFactoryAlgorithm(String algorithm)
    {
        checkNotStarted();

        _trustManagerFactoryAlgorithm = algorithm;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Path to file that contains Certificate Revocation List
     */
    public String getCrlPath()
    {
        return _crlPath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param crlPath
     *            Path to file that contains Certificate Revocation List
     */
    public void setCrlPath(String crlPath)
    {
        checkNotStarted();

        _crlPath = crlPath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Maximum number of intermediate certificates in
     * the certification path (-1 for unlimited)
     */
    public int getMaxCertPathLength()
    {
        return _maxCertPathLength;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param maxCertPathLength
     *            maximum number of intermediate certificates in
     *            the certification path (-1 for unlimited)
     */
    public void setMaxCertPathLength(int maxCertPathLength)
    {
        checkNotStarted();

        _maxCertPathLength = maxCertPathLength;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The SSLContext
     */
    public SSLContext getSslContext()
    {
        if (!isStarted())
            throw new IllegalStateException(getState());
        return _context;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param sslContext
     *            Set a preconfigured SSLContext
     */
    public void setSslContext(SSLContext sslContext)
    {
        checkNotStarted();

        _context = sslContext;
    }

    /* ------------------------------------------------------------ */
    /**
     * Override this method to provide alternate way to load a keystore.
     *
     * @return the key store instance
     * @throws Exception if the keystore cannot be loaded
     */
    protected KeyStore loadKeyStore() throws Exception
    {
        return _keyStore != null ? _keyStore : getKeyStore(_keyStoreInputStream,
                _keyStorePath, _keyStoreType, _keyStoreProvider,
                _keyStorePassword==null? null: _keyStorePassword.toString());
    }

    /* ------------------------------------------------------------ */
    /**
     * Override this method to provide alternate way to load a truststore.
     *
     * @return the key store instance
     * @throws Exception if the truststore cannot be loaded
     */
    protected KeyStore loadTrustStore() throws Exception
    {
        return _trustStore != null ? _trustStore : getKeyStore(_trustStoreInputStream,
                _trustStorePath, _trustStoreType,  _trustStoreProvider,
                _trustStorePassword==null? null: _trustStorePassword.toString());
    }

    /* ------------------------------------------------------------ */
    /**
     * Loads keystore using an input stream or a file path in the same
     * order of precedence.
     *
     * Required for integrations to be able to override the mechanism
     * used to load a keystore in order to provide their own implementation.
     *
     * @param storeStream keystore input stream
     * @param storePath path of keystore file
     * @param storeType keystore type
     * @param storeProvider keystore provider
     * @param storePassword keystore password
     * @return created keystore
     * @throws Exception if the keystore cannot be obtained
     *
     * @deprecated
     */
    @Deprecated
    protected KeyStore getKeyStore(InputStream storeStream, String storePath, String storeType, String storeProvider, String storePassword) throws Exception
    {
        return CertificateUtils.getKeyStore(storeStream, storePath, storeType, storeProvider, storePassword);
    }

    /* ------------------------------------------------------------ */
    /**
     * Loads certificate revocation list (CRL) from a file.
     *
     * Required for integrations to be able to override the mechanism used to
     * load CRL in order to provide their own implementation.
     *
     * @param crlPath path of certificate revocation list file
     * @return Collection of CRL's
     * @throws Exception if the certificate revocation list cannot be loaded
     */
    protected Collection<? extends CRL> loadCRL(String crlPath) throws Exception
    {
        return CertificateUtils.loadCRL(crlPath);
    }

    /* ------------------------------------------------------------ */
    protected KeyManager[] getKeyManagers(KeyStore keyStore) throws Exception
    {
        KeyManager[] managers = null;

        if (keyStore != null)
        {
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(_keyManagerFactoryAlgorithm);
            keyManagerFactory.init(keyStore,_keyManagerPassword == null?(_keyStorePassword == null?null:_keyStorePassword.toString().toCharArray()):_keyManagerPassword.toString().toCharArray());
            managers = keyManagerFactory.getKeyManagers();

            if (_certAlias != null)
            {
                for (int idx = 0; idx < managers.length; idx++)
                {
                    if (managers[idx] instanceof X509KeyManager)
                    {
                        managers[idx] = new AliasedX509ExtendedKeyManager(_certAlias,(X509KeyManager)managers[idx]);
                    }
                }
            }
        }

        return managers;
    }

    /* ------------------------------------------------------------ */
    protected TrustManager[] getTrustManagers(KeyStore trustStore, Collection<? extends CRL> crls) throws Exception
    {
        TrustManager[] managers = null;
        if (trustStore != null)
        {
            // Revocation checking is only supported for PKIX algorithm
            if (_validatePeerCerts && _trustManagerFactoryAlgorithm.equalsIgnoreCase("PKIX"))
            {
                PKIXBuilderParameters pbParams = new PKIXBuilderParameters(trustStore,new X509CertSelector());

                // Set maximum certification path length
                pbParams.setMaxPathLength(_maxCertPathLength);

                // Make sure revocation checking is enabled
                pbParams.setRevocationEnabled(true);

                if (crls != null && !crls.isEmpty())
                {
                    pbParams.addCertStore(CertStore.getInstance("Collection",new CollectionCertStoreParameters(crls)));
                }

                if (_enableCRLDP)
                {
                    // Enable Certificate Revocation List Distribution Points (CRLDP) support
                    System.setProperty("com.sun.security.enableCRLDP","true");
                }

                if (_enableOCSP)
                {
                    // Enable On-Line Certificate Status Protocol (OCSP) support
                    Security.setProperty("ocsp.enable","true");

                    if (_ocspResponderURL != null)
                    {
                        // Override location of OCSP Responder
                        Security.setProperty("ocsp.responderURL", _ocspResponderURL);
                    }
                }

                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(_trustManagerFactoryAlgorithm);
                trustManagerFactory.init(new CertPathTrustManagerParameters(pbParams));

                managers = trustManagerFactory.getTrustManagers();
            }
            else
            {
                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(_trustManagerFactoryAlgorithm);
                trustManagerFactory.init(trustStore);

                managers = trustManagerFactory.getTrustManagers();
            }
        }

        return managers;
    }

    /* ------------------------------------------------------------ */
    /**
     * Check KeyStore Configuration. Ensures that if keystore has been
     * configured but there's no truststore, that keystore is
     * used as truststore.
     * @throws IllegalStateException if SslContextFactory configuration can't be used.
     */
    public void checkKeyStore()
    {
        if (_context != null)
            return; //nothing to check if using preconfigured context


        if (_keyStore == null && _keyStoreInputStream == null && _keyStorePath == null)
            throw new IllegalStateException("SSL doesn't have a valid keystore");

        // if the keystore has been configured but there is no
        // truststore configured, use the keystore as the truststore
        if (_trustStore == null && _trustStoreInputStream == null && _trustStorePath == null)
        {
            _trustStore = _keyStore;
            _trustStorePath = _keyStorePath;
            _trustStoreInputStream = _keyStoreInputStream;
            _trustStoreType = _keyStoreType;
            _trustStoreProvider = _keyStoreProvider;
            _trustStorePassword = _keyStorePassword;
            _trustManagerFactoryAlgorithm = _keyManagerFactoryAlgorithm;
        }

        // It's the same stream we cannot read it twice, so read it once in memory
        if (_keyStoreInputStream != null && _keyStoreInputStream == _trustStoreInputStream)
        {
            try
            {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                IO.copy(_keyStoreInputStream, baos);
                _keyStoreInputStream.close();

                _keyStoreInputStream = new ByteArrayInputStream(baos.toByteArray());
                _trustStoreInputStream = new ByteArrayInputStream(baos.toByteArray());
            }
            catch (Exception ex)
            {
                throw new IllegalStateException(ex);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Select protocols to be used by the connector
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported protocols.
     * @param enabledProtocols Array of enabled protocols
     * @param supportedProtocols Array of supported protocols
     * @return Array of protocols to enable
     */
    public String[] selectProtocols(String[] enabledProtocols, String[] supportedProtocols)
    {
        Set<String> selected_protocols = new LinkedHashSet<String>();

        // Set the starting protocols - either from the included or enabled list
        if (_includeProtocols!=null)
        {
            // Use only the supported included protocols
            for (String protocol : _includeProtocols)
                if(Arrays.asList(supportedProtocols).contains(protocol))
                    selected_protocols.add(protocol);
        }
        else
            selected_protocols.addAll(Arrays.asList(enabledProtocols));


        // Remove any excluded protocols
        if (_excludeProtocols != null)
            selected_protocols.removeAll(_excludeProtocols);

        return selected_protocols.toArray(new String[selected_protocols.size()]);
    }

    /* ------------------------------------------------------------ */
    /**
     * Select cipher suites to be used by the connector
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported cipher suite lists.
     * @param enabledCipherSuites Array of enabled cipher suites
     * @param supportedCipherSuites Array of supported cipher suites
     * @return Array of cipher suites to enable
     */
    public String[] selectCipherSuites(String[] enabledCipherSuites, String[] supportedCipherSuites)
    {
        Set<String> selected_ciphers = new LinkedHashSet<String>();

        // Set the starting ciphers - either from the included or enabled list
        if (_includeCipherSuites!=null)
        {
            // Use only the supported included ciphers
            for (String cipherSuite : _includeCipherSuites)
                if(Arrays.asList(supportedCipherSuites).contains(cipherSuite))
                    selected_ciphers.add(cipherSuite);
        }
        else
            selected_ciphers.addAll(Arrays.asList(enabledCipherSuites));


        // Remove any excluded ciphers
        if (_excludeCipherSuites != null)
            selected_ciphers.removeAll(_excludeCipherSuites);
        return selected_ciphers.toArray(new String[selected_ciphers.size()]);
    }

    /* ------------------------------------------------------------ */
    /**
     * Check if the lifecycle has been started and throw runtime exception
     */
    protected void checkNotStarted()
    {
        if (isStarted())
            throw new IllegalStateException("Cannot modify configuration when "+getState());
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if CRL Distribution Points support is enabled
     */
    public boolean isEnableCRLDP()
    {
        return _enableCRLDP;
    }

    /* ------------------------------------------------------------ */
    /** Enables CRL Distribution Points Support
     * @param enableCRLDP true - turn on, false - turns off
     */
    public void setEnableCRLDP(boolean enableCRLDP)
    {
        checkNotStarted();

        _enableCRLDP = enableCRLDP;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if On-Line Certificate Status Protocol support is enabled
     */
    public boolean isEnableOCSP()
    {
        return _enableOCSP;
    }

    /* ------------------------------------------------------------ */
    /** Enables On-Line Certificate Status Protocol support
     * @param enableOCSP true - turn on, false - turn off
     */
    public void setEnableOCSP(boolean enableOCSP)
    {
        checkNotStarted();

        _enableOCSP = enableOCSP;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Location of the OCSP Responder
     */
    public String getOcspResponderURL()
    {
        return _ocspResponderURL;
    }

    /* ------------------------------------------------------------ */
    /** Set the location of the OCSP Responder.
     * @param ocspResponderURL location of the OCSP Responder
     */
    public void setOcspResponderURL(String ocspResponderURL)
    {
        checkNotStarted();

        _ocspResponderURL = ocspResponderURL;
    }

    /* ------------------------------------------------------------ */
    /** Set the key store.
     * @param keyStore the key store to set
     */
    public void setKeyStore(KeyStore keyStore)
    {
        checkNotStarted();

        _keyStore = keyStore;
    }

    /* ------------------------------------------------------------ */
    /** Set the trust store.
     * @param trustStore the trust store to set
     */
    public void setTrustStore(KeyStore trustStore)
    {
        checkNotStarted();

        _trustStore = trustStore;
    }

    /* ------------------------------------------------------------ */
    /** Set the key store resource.
     * @param resource the key store resource to set
     */
    public void setKeyStoreResource(Resource resource)
    {
        checkNotStarted();

        try
        {
            _keyStoreInputStream = resource.getInputStream();
        }
        catch (IOException e)
        {
             throw new InvalidParameterException("Unable to get resource "+
                     "input stream for resource "+resource.toString());
        }
    }

    /* ------------------------------------------------------------ */
    /** Set the trust store resource.
     * @param resource the trust store resource to set
     */
    public void setTrustStoreResource(Resource resource)
    {
        checkNotStarted();

        try
        {
            _trustStoreInputStream = resource.getInputStream();
        }
        catch (IOException e)
        {
             throw new InvalidParameterException("Unable to get resource "+
                     "input stream for resource "+resource.toString());
        }
    }

    /* ------------------------------------------------------------ */
    /**
    * @return true if SSL Session caching is enabled
    */
    public boolean isSessionCachingEnabled()
    {
        return _sessionCachingEnabled;
    }

    /* ------------------------------------------------------------ */
    /** Set the flag to enable SSL Session caching.
    * @param enableSessionCaching the value of the flag
    */
    public void setSessionCachingEnabled(boolean enableSessionCaching)
    {
        _sessionCachingEnabled = enableSessionCaching;
    }

    /* ------------------------------------------------------------ */
    /** Get SSL session cache size.
     * @return SSL session cache size
     */
    public int getSslSessionCacheSize()
    {
        return _sslSessionCacheSize;
    }

    /* ------------------------------------------------------------ */
    /** SEt SSL session cache size.
     * @param sslSessionCacheSize SSL session cache size to set
     */
    public void setSslSessionCacheSize(int sslSessionCacheSize)
    {
        _sslSessionCacheSize = sslSessionCacheSize;
    }

    /* ------------------------------------------------------------ */
    /** Get SSL session timeout.
     * @return SSL session timeout
     */
    public int getSslSessionTimeout()
    {
        return _sslSessionTimeout;
    }

    /* ------------------------------------------------------------ */
    /** Set SSL session timeout.
     * @param sslSessionTimeout SSL session timeout to set
     */
    public void setSslSessionTimeout(int sslSessionTimeout)
    {
        _sslSessionTimeout = sslSessionTimeout;
    }


    /* ------------------------------------------------------------ */
    public SSLServerSocket newSslServerSocket(String host,int port,int backlog) throws IOException
    {
        SSLServerSocketFactory factory = _context.getServerSocketFactory();

        SSLServerSocket socket =
            (SSLServerSocket) (host==null ?
                        factory.createServerSocket(port,backlog):
                        factory.createServerSocket(port,backlog,InetAddress.getByName(host)));

        if (getWantClientAuth())
            socket.setWantClientAuth(getWantClientAuth());
        if (getNeedClientAuth())
            socket.setNeedClientAuth(getNeedClientAuth());

        socket.setEnabledCipherSuites(selectCipherSuites(
                                            socket.getEnabledCipherSuites(),
                                            socket.getSupportedCipherSuites()));
        socket.setEnabledProtocols(selectProtocols(socket.getEnabledProtocols(),socket.getSupportedProtocols()));

        return socket;
    }

    /* ------------------------------------------------------------ */
    public SSLSocket newSslSocket() throws IOException
    {
        SSLSocketFactory factory = _context.getSocketFactory();

        SSLSocket socket = (SSLSocket)factory.createSocket();

        if (getWantClientAuth())
            socket.setWantClientAuth(getWantClientAuth());
        if (getNeedClientAuth())
            socket.setNeedClientAuth(getNeedClientAuth());

        socket.setEnabledCipherSuites(selectCipherSuites(
                                            socket.getEnabledCipherSuites(),
                                            socket.getSupportedCipherSuites()));
        socket.setEnabledProtocols(selectProtocols(socket.getEnabledProtocols(),socket.getSupportedProtocols()));

        return socket;
    }

    /* ------------------------------------------------------------ */
    public SSLEngine newSslEngine(String host,int port)
    {
        SSLEngine sslEngine=isSessionCachingEnabled()
            ?_context.createSSLEngine(host, port)
            :_context.createSSLEngine();

        customize(sslEngine);
        return sslEngine;
    }

    /* ------------------------------------------------------------ */
    public SSLEngine newSslEngine()
    {
        SSLEngine sslEngine=_context.createSSLEngine();
        customize(sslEngine);
        return sslEngine;
    }

    /* ------------------------------------------------------------ */
    public void customize(SSLEngine sslEngine)
    {
        if (getWantClientAuth())
            sslEngine.setWantClientAuth(getWantClientAuth());
        if (getNeedClientAuth())
            sslEngine.setNeedClientAuth(getNeedClientAuth());

        sslEngine.setEnabledCipherSuites(selectCipherSuites(
                sslEngine.getEnabledCipherSuites(),
                sslEngine.getSupportedCipherSuites()));

        sslEngine.setEnabledProtocols(selectProtocols(sslEngine.getEnabledProtocols(),sslEngine.getSupportedProtocols()));
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return String.format("%s@%x(%s,%s)",
                getClass().getSimpleName(),
                hashCode(),
                _keyStorePath,
                _trustStorePath);
    }
}
