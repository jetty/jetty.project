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
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
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

    static final Logger LOG = Log.getLogger(SslContextFactory.class);

    public static final String DEFAULT_KEYMANAGERFACTORY_ALGORITHM =
        (Security.getProperty("ssl.KeyManagerFactory.algorithm") == null ?
                KeyManagerFactory.getDefaultAlgorithm() : Security.getProperty("ssl.KeyManagerFactory.algorithm"));

    public static final String DEFAULT_TRUSTMANAGERFACTORY_ALGORITHM =
        (Security.getProperty("ssl.TrustManagerFactory.algorithm") == null ?
                TrustManagerFactory.getDefaultAlgorithm() : Security.getProperty("ssl.TrustManagerFactory.algorithm"));

    /** String name of key password property. */
    public static final String KEYPASSWORD_PROPERTY = "org.eclipse.jetty.ssl.keypassword";

    /** String name of keystore password property. */
    public static final String PASSWORD_PROPERTY = "org.eclipse.jetty.ssl.password";

    /** Excluded protocols. */
    private final Set<String> _excludeProtocols = new LinkedHashSet<>();

    /** Included protocols. */
    private Set<String> _includeProtocols = null;

    /** Excluded cipher suites. */
    private final Set<String> _excludeCipherSuites = new LinkedHashSet<>();
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

    /** EndpointIdentificationAlgorithm - when set to "HTTPS" hostname verification will be enabled */
    private String _endpointIdentificationAlgorithm = null;

    /** Whether to blindly trust certificates */
    private boolean _trustAll;

    /** Whether TLS renegotiation is allowed */
    private boolean _renegotiationAllowed = true;

    /**
     * Construct an instance of SslContextFactory
     * Default constructor for use in XmlConfiguration files
     */
    public SslContextFactory()
    {
        this(false);
    }

    /**
     * Construct an instance of SslContextFactory
     * Default constructor for use in XmlConfiguration files
     * @param trustAll whether to blindly trust all certificates
     * @see #setTrustAll(boolean)
     */
    public SslContextFactory(boolean trustAll)
    {
        setTrustAll(trustAll);
    }

    /**
     * Construct an instance of SslContextFactory
     * @param keyStorePath default keystore location
     */
    public SslContextFactory(String keyStorePath)
    {
        _keyStorePath = keyStorePath;
    }

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
                SSLContext context = SSLContext.getInstance(_sslProtocol);
                context.init(null, trust_managers, secureRandom);
                _context = context;
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
                SSLContext context = _sslProvider == null ? SSLContext.getInstance(_sslProtocol) : SSLContext.getInstance(_sslProtocol,_sslProvider);
                context.init(keyManagers,trustManagers,secureRandom);
                _context = context;
            }

            SSLEngine engine = newSSLEngine();
            LOG.debug("Enabled Protocols {} of {}",Arrays.asList(engine.getEnabledProtocols()),Arrays.asList(engine.getSupportedProtocols()));
            if (LOG.isDebugEnabled())
                LOG.debug("Enabled Ciphers   {} of {}",Arrays.asList(engine.getEnabledCipherSuites()),Arrays.asList(engine.getSupportedCipherSuites()));
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        _context = null;
        super.doStop();
    }

    /**
     * @return The array of protocol names to exclude from
     * {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public String[] getExcludeProtocols()
    {
        return _excludeProtocols.toArray(new String[_excludeProtocols.size()]);
    }

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

    /**
     * @param protocol Protocol names to add to {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public void addExcludeProtocols(String... protocol)
    {
        checkNotStarted();
        _excludeProtocols.addAll(Arrays.asList(protocol));
    }

    /**
     * @return The array of protocol names to include in
     * {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public String[] getIncludeProtocols()
    {
        return _includeProtocols.toArray(new String[_includeProtocols.size()]);
    }

    /**
     * @param protocols
     *            The array of protocol names to include in
     *            {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public void setIncludeProtocols(String... protocols)
    {
        checkNotStarted();
        _includeProtocols = new LinkedHashSet<>(Arrays.asList(protocols));
    }

    /**
     * @return The array of cipher suite names to exclude from
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public String[] getExcludeCipherSuites()
    {
        return _excludeCipherSuites.toArray(new String[_excludeCipherSuites.size()]);
    }

    /**
     * You can either use the exact cipher suite name or a a regular expression.
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

    /**
     * @param cipher Cipher names to add to {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public void addExcludeCipherSuites(String... cipher)
    {
        checkNotStarted();
        _excludeCipherSuites.addAll(Arrays.asList(cipher));
    }

    /**
     * @return The array of cipher suite names to include in
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public String[] getIncludeCipherSuites()
    {
        return _includeCipherSuites.toArray(new String[_includeCipherSuites.size()]);
    }

    /**
     * You can either use the exact cipher suite name or a a regular expression.
     * @param cipherSuites
     *            The array of cipher suite names to include in
     *            {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public void setIncludeCipherSuites(String... cipherSuites)
    {
        checkNotStarted();
        _includeCipherSuites = new LinkedHashSet<>(Arrays.asList(cipherSuites));
    }

    /**
     * @return The file or URL of the SSL Key store.
     */
    public String getKeyStorePath()
    {
        return _keyStorePath;
    }

    /**
     * @param keyStorePath
     *            The file or URL of the SSL Key store.
     */
    public void setKeyStorePath(String keyStorePath)
    {
        checkNotStarted();
        _keyStorePath = keyStorePath;
    }

    /**
     * @return The provider of the key store
     */
    public String getKeyStoreProvider()
    {
        return _keyStoreProvider;
    }

    /**
     * @param keyStoreProvider
     *            The provider of the key store
     */
    public void setKeyStoreProvider(String keyStoreProvider)
    {
        checkNotStarted();
        _keyStoreProvider = keyStoreProvider;
    }

    /**
     * @return The type of the key store (default "JKS")
     */
    public String getKeyStoreType()
    {
        return (_keyStoreType);
    }

    /**
     * @param keyStoreType
     *            The type of the key store (default "JKS")
     */
    public void setKeyStoreType(String keyStoreType)
    {
        checkNotStarted();
        _keyStoreType = keyStoreType;
    }

    /**
     * @return Alias of SSL certificate for the connector
     */
    public String getCertAlias()
    {
        return _certAlias;
    }

    /**
     * @param certAlias
     *            Alias of SSL certificate for the connector
     */
    public void setCertAlias(String certAlias)
    {
        checkNotStarted();
        _certAlias = certAlias;
    }

    /**
     * @return The file name or URL of the trust store location
     */
    public String getTrustStore()
    {
        return _trustStorePath;
    }

    /**
     * @param trustStorePath
     *            The file name or URL of the trust store location
     */
    public void setTrustStorePath(String trustStorePath)
    {
        checkNotStarted();
        _trustStorePath = trustStorePath;
    }

    /**
     * @return The provider of the trust store
     */
    public String getTrustStoreProvider()
    {
        return _trustStoreProvider;
    }

    /**
     * @param trustStoreProvider
     *            The provider of the trust store
     */
    public void setTrustStoreProvider(String trustStoreProvider)
    {
        checkNotStarted();
        _trustStoreProvider = trustStoreProvider;
    }

    /**
     * @return The type of the trust store (default "JKS")
     */
    public String getTrustStoreType()
    {
        return _trustStoreType;
    }

    /**
     * @param trustStoreType
     *            The type of the trust store (default "JKS")
     */
    public void setTrustStoreType(String trustStoreType)
    {
        checkNotStarted();
        _trustStoreType = trustStoreType;
    }

    /**
     * @return True if SSL needs client authentication.
     * @see SSLEngine#getNeedClientAuth()
     */
    public boolean getNeedClientAuth()
    {
        return _needClientAuth;
    }

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

    /**
     * @return True if SSL wants client authentication.
     * @see SSLEngine#getWantClientAuth()
     */
    public boolean getWantClientAuth()
    {
        return _wantClientAuth;
    }

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

    /**
     * @return true if SSL certificate has to be validated
     */
    public boolean isValidateCerts()
    {
        return _validateCerts;
    }

    /**
     * @param validateCerts
     *            true if SSL certificates have to be validated
     */
    public void setValidateCerts(boolean validateCerts)
    {
        checkNotStarted();
        _validateCerts = validateCerts;
    }

    /**
     * @return true if SSL certificates of the peer have to be validated
     */
    public boolean isValidatePeerCerts()
    {
        return _validatePeerCerts;
    }

    /**
     * @param validatePeerCerts
     *            true if SSL certificates of the peer have to be validated
     */
    public void setValidatePeerCerts(boolean validatePeerCerts)
    {
        checkNotStarted();
        _validatePeerCerts = validatePeerCerts;
    }


    /**
     * @param password
     *            The password for the key store
     */
    public void setKeyStorePassword(String password)
    {
        checkNotStarted();
        _keyStorePassword = Password.getPassword(PASSWORD_PROPERTY,password,null);
    }

    /**
     * @param password
     *            The password (if any) for the specific key within the key store
     */
    public void setKeyManagerPassword(String password)
    {
        checkNotStarted();
        _keyManagerPassword = Password.getPassword(KEYPASSWORD_PROPERTY,password,null);
    }

    /**
     * @param password
     *            The password for the trust store
     */
    public void setTrustStorePassword(String password)
    {
        checkNotStarted();
        _trustStorePassword = Password.getPassword(PASSWORD_PROPERTY,password,null);
    }

    /**
     * @return The SSL provider name, which if set is passed to
     * {@link SSLContext#getInstance(String, String)}
     */
    public String getProvider()
    {
        return _sslProvider;
    }

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

    /**
     * @return The SSL protocol (default "TLS") passed to
     * {@link SSLContext#getInstance(String, String)}
     */
    public String getProtocol()
    {
        return _sslProtocol;
    }

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

    /**
     * @return The algorithm name, which if set is passed to
     * {@link SecureRandom#getInstance(String)} to obtain the {@link SecureRandom} instance passed to
     * {@link SSLContext#init(javax.net.ssl.KeyManager[], javax.net.ssl.TrustManager[], SecureRandom)}
     */
    public String getSecureRandomAlgorithm()
    {
        return _secureRandomAlgorithm;
    }

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

    /**
     * @return The algorithm name (default "SunX509") used by the {@link KeyManagerFactory}
     */
    public String getSslKeyManagerFactoryAlgorithm()
    {
        return (_keyManagerFactoryAlgorithm);
    }

    /**
     * @param algorithm
     *            The algorithm name (default "SunX509") used by the {@link KeyManagerFactory}
     */
    public void setSslKeyManagerFactoryAlgorithm(String algorithm)
    {
        checkNotStarted();
        _keyManagerFactoryAlgorithm = algorithm;
    }

    /**
     * @return The algorithm name (default "SunX509") used by the {@link TrustManagerFactory}
     */
    public String getTrustManagerFactoryAlgorithm()
    {
        return (_trustManagerFactoryAlgorithm);
    }

    /**
     * @return True if all certificates should be trusted if there is no KeyStore or TrustStore
     */
    public boolean isTrustAll()
    {
        return _trustAll;
    }

    /**
     * @param trustAll True if all certificates should be trusted if there is no KeyStore or TrustStore
     */
    public void setTrustAll(boolean trustAll)
    {
        _trustAll = trustAll;
        if(trustAll)
            setEndpointIdentificationAlgorithm(null);
    }

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

    /**
     * @return whether TLS renegotiation is allowed (true by default)
     */
    public boolean isRenegotiationAllowed()
    {
        return _renegotiationAllowed;
    }

    /**
     * @param renegotiationAllowed whether TLS renegotiation is allowed
     */
    public void setRenegotiationAllowed(boolean renegotiationAllowed)
    {
        _renegotiationAllowed = renegotiationAllowed;
    }

    /**
     * @return Path to file that contains Certificate Revocation List
     */
    public String getCrlPath()
    {
        return _crlPath;
    }

    /**
     * @param crlPath
     *            Path to file that contains Certificate Revocation List
     */
    public void setCrlPath(String crlPath)
    {
        checkNotStarted();
        _crlPath = crlPath;
    }

    /**
     * @return Maximum number of intermediate certificates in
     * the certification path (-1 for unlimited)
     */
    public int getMaxCertPathLength()
    {
        return _maxCertPathLength;
    }

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

    /**
     * @return The SSLContext
     */
    public SSLContext getSslContext()
    {
        if (!isStarted())
            throw new IllegalStateException(getState());
        return _context;
    }

    /**
     * @param sslContext
     *            Set a preconfigured SSLContext
     */
    public void setSslContext(SSLContext sslContext)
    {
        checkNotStarted();
        _context = sslContext;
    }

    /**
     * When set to "HTTPS" hostname verification will be enabled
     *
     * @param endpointIdentificationAlgorithm Set the endpointIdentificationAlgorithm
     */
    public void setEndpointIdentificationAlgorithm(String endpointIdentificationAlgorithm)
    {
        this._endpointIdentificationAlgorithm = endpointIdentificationAlgorithm;
    }

    /**
     * Override this method to provide alternate way to load a keystore.
     *
     * @return the key store instance
     * @throws Exception if the keystore cannot be loaded
     */
    protected KeyStore loadKeyStore() throws Exception
    {
        return _keyStore != null ? _keyStore : CertificateUtils.getKeyStore(_keyStoreInputStream,
                _keyStorePath, _keyStoreType, _keyStoreProvider,
                _keyStorePassword==null? null: _keyStorePassword.toString());
    }

    /**
     * Override this method to provide alternate way to load a truststore.
     *
     * @return the key store instance
     * @throws Exception if the truststore cannot be loaded
     */
    protected KeyStore loadTrustStore() throws Exception
    {
        return _trustStore != null ? _trustStore : CertificateUtils.getKeyStore(_trustStoreInputStream,
                _trustStorePath, _trustStoreType,  _trustStoreProvider,
                _trustStorePassword==null? null: _trustStorePassword.toString());
    }

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

    /**
     * Check KeyStore Configuration. Ensures that if keystore has been
     * configured but there's no truststore, that keystore is
     * used as truststore.
     * @throws IllegalStateException if SslContextFactory configuration can't be used.
     */
    public void checkKeyStore()
    {
        if (_context != null)
            return;

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
        Set<String> selected_protocols = new LinkedHashSet<>();

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
        selected_protocols.removeAll(_excludeProtocols);

        return selected_protocols.toArray(new String[selected_protocols.size()]);
    }

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
        Set<String> selected_ciphers = new CopyOnWriteArraySet<>();

        // Set the starting ciphers - either from the included or enabled list
        if (_includeCipherSuites!=null)
            processIncludeCipherSuites(supportedCipherSuites, selected_ciphers);
        else
            selected_ciphers.addAll(Arrays.asList(enabledCipherSuites));

        removeExcludedCipherSuites(selected_ciphers);

        return selected_ciphers.toArray(new String[selected_ciphers.size()]);
    }

    private void processIncludeCipherSuites(String[] supportedCipherSuites, Set<String> selected_ciphers)
    {
        for (String cipherSuite : _includeCipherSuites)
        {
            Pattern p = Pattern.compile(cipherSuite);
            for (String supportedCipherSuite : supportedCipherSuites)
            {
                Matcher m = p.matcher(supportedCipherSuite);
                if (m.matches())
                    selected_ciphers.add(supportedCipherSuite);
            }
        }
    }

    private void removeExcludedCipherSuites(Set<String> selected_ciphers)
    {
        for (String excludeCipherSuite : _excludeCipherSuites)
        {
            Pattern excludeCipherPattern = Pattern.compile(excludeCipherSuite);
            for (String selectedCipherSuite : selected_ciphers)
            {
                Matcher m = excludeCipherPattern.matcher(selectedCipherSuite);
                if (m.matches())
                    selected_ciphers.remove(selectedCipherSuite);
            }
        }
    }

    /**
     * Check if the lifecycle has been started and throw runtime exception
     */
    protected void checkNotStarted()
    {
        if (isStarted())
            throw new IllegalStateException("Cannot modify configuration when "+getState());
    }

    /**
     * @return true if CRL Distribution Points support is enabled
     */
    public boolean isEnableCRLDP()
    {
        return _enableCRLDP;
    }

    /** Enables CRL Distribution Points Support
     * @param enableCRLDP true - turn on, false - turns off
     */
    public void setEnableCRLDP(boolean enableCRLDP)
    {
        checkNotStarted();
        _enableCRLDP = enableCRLDP;
    }

    /**
     * @return true if On-Line Certificate Status Protocol support is enabled
     */
    public boolean isEnableOCSP()
    {
        return _enableOCSP;
    }

    /** Enables On-Line Certificate Status Protocol support
     * @param enableOCSP true - turn on, false - turn off
     */
    public void setEnableOCSP(boolean enableOCSP)
    {
        checkNotStarted();
        _enableOCSP = enableOCSP;
    }

    /**
     * @return Location of the OCSP Responder
     */
    public String getOcspResponderURL()
    {
        return _ocspResponderURL;
    }

    /** Set the location of the OCSP Responder.
     * @param ocspResponderURL location of the OCSP Responder
     */
    public void setOcspResponderURL(String ocspResponderURL)
    {
        checkNotStarted();
        _ocspResponderURL = ocspResponderURL;
    }

    /** Set the key store.
     * @param keyStore the key store to set
     */
    public void setKeyStore(KeyStore keyStore)
    {
        checkNotStarted();
        _keyStore = keyStore;
    }

    /** Set the trust store.
     * @param trustStore the trust store to set
     */
    public void setTrustStore(KeyStore trustStore)
    {
        checkNotStarted();
        _trustStore = trustStore;
    }

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

    /**
    * @return true if SSL Session caching is enabled
    */
    public boolean isSessionCachingEnabled()
    {
        return _sessionCachingEnabled;
    }

    /** Set the flag to enable SSL Session caching.
    * @param enableSessionCaching the value of the flag
    */
    public void setSessionCachingEnabled(boolean enableSessionCaching)
    {
        _sessionCachingEnabled = enableSessionCaching;
    }

    /** Get SSL session cache size.
     * @return SSL session cache size
     */
    public int getSslSessionCacheSize()
    {
        return _sslSessionCacheSize;
    }

    /** SEt SSL session cache size.
     * @param sslSessionCacheSize SSL session cache size to set
     */
    public void setSslSessionCacheSize(int sslSessionCacheSize)
    {
        _sslSessionCacheSize = sslSessionCacheSize;
    }

    /** Get SSL session timeout.
     * @return SSL session timeout
     */
    public int getSslSessionTimeout()
    {
        return _sslSessionTimeout;
    }

    /** Set SSL session timeout.
     * @param sslSessionTimeout SSL session timeout to set
     */
    public void setSslSessionTimeout(int sslSessionTimeout)
    {
        _sslSessionTimeout = sslSessionTimeout;
    }


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

    /**
     * Factory method for "scratch" {@link SSLEngine}s, usually only used for retrieving configuration
     * information such as the application buffer size or the list of protocols/ciphers.
     * <p />
     * This method should not be used for creating {@link SSLEngine}s that are used in actual socket
     * communication.
     *
     * @return a new, "scratch" {@link SSLEngine}
     */
    public SSLEngine newSSLEngine()
    {
        if (!isRunning())
            throw new IllegalStateException("!STARTED");
        SSLEngine sslEngine=_context.createSSLEngine();
        customize(sslEngine);
        return sslEngine;
    }

    /**
     * General purpose factory method for creating {@link SSLEngine}s, although creation of
     * {@link SSLEngine}s on the server-side should prefer {@link #newSSLEngine(InetSocketAddress)}.
     *
     * @param host the remote host
     * @param port the remote port
     * @return a new {@link SSLEngine}
     */
    public SSLEngine newSSLEngine(String host, int port)
    {
        if (!isRunning())
            throw new IllegalStateException("!STARTED");
        SSLEngine sslEngine=isSessionCachingEnabled()
            ? _context.createSSLEngine(host, port)
            : _context.createSSLEngine();
        customize(sslEngine);
        return sslEngine;
    }

    /**
     * Server-side only factory method for creating {@link SSLEngine}s.
     * <p />
     * If the given {@code address} is null, it is equivalent to {@link #newSSLEngine()}, otherwise
     * {@link #newSSLEngine(String, int)} is called.
     * <p />
     * If {@link #getNeedClientAuth()} is {@code true}, then the host name is passed to
     * {@link #newSSLEngine(String, int)}, possibly incurring in a reverse DNS lookup, which takes time
     * and may hang the selector (since this method is usually called by the selector thread).
     * <p />
     * Otherwise, the host address is passed to {@link #newSSLEngine(String, int)} without DNS lookup
     * penalties.
     * <p />
     * Clients that wish to create {@link SSLEngine} instances must use {@link #newSSLEngine(String, int)}.
     *
     * @param address the remote peer address
     * @return a new {@link SSLEngine}
     */
    public SSLEngine newSSLEngine(InetSocketAddress address)
    {
        if (address == null)
            return newSSLEngine();

        boolean useHostName = getNeedClientAuth();
        String hostName = useHostName ? address.getHostName() : address.getAddress().getHostAddress();
        return newSSLEngine(hostName, address.getPort());
    }

    public void customize(SSLEngine sslEngine)
    {
        SSLParameters sslParams = sslEngine.getSSLParameters();
        sslParams.setEndpointIdentificationAlgorithm(_endpointIdentificationAlgorithm);
        sslEngine.setSSLParameters(sslParams);

        if (getWantClientAuth())
            sslEngine.setWantClientAuth(getWantClientAuth());
        if (getNeedClientAuth())
            sslEngine.setNeedClientAuth(getNeedClientAuth());

        sslEngine.setEnabledCipherSuites(selectCipherSuites(
                sslEngine.getEnabledCipherSuites(),
                sslEngine.getSupportedCipherSuites()));

        sslEngine.setEnabledProtocols(selectProtocols(sslEngine.getEnabledProtocols(),sslEngine.getSupportedProtocols()));
    }

    public static X509Certificate[] getCertChain(SSLSession sslSession)
    {
        try
        {
            Certificate[] javaxCerts=sslSession.getPeerCertificates();
            if (javaxCerts==null||javaxCerts.length==0)
                return null;

            int length=javaxCerts.length;
            X509Certificate[] javaCerts=new X509Certificate[length];

            java.security.cert.CertificateFactory cf=java.security.cert.CertificateFactory.getInstance("X.509");
            for (int i=0; i<length; i++)
            {
                byte bytes[]=javaxCerts[i].getEncoded();
                ByteArrayInputStream stream=new ByteArrayInputStream(bytes);
                javaCerts[i]=(X509Certificate)cf.generateCertificate(stream);
            }

            return javaCerts;
        }
        catch (SSLPeerUnverifiedException pue)
        {
            return null;
        }
        catch (Exception e)
        {
            LOG.warn(Log.EXCEPTION,e);
            return null;
        }
    }

    /**
     * Given the name of a TLS/SSL cipher suite, return an int representing it effective stream
     * cipher key strength. i.e. How much entropy material is in the key material being fed into the
     * encryption routines.
     *
     * <p>
     * This is based on the information on effective key lengths in RFC 2246 - The TLS Protocol
     * Version 1.0, Appendix C. CipherSuite definitions:
     *
     * <pre>
     *                         Effective
     *     Cipher       Type    Key Bits
     *
     *     NULL       * Stream     0
     *     IDEA_CBC     Block    128
     *     RC2_CBC_40 * Block     40
     *     RC4_40     * Stream    40
     *     RC4_128      Stream   128
     *     DES40_CBC  * Block     40
     *     DES_CBC      Block     56
     *     3DES_EDE_CBC Block    168
     * </pre>
     *
     * @param cipherSuite String name of the TLS cipher suite.
     * @return int indicating the effective key entropy bit-length.
     */
    public static int deduceKeyLength(String cipherSuite)
    {
        // Roughly ordered from most common to least common.
        if (cipherSuite == null)
            return 0;
        else if (cipherSuite.contains("WITH_AES_256_"))
            return 256;
        else if (cipherSuite.contains("WITH_RC4_128_"))
            return 128;
        else if (cipherSuite.contains("WITH_AES_128_"))
            return 128;
        else if (cipherSuite.contains("WITH_RC4_40_"))
            return 40;
        else if (cipherSuite.contains("WITH_3DES_EDE_CBC_"))
            return 168;
        else if (cipherSuite.contains("WITH_IDEA_CBC_"))
            return 128;
        else if (cipherSuite.contains("WITH_RC2_CBC_40_"))
            return 40;
        else if (cipherSuite.contains("WITH_DES40_CBC_"))
            return 40;
        else if (cipherSuite.contains("WITH_DES_CBC_"))
            return 56;
        else
            return 0;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(%s,%s)",
                getClass().getSimpleName(),
                hashCode(),
                _keyStorePath,
                _trustStorePath);
    }
}
