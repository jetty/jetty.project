//========================================================================
//Copyright (c) Webtide LLC
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//
//The Apache License v2.0 is available at
//http://www.apache.org/licenses/LICENSE-2.0.txt
//
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.http.ssl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CRL;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jetty.http.security.Password;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.CertificateValidator;


/* ------------------------------------------------------------ */
/**
 * SslContextFactory is used to configure SSL connectors
 * as well as HttpClient. It holds all SSL parameters and
 * creates SSL context based on these parameters to be
 * used by the SSL connectors. 
 */
public class SslContextFactory extends AbstractLifeCycle
{
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

    /** Excluded cipher suites. */
    private Set<String> _excludeCipherSuites = null;
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
    /** Set to true if SSL certificate validation is required */
    private boolean _validateCerts;
    /** Set to true if renegotiation is allowed */
    private boolean _allowRenegotiate = false;

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

    /** Path to file that contains Certificate Revocation List */
    private String _crlPath;
    /** Maximum certification path length (n - number of intermediate certs, -1 for unlimited) */
    private int _maxCertPathLength = -1;

    /** SSL context */
    private SSLContext _context;

    /* ------------------------------------------------------------ */
    /**
     * Construct an instance of SslContextFactory
     * Default constructor for use in XmlConfiguration files
     */
    public SslContextFactory()
    {
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Construct an instance of SslContextFactory
     * @param keystorePath default keystore location
     */
    public SslContextFactory(String keystorePath)
    {
        _keyStorePath = keystorePath;
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
            if (_keyStoreInputStream == null && _keyStorePath == null &&
                    _trustStoreInputStream == null && _trustStorePath == null )
            {
                // Create a trust manager that does not validate certificate chains
                TrustManager trustAllCerts = new X509TrustManager()
                {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers()
                    {
                        return null;
                    }
    
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType)
                    {
                    }
    
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType)
                    {
                    }
                };
    
                _context = SSLContext.getInstance(_sslProtocol);
                _context.init(null, new TrustManager[]{trustAllCerts}, null);
            }
            else
            {
                createSSLContext();
            }
        }
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
    public void setExcludeCipherSuites(String[] cipherSuites)
    {
        checkStarted();
        
        _excludeCipherSuites = new HashSet<String>(Arrays.asList(cipherSuites));
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
    public void setIncludeCipherSuites(String[] cipherSuites)
    {
        checkStarted();
        
        _includeCipherSuites = new HashSet<String>(Arrays.asList(cipherSuites));
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The file or URL of the SSL Key store.
     */
    public String getKeyStore()
    {
        return _keyStorePath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param keystore
     *            The file or URL of the SSL Key store.
     */
    public void setKeyStore(String keystore)
    {
        checkStarted();
        
        _keyStorePath = keystore;
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
     * @param keystoreProvider
     *            The provider of the key store
     */
    public void setKeyStoreProvider(String keystoreProvider)
    {
        checkStarted();
        
        _keyStoreProvider = keystoreProvider;
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
     * @param keystoreType
     *            The type of the key store (default "JKS")
     */
    public void setKeyStoreType(String keystoreType)
    {
        checkStarted();
        
        _keyStoreType = keystoreType;
    }

    /* ------------------------------------------------------------ */
    /** Get the _keyStoreInputStream.
     * @return the _keyStoreInputStream
     */
    public InputStream getKeyStoreInputStream()
    {
        checkConfig();
        
        return _keyStoreInputStream;
    }

    /* ------------------------------------------------------------ */
    /** Set the keyStoreInputStream.
     * @param keystoreInputStream the InputStream to the KeyStore 
     */
    public void setKeyStoreInputStream(InputStream keystoreInputStream)
    {
        checkStarted();
        
        _keyStoreInputStream = keystoreInputStream;
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
        checkStarted();
        
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
     * @param truststore
     *            The file name or URL of the trust store location
     */
    public void setTrustStore(String truststore)
    {
        checkStarted();
        
        _trustStorePath = truststore;
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
     * @param truststoreProvider
     *            The provider of the trust store
     */
    public void setTrustStoreProvider(String truststoreProvider)
    {
        checkStarted();
        
        _trustStoreProvider = truststoreProvider;
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
     * @param truststoreType
     *            The type of the trust store (default "JKS")
     */
    public void setTrustStoreType(String truststoreType)
    {
        checkStarted();
        
        _trustStoreType = truststoreType;
    }

    /* ------------------------------------------------------------ */
    /** Get the _trustStoreInputStream.
     * @return the _trustStoreInputStream
     */
    public InputStream getTrustStoreInputStream()
    {
        checkConfig();
        
        return _trustStoreInputStream;
    }

    /* ------------------------------------------------------------ */
    /** Set the _trustStoreInputStream.
     * @param truststoreInputStream the InputStream to the TrustStore
     */
    public void setTrustStoreInputStream(InputStream truststoreInputStream)
    {
        checkStarted();
        
        _trustStoreInputStream = truststoreInputStream;
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
        checkStarted();
        
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
        checkStarted();
        
        _wantClientAuth = wantClientAuth;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if SSL certificate has to be validated
     */
    public boolean getValidateCerts()
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
        checkStarted();
        
        _validateCerts = validateCerts;
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
     * Set if SSL re-negotiation is allowed. CVE-2009-3555 discovered a vulnerability
     * in SSL/TLS with re-negotiation. If your JVM does not have CVE-2009-3555 fixed,
     * then re-negotiation should not be allowed.
     * 
     * @param allowRenegotiate
     *            true if re-negotiation is allowed (default false)
     */
    public void setAllowRenegotiate(boolean allowRenegotiate)
    {
        checkStarted();
        
        _allowRenegotiate = allowRenegotiate;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param password
     *            The password for the key store
     */
    public void setKeyStorePassword(String password)
    {
        checkStarted();
        
        _keyStorePassword = Password.getPassword(PASSWORD_PROPERTY,password,null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param password
     *            The password (if any) for the specific key within the key store
     */
    public void setKeyManagerPassword(String password)
    {
        checkStarted();
        
        _keyManagerPassword = Password.getPassword(KEYPASSWORD_PROPERTY,password,null);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param password
     *            The password for the trust store
     */
    public void setTrustStorePassword(String password)
    {
        checkStarted();
        
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
        checkStarted();
        
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
        checkStarted();
        
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
        checkStarted();
        
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
        checkStarted();
        
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
     * @param algorithm
     *            The algorithm name (default "SunX509") used by the {@link TrustManagerFactory}
     */
    public void setTrustManagerFactoryAlgorithm(String algorithm)
    {
        checkStarted();
        
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
        checkStarted();
        
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
        checkStarted();
        
        _maxCertPathLength = maxCertPathLength;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return The SSLContext
     */
    public SSLContext getSslContext()
    {
        return _context;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param sslContext
     *            Set a preconfigured SSLContext
     */
    public void setSslContext(SSLContext sslContext)
    {
        checkStarted();
        
        _context = sslContext;
    }

    /* ------------------------------------------------------------ */
    /**
     * @throws Exception
     */
    protected void createSSLContext() throws Exception
    {
        // verify that keystore and truststore 
        // parameters are set up correctly  
        checkConfig();
        
        KeyStore keyStore = getKeyStore(_keyStoreInputStream, _keyStorePath, _keyStoreType, 
                _keyStoreProvider, _keyStorePassword==null? null: _keyStorePassword.toString());
        KeyStore trustStore = getKeyStore(_trustStoreInputStream, _trustStorePath, _trustStoreType, 
                _trustStoreProvider, _trustStorePassword==null? null: _trustStorePassword.toString());
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

            CertificateValidator validator = new CertificateValidator(trustStore,crls);
            validator.validate(keyStore, cert);
        }

        KeyManager[] keyManagers = getKeyManagers(keyStore);
        TrustManager[] trustManagers = getTrustManagers(trustStore,crls);

        SecureRandom secureRandom = _secureRandomAlgorithm == null?null:SecureRandom.getInstance(_secureRandomAlgorithm);
        _context = _sslProvider == null?SSLContext.getInstance(_sslProtocol):SSLContext.getInstance(_sslProtocol,_sslProvider);
        _context.init(keyManagers,trustManagers,secureRandom);
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
            if (_validateCerts && _trustManagerFactoryAlgorithm.equalsIgnoreCase("PKIX"))
            {
                PKIXBuilderParameters pbParams = new PKIXBuilderParameters(trustStore,new X509CertSelector());

                // Enable revocation checking
                pbParams.setRevocationEnabled(true);

                // Set maximum certification path length
                pbParams.setMaxPathLength(_maxCertPathLength);

                if (crls != null && !crls.isEmpty())
                {
                    pbParams.addCertStore(CertStore.getInstance("Collection",new CollectionCertStoreParameters(crls)));
                }

                // Enable On-Line Certificate Status Protocol (OCSP) support
                Security.setProperty("ocsp.enable","true");

                // Enable Certificate Revocation List Distribution Points (CRLDP) support
                System.setProperty("com.sun.security.enableCRLDP","true");

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
    protected KeyStore getKeyStore(InputStream storeStream, String storePath, String storeType, String storeProvider, String storePassword) throws Exception
    {
        KeyStore keystore = null;

        if (storeStream != null || storePath != null)
        {
            InputStream inStream = storeStream;
            try
            {
                if (inStream == null)
                {
                    inStream = Resource.newResource(storePath).getInputStream();
                }
                
                if (storeProvider != null)
                {
                    keystore = KeyStore.getInstance(storeType, storeProvider);
                }
                else
                {
                    keystore = KeyStore.getInstance(storeType);
                }
    
                keystore.load(inStream, storePassword == null ? null : storePassword.toCharArray());
            }
            finally
            {
                if (inStream != null)
                {
                    inStream.close();
                }
            }
        }
        
        return keystore;
    }

    /* ------------------------------------------------------------ */
    protected Collection<? extends CRL> loadCRL(String crlPath) throws Exception
    {
        Collection<? extends CRL> crlList = null;

        if (crlPath != null)
        {
            InputStream in = null;
            try
            {
                in = Resource.newResource(crlPath).getInputStream();
                crlList = CertificateFactory.getInstance("X.509").generateCRLs(in);
            }
            finally
            {
                if (in != null)
                {
                    in.close();
                }
            }
        }

        return crlList;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Check configuration. Ensures that if keystore has been
     * configured but there's no truststore, that keystore is
     * used as truststore.
     * @return true SslContextFactory configuration can be used in server connector.
     */
    public boolean checkConfig()
    {
        boolean check = true;
        if (_keyStoreInputStream == null && _keyStorePath == null)
        {
            // configuration doesn't have a valid keystore
            check = false;
        }
        else 
        {
             // if the keystore has been configured but there is no 
             // truststore configured, use the keystore as the truststore
            if (_trustStoreInputStream == null && _trustStorePath == null)
            {
                _trustStorePath = _keyStorePath;
                _trustStoreInputStream = _keyStoreInputStream;
                _trustStoreType = _keyStoreType;
                _trustStoreProvider = _keyStoreProvider;
                _trustStorePassword = _keyStorePassword;
                _trustManagerFactoryAlgorithm = _keyManagerFactoryAlgorithm;
            }
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
                throw new RuntimeException(ex);
            }
        }
        
        return check;
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
        Set<String> selectedCipherSuites = null;
        if (enabledCipherSuites != null)
        {
            selectedCipherSuites = new HashSet<String>(Arrays.asList(enabledCipherSuites));
        }
        else
        {
            selectedCipherSuites = new HashSet<String>();
        }

        if ((supportedCipherSuites != null && supportedCipherSuites.length > 0) && 
            (_includeCipherSuites != null && _includeCipherSuites.size() > 0))
        {
            Set<String> supportedCSList = new HashSet<String>(Arrays.asList(supportedCipherSuites));
            
            for (String cipherName : _includeCipherSuites)
            {
                if ((!selectedCipherSuites.contains(cipherName)) && 
                    supportedCSList.contains(cipherName))
                {
                    selectedCipherSuites.add(cipherName);
                }
            }
        }

        if (_excludeCipherSuites != null && _excludeCipherSuites.size() > 0)
        {
            for (String cipherName : _excludeCipherSuites)
            {
                if (selectedCipherSuites.contains(cipherName))
                {
                    selectedCipherSuites.remove(cipherName);
                }
            }
        }

        return selectedCipherSuites.toArray(new String[selectedCipherSuites.size()]);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Check if the lifecycle has been started and throw runtime exception
     */
    protected void checkStarted()
    {
        if (isStarted())
        {
            throw new IllegalStateException("Cannot modify configuration after SslContextFactory was started");
        }
    }
}
