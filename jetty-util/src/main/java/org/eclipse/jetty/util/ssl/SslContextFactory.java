//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CRL;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
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
public class SslContextFactory extends AbstractLifeCycle implements Dumpable
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
                    KeyManagerFactory.getDefaultAlgorithm() : Security.getProperty("ssl.KeyManagerFactory.algorithm"));

    public static final String DEFAULT_TRUSTMANAGERFACTORY_ALGORITHM =
            (Security.getProperty("ssl.TrustManagerFactory.algorithm") == null ?
                    TrustManagerFactory.getDefaultAlgorithm() : Security.getProperty("ssl.TrustManagerFactory.algorithm"));

    /** String name of key password property. */
    public static final String KEYPASSWORD_PROPERTY = "org.eclipse.jetty.ssl.keypassword";

    /** String name of keystore password property. */
    public static final String PASSWORD_PROPERTY = "org.eclipse.jetty.ssl.password";

    private final Set<String> _excludeProtocols = new LinkedHashSet<>();
    private final Set<String> _includeProtocols = new LinkedHashSet<>();
    private final Set<String> _excludeCipherSuites = new LinkedHashSet<>();
    private final List<String> _includeCipherSuites = new ArrayList<>();
    private final Map<String, X509> _aliasX509 = new HashMap<>();
    private final Map<String, X509> _certHosts = new HashMap<>();
    private final Map<String, X509> _certWilds = new HashMap<>();
    private String[] _selectedProtocols;
    private boolean _useCipherSuitesOrder = true;
    private Comparator<String> _cipherComparator;
    private String[] _selectedCipherSuites;
    private Resource _keyStoreResource;
    private String _keyStoreProvider;
    private String _keyStoreType = "JKS";
    private String _certAlias;
    private Resource _trustStoreResource;
    private String _trustStoreProvider;
    private String _trustStoreType = "JKS";
    private boolean _needClientAuth = false;
    private boolean _wantClientAuth = false;
    private Password _keyStorePassword;
    private Password _keyManagerPassword;
    private Password _trustStorePassword;
    private String _sslProvider;
    private String _sslProtocol = "TLS";
    private String _secureRandomAlgorithm;
    private String _keyManagerFactoryAlgorithm = DEFAULT_KEYMANAGERFACTORY_ALGORITHM;
    private String _trustManagerFactoryAlgorithm = DEFAULT_TRUSTMANAGERFACTORY_ALGORITHM;
    private boolean _validateCerts;
    private boolean _validatePeerCerts;
    private int _maxCertPathLength = -1;
    private String _crlPath;
    private boolean _enableCRLDP = false;
    private boolean _enableOCSP = false;
    private String _ocspResponderURL;
    private KeyStore _setKeyStore;
    private KeyStore _setTrustStore;
    private boolean _sessionCachingEnabled = true;
    private int _sslSessionCacheSize = -1;
    private int _sslSessionTimeout = -1;
    private SSLContext _setContext;
    private String _endpointIdentificationAlgorithm = null;
    private boolean _trustAll;
    private boolean _renegotiationAllowed = true;
    private Factory _factory;

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
     *
     * @param trustAll whether to blindly trust all certificates
     * @see #setTrustAll(boolean)
     */
    public SslContextFactory(boolean trustAll)
    {
        this(trustAll, null);
    }

    /**
     * Construct an instance of SslContextFactory
     *
     * @param keyStorePath default keystore location
     */
    public SslContextFactory(String keyStorePath)
    {
        this(false, keyStorePath);
    }

    private SslContextFactory(boolean trustAll, String keyStorePath)
    {
        setTrustAll(trustAll);
        addExcludeProtocols("SSL", "SSLv2", "SSLv2Hello", "SSLv3");
        setExcludeCipherSuites("^.*_(MD5|SHA|SHA1)$");
        if (keyStorePath != null)
            setKeyStorePath(keyStorePath);
    }

    /**
     * Creates the SSLContext object and starts the lifecycle
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        synchronized (this)
        {
            load();
        }
    }

    private void load() throws Exception
    {
        SSLContext context = _setContext;
        KeyStore keyStore = _setKeyStore;
        KeyStore trustStore = _setTrustStore;

        if (context == null)
        {
            // Is this an empty factory?
            if (keyStore == null && _keyStoreResource == null && trustStore == null && _trustStoreResource == null)
            {
                TrustManager[] trust_managers = null;

                if (isTrustAll())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No keystore or trust store configured.  ACCEPTING UNTRUSTED CERTIFICATES!!!!!");
                    // Create a trust manager that does not validate certificate chains
                    trust_managers = TRUST_ALL_CERTS;
                }

                String algorithm = getSecureRandomAlgorithm();
                SecureRandom secureRandom = algorithm == null ? null : SecureRandom.getInstance(algorithm);
                context = _sslProvider == null ? SSLContext.getInstance(_sslProtocol) : SSLContext.getInstance(_sslProtocol, _sslProvider);
                context.init(null, trust_managers, secureRandom);
            }
            else
            {
                if (keyStore == null)
                    keyStore = loadKeyStore(_keyStoreResource);
                if (trustStore == null)
                    trustStore = loadTrustStore(_trustStoreResource);

                Collection<? extends CRL> crls = loadCRL(getCrlPath());

                // Look for X.509 certificates to create alias map
                if (keyStore != null)
                {
                    for (String alias : Collections.list(keyStore.aliases()))
                    {
                        Certificate certificate = keyStore.getCertificate(alias);
                        if (certificate != null && "X.509".equals(certificate.getType()))
                        {
                            X509Certificate x509C = (X509Certificate)certificate;

                            // Exclude certificates with special uses
                            if (X509.isCertSign(x509C))
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Skipping " + x509C);
                                continue;
                            }
                            X509 x509 = new X509(alias, x509C);
                            _aliasX509.put(alias, x509);

                            if (isValidateCerts())
                            {
                                CertificateValidator validator = new CertificateValidator(trustStore, crls);
                                validator.setMaxCertPathLength(getMaxCertPathLength());
                                validator.setEnableCRLDP(isEnableCRLDP());
                                validator.setEnableOCSP(isEnableOCSP());
                                validator.setOcspResponderURL(getOcspResponderURL());
                                validator.validate(keyStore, x509C); // TODO what about truststore?
                            }

                            LOG.info("x509={} for {}", x509, this);

                            for (String h : x509.getHosts())
                                _certHosts.put(h, x509);
                            for (String w : x509.getWilds())
                                _certWilds.put(w, x509);
                        }
                    }
                }

                // Instantiate key and trust managers
                KeyManager[] keyManagers = getKeyManagers(keyStore);
                TrustManager[] trustManagers = getTrustManagers(trustStore, crls);

                // Initialize context
                SecureRandom secureRandom = (_secureRandomAlgorithm == null) ? null : SecureRandom.getInstance(_secureRandomAlgorithm);
                context = _sslProvider == null ? SSLContext.getInstance(_sslProtocol) : SSLContext.getInstance(_sslProtocol, _sslProvider);
                context.init(keyManagers, trustManagers, secureRandom);
            }
        }

        // Initialize cache
        SSLSessionContext serverContext = context.getServerSessionContext();
        if (serverContext != null)
        {
            if (getSslSessionCacheSize() > -1)
                serverContext.setSessionCacheSize(getSslSessionCacheSize());
            if (getSslSessionTimeout() > -1)
                serverContext.setSessionTimeout(getSslSessionTimeout());
        }

        // select the protocols and ciphers
        SSLParameters enabled = context.getDefaultSSLParameters();
        SSLParameters supported = context.getSupportedSSLParameters();
        selectCipherSuites(enabled.getCipherSuites(), supported.getCipherSuites());
        selectProtocols(enabled.getProtocols(), supported.getProtocols());

        _factory = new Factory(keyStore, trustStore, context);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Selected Protocols {} of {}", Arrays.asList(_selectedProtocols), Arrays.asList(supported.getProtocols()));
            LOG.debug("Selected Ciphers   {} of {}", Arrays.asList(_selectedCipherSuites), Arrays.asList(supported.getCipherSuites()));
        }
    }
    
    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }
    
    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(String.valueOf(this)).append(" trustAll=").append(Boolean.toString(_trustAll)).append(System.lineSeparator());
    
        try
        {
            /* Use a pristine SSLEngine (not one from this SslContextFactory).
             * This will allow for proper detection and identification
             * of JRE/lib/security/java.security level disabled features
             */
            SSLEngine sslEngine = SSLContext.getDefault().createSSLEngine();
    
            List<Object> selections = new ArrayList<>();
            
            // protocols
            selections.add(new SslSelectionDump("Protocol",
                    sslEngine.getSupportedProtocols(),
                    sslEngine.getEnabledProtocols(),
                    getExcludeProtocols(),
                    getIncludeProtocols()));
            
            // ciphers
            selections.add(new SslSelectionDump("Cipher Suite",
                    sslEngine.getSupportedCipherSuites(),
                    sslEngine.getEnabledCipherSuites(),
                    getExcludeCipherSuites(),
                    getIncludeCipherSuites()));
            
            ContainerLifeCycle.dump(out, indent, selections);
        }
        catch (NoSuchAlgorithmException ignore)
        {
            LOG.ignore(ignore);
        }
    }
    
    @Override
    protected void doStop() throws Exception
    {
        synchronized (this)
        {
            unload();
        }
        super.doStop();
    }

    private void unload()
    {
        _factory = null;
        _selectedProtocols = null;
        _selectedCipherSuites = null;
        _aliasX509.clear();
        _certHosts.clear();
        _certWilds.clear();
    }

    public String[] getSelectedProtocols()
    {
        return Arrays.copyOf(_selectedProtocols, _selectedProtocols.length);
    }

    public String[] getSelectedCipherSuites()
    {
        return Arrays.copyOf(_selectedCipherSuites, _selectedCipherSuites.length);
    }

    public Comparator<String> getCipherComparator()
    {
        return _cipherComparator;
    }

    public void setCipherComparator(Comparator<String> cipherComparator)
    {
        if (cipherComparator != null)
            setUseCipherSuitesOrder(true);
        _cipherComparator = cipherComparator;
    }

    public Set<String> getAliases()
    {
        return Collections.unmodifiableSet(_aliasX509.keySet());
    }

    public X509 getX509(String alias)
    {
        return _aliasX509.get(alias);
    }

    /**
     * @return The array of protocol names to exclude from
     * {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public String[] getExcludeProtocols()
    {
        return _excludeProtocols.toArray(new String[0]);
    }

    /**
     * @param protocols The array of protocol names to exclude from
     *                  {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public void setExcludeProtocols(String... protocols)
    {
        _excludeProtocols.clear();
        _excludeProtocols.addAll(Arrays.asList(protocols));
    }

    /**
     * @param protocol Protocol names to add to {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public void addExcludeProtocols(String... protocol)
    {
        _excludeProtocols.addAll(Arrays.asList(protocol));
    }

    /**
     * @return The array of protocol names to include in
     * {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public String[] getIncludeProtocols()
    {
        return _includeProtocols.toArray(new String[0]);
    }

    /**
     * @param protocols The array of protocol names to include in
     *                  {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public void setIncludeProtocols(String... protocols)
    {
        _includeProtocols.clear();
        _includeProtocols.addAll(Arrays.asList(protocols));
    }

    /**
     * @return The array of cipher suite names to exclude from
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public String[] getExcludeCipherSuites()
    {
        return _excludeCipherSuites.toArray(new String[0]);
    }

    /**
     * You can either use the exact cipher suite name or a a regular expression.
     *
     * @param cipherSuites The array of cipher suite names to exclude from
     *                     {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public void setExcludeCipherSuites(String... cipherSuites)
    {
        _excludeCipherSuites.clear();
        _excludeCipherSuites.addAll(Arrays.asList(cipherSuites));
    }

    /**
     * @param cipher Cipher names to add to {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public void addExcludeCipherSuites(String... cipher)
    {
        _excludeCipherSuites.addAll(Arrays.asList(cipher));
    }

    /**
     * @return The array of cipher suite names to include in
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public String[] getIncludeCipherSuites()
    {
        return _includeCipherSuites.toArray(new String[0]);
    }

    /**
     * You can either use the exact cipher suite name or a a regular expression.
     *
     * @param cipherSuites The array of cipher suite names to include in
     *                     {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public void setIncludeCipherSuites(String... cipherSuites)
    {
        _includeCipherSuites.clear();
        _includeCipherSuites.addAll(Arrays.asList(cipherSuites));
    }

    public boolean isUseCipherSuitesOrder()
    {
        return _useCipherSuitesOrder;
    }

    public void setUseCipherSuitesOrder(boolean useCipherSuitesOrder)
    {
        _useCipherSuitesOrder = useCipherSuitesOrder;
    }

    /**
     * @return The file or URL of the SSL Key store.
     */
    public String getKeyStorePath()
    {
        return _keyStoreResource.toString();
    }

    /**
     * @param keyStorePath The file or URL of the SSL Key store.
     */
    public void setKeyStorePath(String keyStorePath)
    {
        try
        {
            _keyStoreResource = Resource.newResource(keyStorePath);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * @return The provider of the key store
     */
    public String getKeyStoreProvider()
    {
        return _keyStoreProvider;
    }

    /**
     * @param keyStoreProvider The provider of the key store
     */
    public void setKeyStoreProvider(String keyStoreProvider)
    {
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
     * @param keyStoreType The type of the key store (default "JKS")
     */
    public void setKeyStoreType(String keyStoreType)
    {
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
     * Set the default certificate Alias.
     * <p>This can be used if there are multiple non-SNI certificates
     * to specify the certificate that should be used, or with SNI
     * certificates to set a certificate to try if no others match
     * </p>
     *
     * @param certAlias Alias of SSL certificate for the connector
     */
    public void setCertAlias(String certAlias)
    {
        _certAlias = certAlias;
    }

    /**
     * @param trustStorePath The file name or URL of the trust store location
     */
    public void setTrustStorePath(String trustStorePath)
    {
        try
        {
            _trustStoreResource = Resource.newResource(trustStorePath);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * @return The provider of the trust store
     */
    public String getTrustStoreProvider()
    {
        return _trustStoreProvider;
    }

    /**
     * @param trustStoreProvider The provider of the trust store
     */
    public void setTrustStoreProvider(String trustStoreProvider)
    {
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
     * @param trustStoreType The type of the trust store (default "JKS")
     */
    public void setTrustStoreType(String trustStoreType)
    {
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
     * @param needClientAuth True if SSL needs client authentication.
     * @see SSLEngine#getNeedClientAuth()
     */
    public void setNeedClientAuth(boolean needClientAuth)
    {
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
     * @param wantClientAuth True if SSL wants client authentication.
     * @see SSLEngine#getWantClientAuth()
     */
    public void setWantClientAuth(boolean wantClientAuth)
    {
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
     * @param validateCerts true if SSL certificates have to be validated
     */
    public void setValidateCerts(boolean validateCerts)
    {
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
     * @param validatePeerCerts true if SSL certificates of the peer have to be validated
     */
    public void setValidatePeerCerts(boolean validatePeerCerts)
    {
        _validatePeerCerts = validatePeerCerts;
    }

    /**
     * @param password The password for the key store.  If null is passed and
     *                 a keystore is set, then
     *                 the {@link #getPassword(String)} is used to
     *                 obtain a password either from the {@value #PASSWORD_PROPERTY}
     *                 system property or by prompting for manual entry.
     */
    public void setKeyStorePassword(String password)
    {
        if (password == null)
        {
            if (_keyStoreResource != null)
                _keyStorePassword = getPassword(PASSWORD_PROPERTY);
            else
                _keyStorePassword = null;
        }
        else
        {
            _keyStorePassword = newPassword(password);
        }
    }

    /**
     * @param password The password (if any) for the specific key within the key store.
     *                 If null is passed and the {@value #KEYPASSWORD_PROPERTY} system property is set,
     *                 then the {@link #getPassword(String)} is used to
     *                 obtain a password from the {@value #KEYPASSWORD_PROPERTY} system property.
     */
    public void setKeyManagerPassword(String password)
    {
        if (password == null)
        {
            if (System.getProperty(KEYPASSWORD_PROPERTY) != null)
                _keyManagerPassword = getPassword(KEYPASSWORD_PROPERTY);
            else
                _keyManagerPassword = null;
        }
        else
        {
            _keyManagerPassword = newPassword(password);
        }
    }

    /**
     * @param password The password for the truststore. If null is passed and a truststore is set
     *                 that is different from the keystore, then
     *                 the {@link #getPassword(String)} is used to
     *                 obtain a password either from the {@value #PASSWORD_PROPERTY}
     *                 system property or by prompting for manual entry.
     */
    public void setTrustStorePassword(String password)
    {
        if (password == null)
        {
            if (_trustStoreResource != null && !_trustStoreResource.equals(_keyStoreResource))
                _trustStorePassword = getPassword(PASSWORD_PROPERTY);
            else
                _trustStorePassword = null;
        }
        else
        {
            _trustStorePassword = newPassword(password);
        }
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
     * @param provider The SSL provider name, which if set is passed to
     *                 {@link SSLContext#getInstance(String, String)}
     */
    public void setProvider(String provider)
    {
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
     * @param protocol The SSL protocol (default "TLS") passed to
     *                 {@link SSLContext#getInstance(String, String)}
     */
    public void setProtocol(String protocol)
    {
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
     * @param algorithm The algorithm name, which if set is passed to
     *                  {@link SecureRandom#getInstance(String)} to obtain the {@link SecureRandom} instance passed to
     *                  {@link SSLContext#init(javax.net.ssl.KeyManager[], javax.net.ssl.TrustManager[], SecureRandom)}
     */
    public void setSecureRandomAlgorithm(String algorithm)
    {
        _secureRandomAlgorithm = algorithm;
    }

    /**
     * @return The algorithm name (default "SunX509") used by the {@link KeyManagerFactory}
     */
    public String getKeyManagerFactoryAlgorithm()
    {
        return _keyManagerFactoryAlgorithm;
    }

    /**
     * @param algorithm The algorithm name (default "SunX509") used by the {@link KeyManagerFactory}
     */
    public void setKeyManagerFactoryAlgorithm(String algorithm)
    {
        _keyManagerFactoryAlgorithm = algorithm;
    }

    /**
     * @return The algorithm name (default "SunX509") used by the {@link TrustManagerFactory}
     */
    public String getTrustManagerFactoryAlgorithm()
    {
        return _trustManagerFactoryAlgorithm;
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
        if (trustAll)
            setEndpointIdentificationAlgorithm(null);
    }

    /**
     * @param algorithm The algorithm name (default "SunX509") used by the {@link TrustManagerFactory}
     *                  Use the string "TrustAll" to install a trust manager that trusts all.
     */
    public void setTrustManagerFactoryAlgorithm(String algorithm)
    {
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
     * @param crlPath Path to file that contains Certificate Revocation List
     */
    public void setCrlPath(String crlPath)
    {
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
     * @param maxCertPathLength maximum number of intermediate certificates in
     *                          the certification path (-1 for unlimited)
     */
    public void setMaxCertPathLength(int maxCertPathLength)
    {
        _maxCertPathLength = maxCertPathLength;
    }

    /**
     * @return The SSLContext
     */
    public SSLContext getSslContext()
    {
        if (!isStarted())
            return _setContext;

        synchronized (this)
        {
            return _factory._context;
        }
    }

    /**
     * @param sslContext Set a preconfigured SSLContext
     */
    public void setSslContext(SSLContext sslContext)
    {
        _setContext = sslContext;
    }

    /**
     * @return the endpoint identification algorithm
     */
    public String getEndpointIdentificationAlgorithm()
    {
        return _endpointIdentificationAlgorithm;
    }

    /**
     * When set to "HTTPS" hostname verification will be enabled
     *
     * @param endpointIdentificationAlgorithm Set the endpointIdentificationAlgorithm
     */
    public void setEndpointIdentificationAlgorithm(String endpointIdentificationAlgorithm)
    {
        _endpointIdentificationAlgorithm = endpointIdentificationAlgorithm;
    }

    /**
     * Override this method to provide alternate way to load a keystore.
     *
     * @param resource the resource to load the keystore from
     * @return the key store instance
     * @throws Exception if the keystore cannot be loaded
     */
    protected KeyStore loadKeyStore(Resource resource) throws Exception
    {
        String storePassword = _keyStorePassword == null ? null : _keyStorePassword.toString();
        return CertificateUtils.getKeyStore(resource, getKeyStoreType(), getKeyStoreProvider(), storePassword);
    }

    /**
     * Override this method to provide alternate way to load a truststore.
     *
     * @param resource the resource to load the truststore from
     * @return the key store instance
     * @throws Exception if the truststore cannot be loaded
     */
    protected KeyStore loadTrustStore(Resource resource) throws Exception
    {
        String type = getTrustStoreType();
        String provider = getTrustStoreProvider();
        String passwd = _trustStorePassword == null ? null : _trustStorePassword.toString();
        if (resource == null || resource.equals(_keyStoreResource))
        {
            resource = _keyStoreResource;
            if (type == null)
                type = _keyStoreType;
            if (provider == null)
                provider = _keyStoreProvider;
            if (passwd == null)
                passwd = _keyStorePassword == null ? null : _keyStorePassword.toString();
        }
        return CertificateUtils.getKeyStore(resource, type, provider, passwd);
    }

    /**
     * Loads certificate revocation list (CRL) from a file.
     * <p>
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
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(getKeyManagerFactoryAlgorithm());
            keyManagerFactory.init(keyStore, _keyManagerPassword == null ? (_keyStorePassword == null ? null : _keyStorePassword.toString().toCharArray()) : _keyManagerPassword.toString().toCharArray());
            managers = keyManagerFactory.getKeyManagers();

            if (managers != null)
            {
                String alias = getCertAlias();
                if (alias != null)
                {
                    for (int idx = 0; idx < managers.length; idx++)
                    {
                        if (managers[idx] instanceof X509ExtendedKeyManager)
                            managers[idx] = new AliasedX509ExtendedKeyManager((X509ExtendedKeyManager)managers[idx], alias);
                    }
                }

                if (!_certHosts.isEmpty() || !_certWilds.isEmpty())
                {
                    for (int idx = 0; idx < managers.length; idx++)
                    {
                        if (managers[idx] instanceof X509ExtendedKeyManager)
                            managers[idx] = new SniX509ExtendedKeyManager((X509ExtendedKeyManager)managers[idx]);
                    }
                }
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("managers={} for {}", managers, this);

        return managers;
    }

    protected TrustManager[] getTrustManagers(KeyStore trustStore, Collection<? extends CRL> crls) throws Exception
    {
        TrustManager[] managers = null;
        if (trustStore != null)
        {
            // Revocation checking is only supported for PKIX algorithm
            if (isValidatePeerCerts() && "PKIX".equalsIgnoreCase(getTrustManagerFactoryAlgorithm()))
            {
                PKIXBuilderParameters pbParams = new PKIXBuilderParameters(trustStore, new X509CertSelector());

                // Set maximum certification path length
                pbParams.setMaxPathLength(_maxCertPathLength);

                // Make sure revocation checking is enabled
                pbParams.setRevocationEnabled(true);

                if (crls != null && !crls.isEmpty())
                {
                    pbParams.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(crls)));
                }

                if (_enableCRLDP)
                {
                    // Enable Certificate Revocation List Distribution Points (CRLDP) support
                    System.setProperty("com.sun.security.enableCRLDP", "true");
                }

                if (_enableOCSP)
                {
                    // Enable On-Line Certificate Status Protocol (OCSP) support
                    Security.setProperty("ocsp.enable", "true");

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
     * Select protocols to be used by the connector
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported protocols.
     *
     * @param enabledProtocols   Array of enabled protocols
     * @param supportedProtocols Array of supported protocols
     */
    public void selectProtocols(String[] enabledProtocols, String[] supportedProtocols)
    {
        Set<String> selected_protocols = new LinkedHashSet<>();

        // Set the starting protocols - either from the included or enabled list
        if (!_includeProtocols.isEmpty())
        {
            // Use only the supported included protocols
            for (String protocol : _includeProtocols)
            {
                if (Arrays.asList(supportedProtocols).contains(protocol))
                    selected_protocols.add(protocol);
                else
                    LOG.info("Protocol {} not supported in {}", protocol, Arrays.asList(supportedProtocols));
            }
        }
        else
            selected_protocols.addAll(Arrays.asList(enabledProtocols));

        // Remove any excluded protocols
        selected_protocols.removeAll(_excludeProtocols);

        if (selected_protocols.isEmpty())
            LOG.warn("No selected protocols from {}", Arrays.asList(supportedProtocols));

        _selectedProtocols = selected_protocols.toArray(new String[0]);
    }

    /**
     * Select cipher suites to be used by the connector
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported cipher suite lists.
     *
     * @param enabledCipherSuites   Array of enabled cipher suites
     * @param supportedCipherSuites Array of supported cipher suites
     */
    protected void selectCipherSuites(String[] enabledCipherSuites, String[] supportedCipherSuites)
    {
        List<String> selected_ciphers = new ArrayList<>();

        // Set the starting ciphers - either from the included or enabled list
        if (_includeCipherSuites.isEmpty())
            selected_ciphers.addAll(Arrays.asList(enabledCipherSuites));
        else
            processIncludeCipherSuites(supportedCipherSuites, selected_ciphers);

        removeExcludedCipherSuites(selected_ciphers);

        if (selected_ciphers.isEmpty())
            LOG.warn("No supported ciphers from {}", Arrays.asList(supportedCipherSuites));

        Comparator<String> comparator = getCipherComparator();
        if (comparator != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Sorting selected ciphers with {}", comparator);
            Collections.sort(selected_ciphers, comparator);
        }

        _selectedCipherSuites = selected_ciphers.toArray(new String[0]);
    }

    protected void processIncludeCipherSuites(String[] supportedCipherSuites, List<String> selected_ciphers)
    {
        for (String cipherSuite : _includeCipherSuites)
        {
            Pattern p = Pattern.compile(cipherSuite);
            boolean added = false;
            for (String supportedCipherSuite : supportedCipherSuites)
            {
                Matcher m = p.matcher(supportedCipherSuite);
                if (m.matches())
                {
                    added = true;
                    selected_ciphers.add(supportedCipherSuite);
                }

            }
            if (!added)
                LOG.info("No Cipher matching '{}' is supported", cipherSuite);
        }
    }

    protected void removeExcludedCipherSuites(List<String> selected_ciphers)
    {
        for (String excludeCipherSuite : _excludeCipherSuites)
        {
            Pattern excludeCipherPattern = Pattern.compile(excludeCipherSuite);
            for (Iterator<String> i = selected_ciphers.iterator(); i.hasNext(); )
            {
                String selectedCipherSuite = i.next();
                Matcher m = excludeCipherPattern.matcher(selectedCipherSuite);
                if (m.matches())
                    i.remove();
            }
        }
    }

    /**
     * Check if the lifecycle has been started and throw runtime exception
     */
    private void checkIsStarted()
    {
        if (!isStarted())
            throw new IllegalStateException("!STARTED: " + this);
    }

    /**
     * @return true if CRL Distribution Points support is enabled
     */
    public boolean isEnableCRLDP()
    {
        return _enableCRLDP;
    }

    /**
     * Enables CRL Distribution Points Support
     *
     * @param enableCRLDP true - turn on, false - turns off
     */
    public void setEnableCRLDP(boolean enableCRLDP)
    {
        _enableCRLDP = enableCRLDP;
    }

    /**
     * @return true if On-Line Certificate Status Protocol support is enabled
     */
    public boolean isEnableOCSP()
    {
        return _enableOCSP;
    }

    /**
     * Enables On-Line Certificate Status Protocol support
     *
     * @param enableOCSP true - turn on, false - turn off
     */
    public void setEnableOCSP(boolean enableOCSP)
    {
        _enableOCSP = enableOCSP;
    }

    /**
     * @return Location of the OCSP Responder
     */
    public String getOcspResponderURL()
    {
        return _ocspResponderURL;
    }

    /**
     * Set the location of the OCSP Responder.
     *
     * @param ocspResponderURL location of the OCSP Responder
     */
    public void setOcspResponderURL(String ocspResponderURL)
    {
        _ocspResponderURL = ocspResponderURL;
    }

    /**
     * Set the key store.
     *
     * @param keyStore the key store to set
     */
    public void setKeyStore(KeyStore keyStore)
    {
        _setKeyStore = keyStore;
    }

    public KeyStore getKeyStore()
    {
        if (!isStarted())
            return _setKeyStore;

        synchronized (this)
        {
            return _factory._keyStore;
        }
    }

    /**
     * Set the trust store.
     *
     * @param trustStore the trust store to set
     */
    public void setTrustStore(KeyStore trustStore)
    {
        _setTrustStore = trustStore;
    }

    public KeyStore getTrustStore()
    {
        if (!isStarted())
            return _setTrustStore;

        synchronized (this)
        {
            return _factory._trustStore;
        }
    }

    /**
     * Set the key store resource.
     *
     * @param resource the key store resource to set
     */
    public void setKeyStoreResource(Resource resource)
    {
        _keyStoreResource = resource;
    }

    public Resource getKeyStoreResource()
    {
        return _keyStoreResource;
    }

    /**
     * Set the trust store resource.
     *
     * @param resource the trust store resource to set
     */
    public void setTrustStoreResource(Resource resource)
    {
        _trustStoreResource = resource;
    }

    public Resource getTrustStoreResource()
    {
        return _trustStoreResource;
    }

    /**
     * @return true if SSL Session caching is enabled
     */
    public boolean isSessionCachingEnabled()
    {
        return _sessionCachingEnabled;
    }

    /**
     * Set the flag to enable SSL Session caching.
     * If set to true, then the {@link SSLContext#createSSLEngine(String, int)} method is
     * used to pass host and port information as a hint for session reuse.  Note that
     * this is only a hint and session may not be reused. Moreover, the hint is typically
     * only used on client side implementations and setting this to false does not
     * stop a server from accepting an offered session ID to reuse.
     *
     * @param enableSessionCaching the value of the flag
     */
    public void setSessionCachingEnabled(boolean enableSessionCaching)
    {
        _sessionCachingEnabled = enableSessionCaching;
    }

    /**
     * Get SSL session cache size.
     * Passed directly to {@link SSLSessionContext#setSessionCacheSize(int)}
     *
     * @return SSL session cache size
     */
    public int getSslSessionCacheSize()
    {
        return _sslSessionCacheSize;
    }

    /**
     * Set SSL session cache size.
     * <p>Set the max cache size to be set on {@link SSLSessionContext#setSessionCacheSize(int)}
     * when this factory is started.</p>
     *
     * @param sslSessionCacheSize SSL session cache size to set. A value  of -1 (default) uses
     *                            the JVM default, 0 means unlimited and positive number is a max size.
     */
    public void setSslSessionCacheSize(int sslSessionCacheSize)
    {
        _sslSessionCacheSize = sslSessionCacheSize;
    }

    /**
     * Get SSL session timeout.
     *
     * @return SSL session timeout
     */
    public int getSslSessionTimeout()
    {
        return _sslSessionTimeout;
    }

    /**
     * Set SSL session timeout.
     * <p>Set the timeout in seconds to be set on {@link SSLSessionContext#setSessionTimeout(int)}
     * when this factory is started.</p>
     *
     * @param sslSessionTimeout SSL session timeout to set in seconds. A value of -1 (default) uses
     *                          the JVM default, 0 means unlimited and positive number is a timeout in seconds.
     */
    public void setSslSessionTimeout(int sslSessionTimeout)
    {
        _sslSessionTimeout = sslSessionTimeout;
    }

    /**
     * Returns the password object for the given realm.
     *
     * @param realm the realm
     * @return the Password object
     */
    protected Password getPassword(String realm)
    {
        return Password.getPassword(realm, null, null);
    }

    /**
     * Creates a new Password object.
     *
     * @param password the password string
     * @return the new Password object
     */
    public Password newPassword(String password)
    {
        return new Password(password);
    }

    public SSLServerSocket newSslServerSocket(String host, int port, int backlog) throws IOException
    {
        checkIsStarted();

        SSLContext context = getSslContext();
        SSLServerSocketFactory factory = context.getServerSocketFactory();
        SSLServerSocket socket =
                (SSLServerSocket)(host == null ?
                        factory.createServerSocket(port, backlog) :
                        factory.createServerSocket(port, backlog, InetAddress.getByName(host)));
        socket.setSSLParameters(customize(socket.getSSLParameters()));

        return socket;
    }

    public SSLSocket newSslSocket() throws IOException
    {
        checkIsStarted();

        SSLContext context = getSslContext();
        SSLSocketFactory factory = context.getSocketFactory();
        SSLSocket socket = (SSLSocket)factory.createSocket();
        socket.setSSLParameters(customize(socket.getSSLParameters()));

        return socket;
    }

    /**
     * Factory method for "scratch" {@link SSLEngine}s, usually only used for retrieving configuration
     * information such as the application buffer size or the list of protocols/ciphers.
     * <p>
     * This method should not be used for creating {@link SSLEngine}s that are used in actual socket
     * communication.
     *
     * @return a new, "scratch" {@link SSLEngine}
     */
    public SSLEngine newSSLEngine()
    {
        checkIsStarted();

        SSLContext context = getSslContext();
        SSLEngine sslEngine = context.createSSLEngine();
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
        checkIsStarted();

        SSLContext context = getSslContext();
        SSLEngine sslEngine = isSessionCachingEnabled() ?
                context.createSSLEngine(host, port) :
                context.createSSLEngine();
        customize(sslEngine);

        return sslEngine;
    }

    /**
     * Server-side only factory method for creating {@link SSLEngine}s.
     * <p>
     * If the given {@code address} is null, it is equivalent to {@link #newSSLEngine()}, otherwise
     * {@link #newSSLEngine(String, int)} is called.
     * <p>
     * If {@link #getNeedClientAuth()} is {@code true}, then the host name is passed to
     * {@link #newSSLEngine(String, int)}, possibly incurring in a reverse DNS lookup, which takes time
     * and may hang the selector (since this method is usually called by the selector thread).
     * <p>
     * Otherwise, the host address is passed to {@link #newSSLEngine(String, int)} without DNS lookup
     * penalties.
     * <p>
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

    /**
     * Customize an SslEngine instance with the configuration of this factory,
     * by calling {@link #customize(SSLParameters)}
     *
     * @param sslEngine the SSLEngine to customize
     */
    public void customize(SSLEngine sslEngine)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Customize {}", sslEngine);

        sslEngine.setSSLParameters(customize(sslEngine.getSSLParameters()));
    }

    /**
     * Customize an SslParameters instance with the configuration of this factory.
     *
     * @param sslParams The parameters to customize
     * @return The passed instance of sslParams (returned as a convenience)
     */
    public SSLParameters customize(SSLParameters sslParams)
    {
        sslParams.setEndpointIdentificationAlgorithm(getEndpointIdentificationAlgorithm());
        sslParams.setUseCipherSuitesOrder(isUseCipherSuitesOrder());
        if (!_certHosts.isEmpty() || !_certWilds.isEmpty())
            sslParams.setSNIMatchers(Collections.singletonList(new AliasSNIMatcher()));
        if (_selectedCipherSuites != null)
            sslParams.setCipherSuites(_selectedCipherSuites);
        if (_selectedProtocols != null)
            sslParams.setProtocols(_selectedProtocols);
        if (getWantClientAuth())
            sslParams.setWantClientAuth(true);
        if (getNeedClientAuth())
            sslParams.setNeedClientAuth(true);
        return sslParams;
    }

    public void reload(Consumer<SslContextFactory> consumer) throws Exception
    {
        synchronized (this)
        {
            consumer.accept(this);
            unload();
            load();
        }
    }

    public static X509Certificate[] getCertChain(SSLSession sslSession)
    {
        try
        {
            Certificate[] javaxCerts = sslSession.getPeerCertificates();
            if (javaxCerts == null || javaxCerts.length == 0)
                return null;

            int length = javaxCerts.length;
            X509Certificate[] javaCerts = new X509Certificate[length];

            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            for (int i = 0; i < length; i++)
            {
                byte bytes[] = javaxCerts[i].getEncoded();
                ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
                javaCerts[i] = (X509Certificate)cf.generateCertificate(stream);
            }

            return javaCerts;
        }
        catch (SSLPeerUnverifiedException pue)
        {
            return null;
        }
        catch (Exception e)
        {
            LOG.warn(Log.EXCEPTION, e);
            return null;
        }
    }

    /**
     * Given the name of a TLS/SSL cipher suite, return an int representing it effective stream
     * cipher key strength. i.e. How much entropy material is in the key material being fed into the
     * encryption routines.
     * <p>
     * This is based on the information on effective key lengths in RFC 2246 - The TLS Protocol
     * Version 1.0, Appendix C. CipherSuite definitions:
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
                _keyStoreResource,
                _trustStoreResource);
    }

    class Factory
    {
        private final KeyStore _keyStore;
        private final KeyStore _trustStore;
        private final SSLContext _context;

        Factory(KeyStore keyStore, KeyStore trustStore, SSLContext context)
        {
            super();
            _keyStore = keyStore;
            _trustStore = trustStore;
            _context = context;
        }
    }

    class AliasSNIMatcher extends SNIMatcher
    {
        private String _host;
        private X509 _x509;

        AliasSNIMatcher()
        {
            super(StandardConstants.SNI_HOST_NAME);
        }

        @Override
        public boolean matches(SNIServerName serverName)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("SNI matching for {}", serverName);

            if (serverName instanceof SNIHostName)
            {
                String host = _host = ((SNIHostName)serverName).getAsciiName();
                host = StringUtil.asciiToLowerCase(host);

                // Try an exact match
                _x509 = _certHosts.get(host);

                // Else try an exact wild match
                if (_x509 == null)
                {
                    _x509 = _certWilds.get(host);

                    // Else try an 1 deep wild match
                    if (_x509 == null)
                    {
                        int dot = host.indexOf('.');
                        if (dot >= 0)
                        {
                            String domain = host.substring(dot + 1);
                            _x509 = _certWilds.get(domain);
                        }
                    }
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("SNI matched {}->{}", host, _x509);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("SNI no match for {}", serverName);
            }

            // Return true and allow the KeyManager to accept or reject when choosing a certificate.
            // If we don't have a SNI host, or didn't see any certificate aliases,
            // just say true as it will either somehow work or fail elsewhere.
            return true;
        }

        public String getHost()
        {
            return _host;
        }

        public X509 getX509()
        {
            return _x509;
        }
    }
}
