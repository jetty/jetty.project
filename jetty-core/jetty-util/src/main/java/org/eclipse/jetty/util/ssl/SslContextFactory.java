//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.ssl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CRL;
import java.security.cert.CertStore;
import java.security.cert.CertStoreParameters;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathChecker;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.HostnameVerifier;
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
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.eclipse.jetty.util.security.CertificateUtils;
import org.eclipse.jetty.util.security.CertificateValidator;
import org.eclipse.jetty.util.security.Password;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>SslContextFactory is used to configure SSL parameters
 * to be used by server and client connectors.</p>
 * <p>Use {@link Server} to configure server-side connectors,
 * and {@link Client} to configure HTTP or WebSocket clients.</p>
 */
@ManagedObject
public abstract class SslContextFactory extends ContainerLifeCycle implements Dumpable
{
    public static final TrustManager[] TRUST_ALL_CERTS = new X509TrustManager[]{new X509ExtendedTrustManagerWrapper(null)};
    public static final String DEFAULT_KEYMANAGERFACTORY_ALGORITHM = KeyManagerFactory.getDefaultAlgorithm();
    public static final String DEFAULT_TRUSTMANAGERFACTORY_ALGORITHM = TrustManagerFactory.getDefaultAlgorithm();
    /**
     * String name of key password property.
     */
    public static final String KEYPASSWORD_PROPERTY = "org.eclipse.jetty.ssl.keypassword";
    /**
     * String name of keystore password property.
     */
    public static final String PASSWORD_PROPERTY = "org.eclipse.jetty.ssl.password";

    private static final Logger LOG = LoggerFactory.getLogger(SslContextFactory.class);
    private static final Logger LOG_CONFIG = LoggerFactory.getLogger(LOG.getName() + ".config");
    /**
     * Default Excluded Protocols List
     */
    private static final String[] DEFAULT_EXCLUDED_PROTOCOLS = {"SSL", "SSLv2", "SSLv2Hello", "SSLv3"};
    /**
     * Default Excluded Cipher Suite List
     */
    private static final String[] DEFAULT_EXCLUDED_CIPHER_SUITES = {
        // Exclude weak / insecure ciphers
        "^.*_(MD5|SHA|SHA1)$",
        // Exclude ciphers that don't support forward secrecy
        "^TLS_RSA_.*$",
        // The following exclusions are present to cleanup known bad cipher
        // suites that may be accidentally included via include patterns.
        // The default enabled cipher list in Java will not include these
        // (but they are available in the supported list).
        "^SSL_.*$",
        "^.*_NULL_.*$",
        "^.*_anon_.*$"
    };

    private final AutoLock _lock = new AutoLock();
    private final Set<String> _excludeProtocols = new LinkedHashSet<>();
    private final Set<String> _includeProtocols = new LinkedHashSet<>();
    private final Set<String> _excludeCipherSuites = new LinkedHashSet<>();
    private final Set<String> _includeCipherSuites = new LinkedHashSet<>();
    private final Map<String, X509> _aliasX509 = new HashMap<>();
    private final Map<String, X509> _certHosts = new HashMap<>();
    private final Map<String, X509> _certWilds = new HashMap<>();
    private String[] _selectedProtocols;
    private boolean _useCipherSuitesOrder = true;
    private Comparator<String> _cipherComparator;
    private String[] _selectedCipherSuites;
    private Resource _keyStoreResource;
    private String _keyStoreProvider;
    private String _keyStoreType = "PKCS12";
    private String _certAlias;
    private Resource _trustStoreResource;
    private String _trustStoreProvider;
    private String _trustStoreType;
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
    private String _endpointIdentificationAlgorithm = "HTTPS";
    private boolean _trustAll;
    private boolean _renegotiationAllowed = true;
    private int _renegotiationLimit = 5;
    private Factory _factory;
    private PKIXCertPathChecker _pkixCertPathChecker;
    private HostnameVerifier _hostnameVerifier;

    /**
     * Construct an instance of SslContextFactory with the default configuration.
     */
    protected SslContextFactory()
    {
        this(false);
    }

    /**
     * Construct an instance of SslContextFactory that trusts all certificates
     *
     * @param trustAll whether to blindly trust all certificates
     * @see #setTrustAll(boolean)
     */
    public SslContextFactory(boolean trustAll)
    {
        setTrustAll(trustAll);
        setExcludeProtocols(DEFAULT_EXCLUDED_PROTOCOLS);
        setExcludeCipherSuites(DEFAULT_EXCLUDED_CIPHER_SUITES);
    }

    /**
     * Creates the SSLContext object and starts the lifecycle
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        try (AutoLock l = _lock.lock())
        {
            load();
        }
        checkConfiguration();
    }

    protected void checkConfiguration()
    {
        SSLEngine engine = _factory._context.createSSLEngine();
        customize(engine);
        SSLParameters supported = engine.getSSLParameters();

        checkProtocols(supported);
        checkCiphers(supported);
    }

    protected void checkTrustAll()
    {
        if (isTrustAll())
            LOG_CONFIG.warn("Trusting all certificates configured for {}", this);
    }

    protected void checkEndPointIdentificationAlgorithm()
    {
        if (getEndpointIdentificationAlgorithm() == null)
            LOG_CONFIG.warn("No Client EndPointIdentificationAlgorithm configured for {}", this);
    }

    protected void checkProtocols(SSLParameters supported)
    {
        for (String protocol : supported.getProtocols())
        {
            for (String excluded : DEFAULT_EXCLUDED_PROTOCOLS)
            {
                if (excluded.equals(protocol))
                    LOG_CONFIG.warn("Protocol {} not excluded for {}", protocol, this);
            }
        }
    }

    protected void checkCiphers(SSLParameters supported)
    {
        for (String suite : supported.getCipherSuites())
        {
            for (String excludedSuiteRegex : DEFAULT_EXCLUDED_CIPHER_SUITES)
            {
                if (suite.matches(excludedSuiteRegex))
                    LOG_CONFIG.warn("Weak cipher suite {} enabled for {}", suite, this);
            }
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
                TrustManager[] trustManagers = null;

                if (isTrustAll())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No keystore or trust store configured.  ACCEPTING UNTRUSTED CERTIFICATES!!!!!");
                    // Create a trust manager that does not validate certificate chains
                    trustManagers = TRUST_ALL_CERTS;
                }

                context = getSSLContextInstance();
                context.init(null, trustManagers, getSecureRandomInstance());
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
                                    LOG.debug("Skipping {}", x509C);
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
                            {
                                _certHosts.put(h, x509);
                            }
                            for (String w : x509.getWilds())
                            {
                                _certWilds.put(w, x509);
                            }
                        }
                    }
                }

                // Instantiate key and trust managers
                KeyManager[] keyManagers = getKeyManagers(keyStore);
                TrustManager[] trustManagers = getTrustManagers(trustStore, crls);

                // Initialize context
                context = getSSLContextInstance();
                context.init(keyManagers, trustManagers, getSecureRandomInstance());
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
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        try
        {
            SSLEngine sslEngine = SSLContext.getDefault().createSSLEngine();
            Dumpable.dumpObjects(out, indent, this, "trustAll=" + _trustAll,
                new SslSelectionDump("Protocol",
                    sslEngine.getSupportedProtocols(),
                    sslEngine.getEnabledProtocols(),
                    getExcludeProtocols(),
                    getIncludeProtocols()),
                new SslSelectionDump("Cipher Suite",
                    sslEngine.getSupportedCipherSuites(),
                    sslEngine.getEnabledCipherSuites(),
                    getExcludeCipherSuites(),
                    getIncludeCipherSuites()));
        }
        catch (NoSuchAlgorithmException x)
        {
            LOG.trace("IGNORED", x);
        }
    }

    List<SslSelectionDump> selectionDump() throws NoSuchAlgorithmException
    {
        /* Use a pristine SSLEngine (not one from this SslContextFactory).
         * This will allow for proper detection and identification
         * of JRE/lib/security/java.security level disabled features
         */
        SSLEngine sslEngine = SSLContext.getDefault().createSSLEngine();

        List<SslSelectionDump> selections = new ArrayList<>();

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

        return selections;
    }

    @Override
    protected void doStop() throws Exception
    {
        try (AutoLock l = _lock.lock())
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

    Map<String, X509> aliasCerts()
    {
        return _aliasX509;
    }

    Map<String, X509> hostCerts()
    {
        return _certHosts;
    }

    Map<String, X509> wildCerts()
    {
        return _certWilds;
    }

    @ManagedAttribute(value = "The selected TLS protocol versions", readonly = true)
    public String[] getSelectedProtocols()
    {
        return Arrays.copyOf(_selectedProtocols, _selectedProtocols.length);
    }

    @ManagedAttribute(value = "The selected cipher suites", readonly = true)
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
    @ManagedAttribute("The excluded TLS protocols")
    public String[] getExcludeProtocols()
    {
        return _excludeProtocols.toArray(new String[0]);
    }

    /**
     * You can either use the exact Protocol name or a a regular expression.
     *
     * @param protocols The array of protocol names to exclude from
     * {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public void setExcludeProtocols(String... protocols)
    {
        _excludeProtocols.clear();
        _excludeProtocols.addAll(Arrays.asList(protocols));
    }

    /**
     * You can either use the exact Protocol name or a a regular expression.
     *
     * @param protocol Protocol name patterns to add to {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public void addExcludeProtocols(String... protocol)
    {
        _excludeProtocols.addAll(Arrays.asList(protocol));
    }

    /**
     * @return The array of protocol name patterns to include in
     * {@link SSLEngine#setEnabledProtocols(String[])}
     */
    @ManagedAttribute("The included TLS protocols")
    public String[] getIncludeProtocols()
    {
        return _includeProtocols.toArray(new String[0]);
    }

    /**
     * You can either use the exact Protocol name or a a regular expression.
     *
     * @param protocols The array of protocol name patterns to include in
     * {@link SSLEngine#setEnabledProtocols(String[])}
     */
    public void setIncludeProtocols(String... protocols)
    {
        _includeProtocols.clear();
        _includeProtocols.addAll(Arrays.asList(protocols));
    }

    /**
     * @return The array of cipher suite name patterns to exclude from
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    @ManagedAttribute("The excluded cipher suites")
    public String[] getExcludeCipherSuites()
    {
        return _excludeCipherSuites.toArray(new String[0]);
    }

    /**
     * You can either use the exact Cipher suite name or a a regular expression.
     *
     * @param cipherSuites The array of cipher suite names to exclude from
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public void setExcludeCipherSuites(String... cipherSuites)
    {
        _excludeCipherSuites.clear();
        _excludeCipherSuites.addAll(Arrays.asList(cipherSuites));
    }

    /**
     * You can either use the exact Cipher suite name or a a regular expression.
     *
     * @param cipher Cipher names to add to {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public void addExcludeCipherSuites(String... cipher)
    {
        _excludeCipherSuites.addAll(Arrays.asList(cipher));
    }

    /**
     * @return The array of Cipher suite names to include in
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    @ManagedAttribute("The included cipher suites")
    public String[] getIncludeCipherSuites()
    {
        return _includeCipherSuites.toArray(new String[0]);
    }

    /**
     * You can either use the exact Cipher suite name or a a regular expression.
     *
     * @param cipherSuites The array of cipher suite names to include in
     * {@link SSLEngine#setEnabledCipherSuites(String[])}
     */
    public void setIncludeCipherSuites(String... cipherSuites)
    {
        _includeCipherSuites.clear();
        _includeCipherSuites.addAll(Arrays.asList(cipherSuites));
    }

    @ManagedAttribute("Whether to respect the cipher suites order")
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
    @ManagedAttribute("The keyStore path")
    public String getKeyStorePath()
    {
        return Objects.toString(_keyStoreResource, null);
    }

    /**
     * @param keyStorePath The file or URL of the SSL Key store.
     */
    public void setKeyStorePath(String keyStorePath)
    {
        if (StringUtil.isBlank(keyStorePath))
        {
            // allow user to unset variable
            _keyStoreResource = null;
            return;
        }

        Resource res = ResourceFactory.of(this).newResource(keyStorePath);
        if (!Resources.isReadable(res))
        {
            _keyStoreResource = null;
            throw new IllegalArgumentException("KeyStore Path not accessible: " + keyStorePath);
        }
        _keyStoreResource = res;
    }

    /**
     * @return The provider of the key store
     */
    @ManagedAttribute("The keyStore provider name")
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
     * @return The type of the key store (default "PKCS12")
     */
    @ManagedAttribute("The keyStore type")
    public String getKeyStoreType()
    {
        return (_keyStoreType);
    }

    /**
     * @param keyStoreType The type of the key store
     */
    public void setKeyStoreType(String keyStoreType)
    {
        _keyStoreType = keyStoreType;
    }

    /**
     * @return Alias of SSL certificate for the connector
     */
    @ManagedAttribute("The certificate alias")
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

    @ManagedAttribute("The trustStore path")
    public String getTrustStorePath()
    {
        return Objects.toString(_trustStoreResource, null);
    }

    /**
     * @param trustStorePath The file name or URL of the trust store location
     */
    public void setTrustStorePath(String trustStorePath)
    {
        if (StringUtil.isBlank(trustStorePath))
        {
            // allow user to unset variable
            _trustStoreResource = null;
            return;
        }

        Resource res = ResourceFactory.of(this).newResource(trustStorePath);
        if (!Resources.isReadable(res))
        {
            _trustStoreResource = null;
            throw new IllegalArgumentException("TrustStore Path not accessible: " + trustStorePath);
        }
        _trustStoreResource = res;
    }

    /**
     * @return The provider of the trust store
     */
    @ManagedAttribute("The trustStore provider name")
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
     * @return The type of the trust store
     */
    @ManagedAttribute("The trustStore type")
    public String getTrustStoreType()
    {
        return _trustStoreType;
    }

    /**
     * @param trustStoreType The type of the trust store
     */
    public void setTrustStoreType(String trustStoreType)
    {
        _trustStoreType = trustStoreType;
    }

    /**
     * @return true if SSL certificate has to be validated
     */
    @ManagedAttribute("Whether certificates are validated")
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
    @ManagedAttribute("Whether peer certificates are validated")
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

    public String getKeyStorePassword()
    {
        return _keyStorePassword == null ? null : _keyStorePassword.toString();
    }

    /**
     * @param password The password for the key store.  If null is passed and
     * a keystore is set, then
     * the {@link #getPassword(String)} is used to
     * obtain a password either from the {@value #PASSWORD_PROPERTY}
     * system property.
     */
    public void setKeyStorePassword(String password)
    {
        _keyStorePassword = password == null ? getPassword(PASSWORD_PROPERTY) : newPassword(password);
    }

    public String getKeyManagerPassword()
    {
        return _keyManagerPassword == null ? null : _keyManagerPassword.toString();
    }

    /**
     * @param password The password (if any) for the specific key within the key store.
     * If null is passed and the {@value #KEYPASSWORD_PROPERTY} system property is set,
     * then the {@link #getPassword(String)} is used to
     * obtain a password from the {@value #KEYPASSWORD_PROPERTY} system property.
     */
    public void setKeyManagerPassword(String password)
    {
        _keyManagerPassword = password == null ? getPassword(KEYPASSWORD_PROPERTY) : newPassword(password);
    }

    /**
     * @param password The password for the truststore. If null is passed then
     * the {@link #getPassword(String)} is used to
     * obtain a password from the {@value #PASSWORD_PROPERTY}
     * system property.
     */
    public void setTrustStorePassword(String password)
    {
        _trustStorePassword = password == null ? getPassword(PASSWORD_PROPERTY) : newPassword(password);
    }

    /**
     * <p>
     * Get the optional Security Provider name.
     * </p>
     * <p>
     * Security Provider name used with:
     * </p>
     * <ul>
     * <li>{@link SecureRandom#getInstance(String, String)}</li>
     * <li>{@link SSLContext#getInstance(String, String)}</li>
     * <li>{@link TrustManagerFactory#getInstance(String, String)}</li>
     * <li>{@link KeyManagerFactory#getInstance(String, String)}</li>
     * <li>{@link CertStore#getInstance(String, CertStoreParameters, String)}</li>
     * <li>{@link java.security.cert.CertificateFactory#getInstance(String, String)}</li>
     * </ul>
     *
     * @return The optional Security Provider name.
     */
    @ManagedAttribute("The provider name")
    public String getProvider()
    {
        return _sslProvider;
    }

    /**
     * <p>
     * Set the optional Security Provider name.
     * </p>
     * <p>
     * Security Provider name used with:
     * </p>
     * <ul>
     * <li>{@link SecureRandom#getInstance(String, String)}</li>
     * <li>{@link SSLContext#getInstance(String, String)}</li>
     * <li>{@link TrustManagerFactory#getInstance(String, String)}</li>
     * <li>{@link KeyManagerFactory#getInstance(String, String)}</li>
     * <li>{@link CertStore#getInstance(String, CertStoreParameters, String)}</li>
     * <li>{@link java.security.cert.CertificateFactory#getInstance(String, String)}</li>
     * </ul>
     *
     * @param provider The optional Security Provider name.
     */
    public void setProvider(String provider)
    {
        _sslProvider = provider;
    }

    /**
     * @return The SSL protocol (default "TLS") passed to
     * {@link SSLContext#getInstance(String, String)}
     */
    @ManagedAttribute("The TLS protocol")
    public String getProtocol()
    {
        return _sslProtocol;
    }

    /**
     * @param protocol The SSL protocol (default "TLS") passed to
     * {@link SSLContext#getInstance(String, String)}
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
    @ManagedAttribute("The SecureRandom algorithm")
    public String getSecureRandomAlgorithm()
    {
        return _secureRandomAlgorithm;
    }

    /**
     * @param algorithm The algorithm name, which if set is passed to
     * {@link SecureRandom#getInstance(String)} to obtain the {@link SecureRandom} instance passed to
     * {@link SSLContext#init(javax.net.ssl.KeyManager[], javax.net.ssl.TrustManager[], SecureRandom)}
     */
    public void setSecureRandomAlgorithm(String algorithm)
    {
        _secureRandomAlgorithm = algorithm;
    }

    /**
     * @return The algorithm name (default "SunX509") used by the {@link KeyManagerFactory}
     */
    @ManagedAttribute("The KeyManagerFactory algorithm")
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
    @ManagedAttribute("The TrustManagerFactory algorithm")
    public String getTrustManagerFactoryAlgorithm()
    {
        return _trustManagerFactoryAlgorithm;
    }

    /**
     * @return True if all certificates should be trusted if there is no KeyStore or TrustStore
     */
    @ManagedAttribute("Whether certificates should be trusted even if they are invalid")
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
     * Use the string "TrustAll" to install a trust manager that trusts all.
     */
    public void setTrustManagerFactoryAlgorithm(String algorithm)
    {
        _trustManagerFactoryAlgorithm = algorithm;
    }

    /**
     * @return whether TLS renegotiation is allowed (true by default)
     */
    @ManagedAttribute("Whether renegotiation is allowed")
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
     * @return The number of renegotiations allowed for this connection.  When the limit
     * is 0 renegotiation will be denied. If the limit is less than 0 then no limit is applied.
     */
    @ManagedAttribute("The max number of renegotiations allowed")
    public int getRenegotiationLimit()
    {
        return _renegotiationLimit;
    }

    /**
     * @param renegotiationLimit The number of renegotions allowed for this connection.
     * When the limit is 0 renegotiation will be denied. If the limit is less than 0 then no limit is applied.
     * Default 5.
     */
    public void setRenegotiationLimit(int renegotiationLimit)
    {
        _renegotiationLimit = renegotiationLimit;
    }

    /**
     * @return Path to file that contains Certificate Revocation List
     */
    @ManagedAttribute("The path to the certificate revocation list file")
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
    @ManagedAttribute("The maximum number of intermediate certificates")
    public int getMaxCertPathLength()
    {
        return _maxCertPathLength;
    }

    /**
     * @param maxCertPathLength maximum number of intermediate certificates in
     * the certification path (-1 for unlimited)
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

        try (AutoLock l = _lock.lock())
        {
            if (_factory == null)
                throw new IllegalStateException("SslContextFactory reload failed");
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
    @ManagedAttribute("The endpoint identification algorithm")
    public String getEndpointIdentificationAlgorithm()
    {
        return _endpointIdentificationAlgorithm;
    }

    /**
     * When set to "HTTPS" hostname verification will be enabled.
     * Deployments can be vulnerable to a man-in-the-middle attack if a EndpointIdentificationAlgorithm
     * is not set.
     *
     * @param endpointIdentificationAlgorithm Set the endpointIdentificationAlgorithm
     * @see #setHostnameVerifier(HostnameVerifier)
     */
    public void setEndpointIdentificationAlgorithm(String endpointIdentificationAlgorithm)
    {
        _endpointIdentificationAlgorithm = endpointIdentificationAlgorithm;
    }

    public PKIXCertPathChecker getPkixCertPathChecker()
    {
        return _pkixCertPathChecker;
    }

    public void setPkixCertPathChecker(PKIXCertPathChecker pkixCertPatchChecker)
    {
        _pkixCertPathChecker = pkixCertPatchChecker;
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
        String storePassword = Objects.toString(_keyStorePassword, null);
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
        String type = Objects.toString(getTrustStoreType(), getKeyStoreType());
        String provider = Objects.toString(getTrustStoreProvider(), getKeyStoreProvider());
        Password passwd = _trustStorePassword;
        if (resource == null || resource.equals(_keyStoreResource))
        {
            resource = _keyStoreResource;
            if (passwd == null)
                passwd = _keyStorePassword;
        }
        return CertificateUtils.getKeyStore(resource, type, provider, Objects.toString(passwd, null));
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
            KeyManagerFactory keyManagerFactory = getKeyManagerFactoryInstance();
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
                PKIXBuilderParameters pbParams = newPKIXBuilderParameters(trustStore, crls);

                TrustManagerFactory trustManagerFactory = getTrustManagerFactoryInstance();
                trustManagerFactory.init(new CertPathTrustManagerParameters(pbParams));

                managers = trustManagerFactory.getTrustManagers();
            }
            else
            {
                TrustManagerFactory trustManagerFactory = getTrustManagerFactoryInstance();
                trustManagerFactory.init(trustStore);

                managers = trustManagerFactory.getTrustManagers();
            }
        }

        return managers;
    }

    // @checkstyle-disable-check : AbbreviationAsWordInNameCheck
    protected PKIXBuilderParameters newPKIXBuilderParameters(KeyStore trustStore, Collection<? extends CRL> crls) throws Exception
    // @checkstyle-enable-check : AbbreviationAsWordInNameCheck
    {
        PKIXBuilderParameters pbParams = new PKIXBuilderParameters(trustStore, new X509CertSelector());

        // Set maximum certification path length
        pbParams.setMaxPathLength(_maxCertPathLength);

        // Make sure revocation checking is enabled
        pbParams.setRevocationEnabled(true);

        if (_pkixCertPathChecker != null)
            pbParams.addCertPathChecker(_pkixCertPathChecker);

        if (crls != null && !crls.isEmpty())
        {
            pbParams.addCertStore(getCertStoreInstance(crls));
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

        return pbParams;
    }

    /**
     * Select protocols to be used by the connector
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported protocols.
     *
     * @param enabledProtocols Array of enabled protocols
     * @param supportedProtocols Array of supported protocols
     */
    public void selectProtocols(String[] enabledProtocols, String[] supportedProtocols)
    {
        List<String> selectedProtocols = processIncludeExcludePatterns("Protocols", enabledProtocols, supportedProtocols, _includeProtocols, _excludeProtocols);

        if (selectedProtocols.isEmpty())
            LOG.warn("No selected Protocols from {}", Arrays.asList(supportedProtocols));

        _selectedProtocols = selectedProtocols.toArray(new String[0]);
    }

    /**
     * Select cipher suites to be used by the connector
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported cipher suite lists.
     *
     * @param enabledCipherSuites Array of enabled cipher suites
     * @param supportedCipherSuites Array of supported cipher suites
     */
    protected void selectCipherSuites(String[] enabledCipherSuites, String[] supportedCipherSuites)
    {
        List<String> selectedCiphers = processIncludeExcludePatterns("Cipher Suite", enabledCipherSuites, supportedCipherSuites, _includeCipherSuites, _excludeCipherSuites);

        if (selectedCiphers.isEmpty())
            LOG.warn("No supported Cipher Suite from {}", Arrays.asList(supportedCipherSuites));

        Comparator<String> comparator = getCipherComparator();
        if (comparator != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Sorting selected ciphers with {}", comparator);
            selectedCiphers.sort(comparator);
        }

        _selectedCipherSuites = selectedCiphers.toArray(new String[0]);
    }

    private List<String> processIncludeExcludePatterns(String type, String[] enabled, String[] supported, Set<String> included, Set<String> excluded)
    {
        List<String> selected = new ArrayList<>();
        // Set the starting list - either from the included or enabled list
        if (included.isEmpty())
        {
            selected.addAll(Arrays.asList(enabled));
        }
        else
        {
            // process include patterns
            for (String includedItem : included)
            {
                Pattern pattern = Pattern.compile(includedItem);
                boolean added = false;
                for (String supportedItem : supported)
                {
                    if (pattern.matcher(supportedItem).matches())
                    {
                        added = true;
                        selected.add(supportedItem);
                    }
                }
                if (!added)
                    LOG.info("No {} matching '{}' is supported", type, includedItem);
            }
        }

        // process exclude patterns
        for (String excludedItem : excluded)
        {
            Pattern pattern = Pattern.compile(excludedItem);
            selected.removeIf(selectedItem -> pattern.matcher(selectedItem).matches());
        }

        return selected;
    }

    /**
     * @deprecated no replacement
     */
    @Deprecated
    protected void processIncludeCipherSuites(String[] supportedCipherSuites, List<String> selectedCiphers)
    {
    }

    /**
     * @deprecated no replacement
     */
    @Deprecated
    protected void removeExcludedCipherSuites(List<String> selectedCiphers)
    {
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
    @ManagedAttribute("Whether certificate revocation list distribution points is enabled")
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
    @ManagedAttribute("Whether online certificate status protocol support is enabled")
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
    @ManagedAttribute("The online certificate status protocol URL")
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

        try (AutoLock l = _lock.lock())
        {
            if (_factory == null)
                throw new IllegalStateException("SslContextFactory reload failed");
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

        try (AutoLock l = _lock.lock())
        {
            if (_factory == null)
                throw new IllegalStateException("SslContextFactory reload failed");
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
    @ManagedAttribute("Whether TLS session caching is enabled")
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
    @ManagedAttribute("The maximum TLS session cache size")
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
     * the JVM default, 0 means unlimited and positive number is a max size.
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
    @ManagedAttribute("The TLS session cache timeout, in seconds")
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
     * the JVM default, 0 means unlimited and positive number is a timeout in seconds.
     */
    public void setSslSessionTimeout(int sslSessionTimeout)
    {
        _sslSessionTimeout = sslSessionTimeout;
    }

    /**
     * @return the HostnameVerifier used by a client to verify host names in the server certificate
     */
    public HostnameVerifier getHostnameVerifier()
    {
        return _hostnameVerifier;
    }

    /**
     * <p>Sets a {@code HostnameVerifier} used by a client to verify host names in the server certificate.</p>
     * <p>The {@code HostnameVerifier} works in conjunction with {@link #setEndpointIdentificationAlgorithm(String)}.</p>
     * <p>When {@code endpointIdentificationAlgorithm=="HTTPS"} (the default) the JDK TLS implementation
     * checks that the host name indication set by the client matches the host names in the server certificate.
     * If this check passes successfully, the {@code HostnameVerifier} is invoked and the application
     * can perform additional checks and allow/deny the connection to the server.</p>
     * <p>When {@code endpointIdentificationAlgorithm==null} the JDK TLS implementation will not check
     * the host names, and any check is therefore performed only by the {@code HostnameVerifier.}</p>
     *
     * @param hostnameVerifier the HostnameVerifier used by a client to verify host names in the server certificate
     */
    public void setHostnameVerifier(HostnameVerifier hostnameVerifier)
    {
        _hostnameVerifier = hostnameVerifier;
    }

    /**
     * Returns the password object for the given realm.
     *
     * @param realm the realm
     * @return the Password object
     */
    protected Password getPassword(String realm)
    {
        String password = System.getProperty(realm);
        return password == null ? null : newPassword(password);
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
            (SSLServerSocket)(host == null
                ? factory.createServerSocket(port, backlog)
                : factory.createServerSocket(port, backlog, InetAddress.getByName(host)));
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

    protected CertificateFactory getCertificateFactoryInstance(String type) throws CertificateException
    {
        String provider = getProvider();

        try
        {
            if (provider != null)
            {
                return CertificateFactory.getInstance(type, provider);
            }
        }
        catch (Throwable cause)
        {
            String msg = String.format("Unable to get CertificateFactory instance for type [%s] on provider [%s], using default", type, provider);
            if (LOG.isDebugEnabled())
                LOG.debug(msg, cause);
            else
                LOG.info(msg);
        }

        return CertificateFactory.getInstance(type);
    }

    protected CertStore getCertStoreInstance(Collection<? extends CRL> crls) throws InvalidAlgorithmParameterException, NoSuchAlgorithmException
    {
        String type = "Collection";
        String provider = getProvider();

        try
        {
            if (provider != null)
            {
                return CertStore.getInstance(type, new CollectionCertStoreParameters(crls), provider);
            }
        }
        catch (Throwable cause)
        {
            String msg = String.format("Unable to get CertStore instance for type [%s] on provider [%s], using default", type, provider);
            if (LOG.isDebugEnabled())
                LOG.debug(msg, cause);
            else
                LOG.info(msg);
        }

        return CertStore.getInstance(type, new CollectionCertStoreParameters(crls));
    }

    protected KeyManagerFactory getKeyManagerFactoryInstance() throws NoSuchAlgorithmException
    {
        String algorithm = getKeyManagerFactoryAlgorithm();
        String provider = getProvider();

        try
        {
            if (provider != null)
            {
                return KeyManagerFactory.getInstance(algorithm, provider);
            }
        }
        catch (Throwable cause)
        {
            // fall back to non-provider option
            String msg = String.format("Unable to get KeyManagerFactory instance for algorithm [%s] on provider [%s], using default", algorithm, provider);
            if (LOG.isDebugEnabled())
                LOG.debug(msg, cause);
            else
                LOG.info(msg);
        }

        return KeyManagerFactory.getInstance(algorithm);
    }

    protected SecureRandom getSecureRandomInstance() throws NoSuchAlgorithmException
    {
        String algorithm = getSecureRandomAlgorithm();

        if (algorithm != null)
        {
            String provider = getProvider();

            try
            {
                if (provider != null)
                {
                    return SecureRandom.getInstance(algorithm, provider);
                }
            }
            catch (Throwable cause)
            {
                String msg = String.format("Unable to get SecureRandom instance for algorithm [%s] on provider [%s], using default", algorithm, provider);
                if (LOG.isDebugEnabled())
                    LOG.debug(msg, cause);
                else
                    LOG.info(msg);
            }

            return SecureRandom.getInstance(algorithm);
        }

        return null;
    }

    protected SSLContext getSSLContextInstance() throws NoSuchAlgorithmException
    {
        String protocol = getProtocol();
        String provider = getProvider();

        try
        {
            if (provider != null)
            {
                return SSLContext.getInstance(protocol, provider);
            }
        }
        catch (Throwable cause)
        {
            String msg = String.format("Unable to get SSLContext instance for protocol [%s] on provider [%s], using default", protocol, provider);
            if (LOG.isDebugEnabled())
                LOG.debug(msg, cause);
            else
                LOG.info(msg);
        }

        return SSLContext.getInstance(protocol);
    }

    protected TrustManagerFactory getTrustManagerFactoryInstance() throws NoSuchAlgorithmException
    {
        String algorithm = getTrustManagerFactoryAlgorithm();
        String provider = getProvider();
        try
        {
            if (provider != null)
            {
                return TrustManagerFactory.getInstance(algorithm, provider);
            }
        }
        catch (Throwable cause)
        {
            String msg = String.format("Unable to get TrustManagerFactory instance for algorithm [%s] on provider [%s], using default", algorithm, provider);
            if (LOG.isDebugEnabled())
                LOG.debug(msg, cause);
            else
                LOG.info(msg);
        }

        return TrustManagerFactory.getInstance(algorithm);
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
        SSLEngine sslEngine = isSessionCachingEnabled()
            ? context.createSSLEngine(host, port)
            : context.createSSLEngine();
        customize(sslEngine);

        return sslEngine;
    }

    /**
     * Server-side only factory method for creating {@link SSLEngine}s.
     * <p>
     * If the given {@code address} is null, it is equivalent to {@link #newSSLEngine()}, otherwise
     * {@link #newSSLEngine(String, int)} is called.
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
        return newSSLEngine(address.getHostString(), address.getPort());
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
            sslParams.setSNIMatchers(List.of(new AliasSNIMatcher()));
        if (_selectedCipherSuites != null)
            sslParams.setCipherSuites(_selectedCipherSuites);
        if (_selectedProtocols != null)
            sslParams.setProtocols(_selectedProtocols);
        if (this instanceof Server)
        {
            Server server = (Server)this;
            if (server.getWantClientAuth())
                sslParams.setWantClientAuth(true);
            if (server.getNeedClientAuth())
                sslParams.setNeedClientAuth(true);
        }
        return sslParams;
    }

    public void reload(Consumer<SslContextFactory> consumer) throws Exception
    {
        try (AutoLock l = _lock.lock())
        {
            consumer.accept(this);
            unload();
            load();
        }
    }

    /**
     * Obtain the X509 Certificate Chain from the provided SSLSession using the
     * default {@link CertificateFactory} behaviors
     *
     * @param sslSession the session to use for active peer certificates
     * @return the certificate chain
     */
    public static X509Certificate[] getCertChain(SSLSession sslSession)
    {
        return getX509CertChain(null, sslSession);
    }

    /**
     * Obtain the X509 Certificate Chain from the provided SSLSession using this
     * SslContextFactory's optional Provider specific {@link CertificateFactory}.
     *
     * @param sslSession the session to use for active peer certificates
     * @return the certificate chain
     */
    public X509Certificate[] getX509CertChain(SSLSession sslSession)
    {
        return getX509CertChain(this, sslSession);
    }

    private static X509Certificate[] getX509CertChain(SslContextFactory sslContextFactory, SSLSession sslSession)
    {
        try
        {
            Certificate[] javaxCerts = sslSession.getPeerCertificates();
            if (javaxCerts == null || javaxCerts.length == 0)
                return null;

            int length = javaxCerts.length;
            X509Certificate[] javaCerts = new X509Certificate[length];

            String type = "X.509";
            CertificateFactory cf;
            if (sslContextFactory != null)
            {
                cf = sslContextFactory.getCertificateFactoryInstance(type);
            }
            else
            {
                cf = CertificateFactory.getInstance(type);
            }

            for (int i = 0; i < length; i++)
            {
                byte[] bytes = javaxCerts[i].getEncoded();
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
            LOG.warn("Unable to get X509CertChain", e);
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

    public void validateCerts(X509Certificate[] certs) throws Exception
    {
        KeyStore trustStore = loadTrustStore(_trustStoreResource);
        Collection<? extends CRL> crls = loadCRL(_crlPath);
        CertificateValidator validator = new CertificateValidator(trustStore, crls);
        validator.validate(certs);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[provider=%s,keyStore=%s,trustStore=%s]",
            getClass().getSimpleName(),
            hashCode(),
            _sslProvider,
            _keyStoreResource,
            _trustStoreResource);
    }

    private static class Factory
    {
        private final KeyStore _keyStore;
        private final KeyStore _trustStore;
        private final SSLContext _context;

        private Factory(KeyStore keyStore, KeyStore trustStore, SSLContext context)
        {
            _keyStore = keyStore;
            _trustStore = trustStore;
            _context = context;
        }
    }

    static class AliasSNIMatcher extends SNIMatcher
    {
        private String _host;

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
                _host = StringUtil.asciiToLowerCase(((SNIHostName)serverName).getAsciiName());
                if (LOG.isDebugEnabled())
                    LOG.debug("SNI host name {}", _host);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("No SNI host name for {}", serverName);
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
    }

    public static class Client extends SslContextFactory
    {
        private SniProvider sniProvider = (sslEngine, serverNames) -> serverNames;

        public Client()
        {
            this(false);
        }

        public Client(boolean trustAll)
        {
            super(trustAll);
        }

        @Override
        protected void checkConfiguration()
        {
            checkTrustAll();
            checkEndPointIdentificationAlgorithm();
            super.checkConfiguration();
        }

        @Override
        public void customize(SSLEngine sslEngine)
        {
            SSLParameters sslParameters = sslEngine.getSSLParameters();
            List<SNIServerName> serverNames = sslParameters.getServerNames();
            if (serverNames == null)
                serverNames = Collections.emptyList();
            List<SNIServerName> newServerNames = getSNIProvider().apply(sslEngine, serverNames);
            if (newServerNames != null && newServerNames != serverNames)
            {
                sslParameters.setServerNames(newServerNames);
                sslEngine.setSSLParameters(sslParameters);
            }
            super.customize(sslEngine);
        }

        /**
         * @return the SNI provider used to customize the SNI
         */
        public SniProvider getSNIProvider()
        {
            return sniProvider;
        }

        /**
         * @param sniProvider the SNI provider used to customize the SNI
         */
        public void setSNIProvider(SniProvider sniProvider)
        {
            this.sniProvider = Objects.requireNonNull(sniProvider);
        }

        /**
         * <p>A provider for SNI names to send to the server during the TLS handshake.</p>
         * <p>By default, the OpenJDK TLS implementation does not send SNI names when
         * they are IP addresses, following what currently specified in
         * <a href="https://datatracker.ietf.org/doc/html/rfc6066#section-3">TLS 1.3</a>,
         * or when they are non-domain strings such as {@code "localhost"}.</p>
         * <p>If you need to send custom SNI, such as a non-domain SNI or an IP address SNI,
         * you can set your own SNI provider or use {@link #NON_DOMAIN_SNI_PROVIDER}.</p>
         */
        @FunctionalInterface
        public interface SniProvider
        {
            /**
             * <p>An SNI provider that, if the given {@code serverNames} list is empty,
             * retrieves the host via {@link SSLEngine#getPeerHost()}, converts it to
             * ASCII bytes, and sends it as SNI.</p>
             * <p>This allows to send non-domain SNI such as {@code "localhost"} or
             * IP addresses.</p>
             */
            public static final SniProvider NON_DOMAIN_SNI_PROVIDER = Client::getSniServerNames;

            /**
             * <p>Provides the SNI names to send to the server.</p>
             * <p>Currently, RFC 6066 allows for different types of server names,
             * but defines only one of type "host_name".</p>
             * <p>As such, the input {@code serverNames} list and the list to be returned
             * contain at most one element.</p>
             *
             * @param sslEngine the SSLEngine that processes the TLS handshake
             * @param serverNames the non-null immutable list of server names computed by implementation
             * @return either the same {@code serverNames} list passed as parameter, or a new list
             * containing the server names to send to the server
             */
            public List<SNIServerName> apply(SSLEngine sslEngine, List<SNIServerName> serverNames);
        }

        private static List<SNIServerName> getSniServerNames(SSLEngine sslEngine, List<SNIServerName> serverNames)
        {
            if (serverNames.isEmpty())
            {
                String host = sslEngine.getPeerHost();
                if (host != null)
                {
                    // Must use the byte[] constructor, because the character ':' is forbidden when
                    // using the String constructor (but typically present in IPv6 addresses).
                    // Since Java 17, only letter|digit|hyphen characters are allowed, even by the byte[] constructor.
                    return List.of(new SNIHostName(host.getBytes(StandardCharsets.US_ASCII)));
                }
            }
            return serverNames;
        }
    }

    @ManagedObject
    public static class Server extends SslContextFactory implements SniX509ExtendedKeyManager.SniSelector
    {
        public static final String SNI_HOST = "org.eclipse.jetty.util.ssl.sniHost";

        private boolean _needClientAuth;
        private boolean _wantClientAuth;
        private boolean _sniRequired;
        private SniX509ExtendedKeyManager.SniSelector _sniSelector;

        public Server()
        {
            setEndpointIdentificationAlgorithm(null);
        }

        /**
         * @return True if SSL needs client authentication.
         * @see SSLEngine#getNeedClientAuth()
         */
        @ManagedAttribute("Whether client authentication is needed")
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
        @ManagedAttribute("Whether client authentication is wanted")
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
         * <p>Returns whether an SNI match is required when choosing the alias that
         * identifies the certificate to send to the client.</p>
         * <p>The exact logic to choose an alias given the SNI is configurable via
         * {@link #setSNISelector(SniX509ExtendedKeyManager.SniSelector)}.</p>
         * <p>The default implementation is {@link #sniSelect(String, Principal[], SSLSession, String, Collection)}
         * and if SNI is not required it will delegate the TLS implementation to
         * choose an alias (typically the first alias in the KeyStore).</p>
         * <p>Note that if a non SNI handshake is accepted, requests may still be rejected
         * at the HTTP level for incorrect SNI (see SecureRequestCustomizer).</p>
         *
         * @return whether an SNI match is required when choosing the alias that identifies the certificate
         */
        @ManagedAttribute("Whether the TLS handshake is rejected if there is no SNI host match")
        public boolean isSniRequired()
        {
            return _sniRequired;
        }

        /**
         * <p>Sets whether an SNI match is required when choosing the alias that
         * identifies the certificate to send to the client.</p>
         * <p>This setting may have no effect if {@link #sniSelect(String, Principal[], SSLSession, String, Collection)} is
         * overridden or a custom function is passed to {@link #setSNISelector(SniX509ExtendedKeyManager.SniSelector)}.</p>
         *
         * @param sniRequired whether an SNI match is required when choosing the alias that identifies the certificate
         */
        public void setSniRequired(boolean sniRequired)
        {
            _sniRequired = sniRequired;
        }

        @Override
        protected KeyManager[] getKeyManagers(KeyStore keyStore) throws Exception
        {
            KeyManager[] managers = super.getKeyManagers(keyStore);

            boolean hasSniX509ExtendedKeyManager = false;

            // Is SNI needed to select a certificate?
            if (isSniRequired() || !wildCerts().isEmpty() || hostCerts().size() > 1 || (hostCerts().size() == 1 && aliasCerts().size() > 1))
            {
                for (int idx = 0; idx < managers.length; idx++)
                {
                    if (managers[idx] instanceof X509ExtendedKeyManager)
                    {
                        managers[idx] = newSniX509ExtendedKeyManager((X509ExtendedKeyManager)managers[idx]);
                        hasSniX509ExtendedKeyManager = true;
                    }
                }
            }

            if (isSniRequired())
            {
                if (managers == null || !hasSniX509ExtendedKeyManager)
                    throw new IllegalStateException("No SNI Key managers when SNI is required");
            }
            return managers;
        }

        /**
         * @return the custom function to select certificates based on SNI information
         */
        public SniX509ExtendedKeyManager.SniSelector getSNISelector()
        {
            return _sniSelector;
        }

        /**
         * <p>Sets a custom function to select certificates based on SNI information.</p>
         *
         * @param sniSelector the selection function
         */
        public void setSNISelector(SniX509ExtendedKeyManager.SniSelector sniSelector)
        {
            _sniSelector = sniSelector;
        }

        @Override
        public String sniSelect(String keyType, Principal[] issuers, SSLSession session, String sniHost, Collection<X509> certificates)
        {
            boolean sniRequired = isSniRequired();

            if (LOG.isDebugEnabled())
                LOG.debug("Selecting alias: keyType={}, sni={}, sniRequired={}, certs={}", keyType, String.valueOf(sniHost), sniRequired, certificates);

            String alias;
            if (sniHost == null)
            {
                // No SNI, so reject or delegate.
                alias = sniRequired ? null : SniX509ExtendedKeyManager.SniSelector.DELEGATE;
            }
            else
            {
                // Match the SNI host.
                List<X509> matching = certificates.stream()
                    .filter(x509 -> x509.matches(sniHost))
                    .collect(Collectors.toList());

                if (matching.isEmpty())
                {
                    // There is no match for this SNI among the certificates valid for
                    // this keyType; check if there is any certificate that matches this
                    // SNI, as we will likely be called again with a different keyType.
                    boolean anyMatching = aliasCerts().values().stream()
                        .anyMatch(x509 -> x509.matches(sniHost));
                    alias = sniRequired || anyMatching ? null : SniX509ExtendedKeyManager.SniSelector.DELEGATE;
                }
                else
                {
                    alias = matching.get(0).getAlias();
                    if (matching.size() > 1)
                    {
                        // Prefer strict matches over wildcard matches.
                        alias = matching.stream()
                            .min(Comparator.comparingInt(cert -> cert.getWilds().size()))
                            .map(X509::getAlias)
                            .orElse(alias);
                    }
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Selected alias={}", String.valueOf(alias));

            return alias;
        }

        protected X509ExtendedKeyManager newSniX509ExtendedKeyManager(X509ExtendedKeyManager keyManager)
        {
            return new SniX509ExtendedKeyManager(keyManager, this);
        }
    }

    /**
     * <p>A wrapper that delegates to another (if not {@code null}) X509ExtendedKeyManager.</p>
     */
    public static class X509ExtendedKeyManagerWrapper extends X509ExtendedKeyManager
    {
        private final X509ExtendedKeyManager keyManager;

        public X509ExtendedKeyManagerWrapper(X509ExtendedKeyManager keyManager)
        {
            this.keyManager = keyManager;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers)
        {
            return keyManager == null ? null : keyManager.getClientAliases(keyType, issuers);
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket)
        {
            return keyManager == null ? null : keyManager.chooseClientAlias(keyType, issuers, socket);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine)
        {
            return keyManager == null ? null : keyManager.chooseEngineClientAlias(keyType, issuers, engine);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers)
        {
            return keyManager == null ? null : keyManager.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket)
        {
            return keyManager == null ? null : keyManager.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine)
        {
            return keyManager == null ? null : keyManager.chooseEngineServerAlias(keyType, issuers, engine);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias)
        {
            return keyManager == null ? null : keyManager.getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias)
        {
            return keyManager == null ? null : keyManager.getPrivateKey(alias);
        }
    }

    /**
     * <p>A wrapper that delegates to another (if not {@code null}) X509ExtendedTrustManager.</p>
     */
    public static class X509ExtendedTrustManagerWrapper extends X509ExtendedTrustManager
    {
        private final X509ExtendedTrustManager trustManager;

        public X509ExtendedTrustManagerWrapper(X509ExtendedTrustManager trustManager)
        {
            this.trustManager = trustManager;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            return trustManager == null ? new X509Certificate[0] : trustManager.getAcceptedIssuers();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException
        {
            if (trustManager != null)
                trustManager.checkClientTrusted(certs, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException
        {
            if (trustManager != null)
                trustManager.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException
        {
            if (trustManager != null)
                trustManager.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException
        {
            if (trustManager != null)
                trustManager.checkServerTrusted(certs, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException
        {
            if (trustManager != null)
                trustManager.checkServerTrusted(chain, authType, socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException
        {
            if (trustManager != null)
                trustManager.checkServerTrusted(chain, authType, engine);
        }
    }
}
