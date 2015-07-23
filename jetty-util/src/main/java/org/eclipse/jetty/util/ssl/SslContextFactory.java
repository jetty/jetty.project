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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
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
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

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

/**
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

    /*
     * @see {@link X509Certificate#getKeyUsage()}
     */
    private static final int KEY_USAGE__KEY_CERT_SIGN=5;

    /*
     *
     * @see {@link X509Certificate#getSubjectAlternativeNames()}
     */
    private static final int SUBJECT_ALTERNATIVE_NAMES__DNS_NAME=2;


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
    private final Set<String> _includeProtocols = new LinkedHashSet<>();

    /** Selected protocols. */
    private String[] _selectedProtocols;
    
    /** Excluded cipher suites. */
    private final Set<String> _excludeCipherSuites = new LinkedHashSet<>();

    /** Included cipher suites. */
    private final List<String> _includeCipherSuites = new ArrayList<String>();
    private boolean _useCipherSuitesOrder=true;
    
    /** Cipher comparator for ordering ciphers */
    Comparator<String> _cipherComparator;
    
    /** Selected cipher suites. Combination of includes, excludes, available and ordering */
    private String[] _selectedCipherSuites;

    /** Keystore path. */
    private Resource _keyStoreResource;
    /** Keystore provider name */
    private String _keyStoreProvider;
    /** Keystore type */
    private String _keyStoreType = "JKS";

    /** SSL certificate alias */
    private String _certAlias;
    private final Map<String,String> _certAliases = new HashMap<>();
    private final Map<String,String> _certWilds = new HashMap<>();

    /** Truststore path */
    private Resource _trustStoreResource;
    /** Truststore provider name */
    private String _trustStoreProvider;
    /** Truststore type */
    private String _trustStoreType = "JKS";

    /** Set to true if client certificate authentication is required */
    private boolean _needClientAuth = false;
    /** Set to true if client certificate authentication is desired */
    private boolean _wantClientAuth = false;

    /** Keystore password */
    private Password _keyStorePassword;
    /** Key manager password */
    private Password _keyManagerPassword;
    /** Truststore password */
    private Password _trustStorePassword;

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
    private KeyStore _setKeyStore;
    /** SSL truststore */
    private KeyStore _setTrustStore;
    /** Set to true to enable SSL Session caching */
    private boolean _sessionCachingEnabled = true;
    /** SSL session cache size */
    private int _sslSessionCacheSize;
    /** SSL session timeout */
    private int _sslSessionTimeout;

    /** SSL context */
    private SSLContext _setContext;

    /** EndpointIdentificationAlgorithm - when set to "HTTPS" hostname verification will be enabled */
    private String _endpointIdentificationAlgorithm = null;

    /** Whether to blindly trust certificates */
    private boolean _trustAll;

    /** Whether TLS renegotiation is allowed */
    private boolean _renegotiationAllowed = true;

    protected Factory _factory;




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
        addExcludeProtocols("SSL", "SSLv2", "SSLv2Hello", "SSLv3");
        setExcludeCipherSuites(
                "SSL_RSA_WITH_DES_CBC_SHA",
                "SSL_DHE_RSA_WITH_DES_CBC_SHA",
                "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
                "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
                "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");
}

    /**
     * Construct an instance of SslContextFactory
     * @param keyStorePath default keystore location
     */
    public SslContextFactory(String keyStorePath)
    {
        setKeyStorePath(keyStorePath);
    }

    public String[] getSelectedProtocols()
    {
        return Arrays.copyOf(_selectedProtocols,_selectedProtocols.length);
    }

    public String[] getSelectedCipherSuites()
    {
        return Arrays.copyOf(_selectedCipherSuites,_selectedCipherSuites.length);
    }

    public Comparator<String> getCipherComparator()
    {
        return _cipherComparator;
    }

    public void setCipherComparator(Comparator<String> cipherComparator)
    {
        if (cipherComparator!=null)
            setUseCipherSuitesOrder(true);
        _cipherComparator = cipherComparator;
    }

    /**
     * Create the SSLContext object and start the lifecycle
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        SSLContext context = _setContext;
        KeyStore keyStore = _setKeyStore;
        KeyStore trustStore = _setTrustStore;

        if (context == null)
        {
            // Is this an empty factory?
            if (keyStore==null && _keyStoreResource == null && trustStore==null && _trustStoreResource == null )
            {
                TrustManager[] trust_managers=null;

                if (_trustAll)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No keystore or trust store configured.  ACCEPTING UNTRUSTED CERTIFICATES!!!!!");
                    // Create a trust manager that does not validate certificate chains
                    trust_managers = TRUST_ALL_CERTS;
                }

                SecureRandom secureRandom = (_secureRandomAlgorithm == null)?null:SecureRandom.getInstance(_secureRandomAlgorithm);
                context = _sslProvider == null ? SSLContext.getInstance(_sslProtocol) : SSLContext.getInstance(_sslProtocol, _sslProvider);
                context.init(null, trust_managers, secureRandom);
            }
            else
            {
                if (keyStore==null)
                    keyStore=loadKeyStore(_keyStoreResource);
                if (trustStore==null)
                    trustStore=loadTrustStore(_trustStoreResource);

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

                // Look for X.509 certificates to create alias map
                _certAliases.clear();
                if (keyStore!=null)
                {
                    for (String alias : Collections.list(keyStore.aliases()))
                    {
                        Certificate certificate = keyStore.getCertificate(alias);
                        if ("X.509".equals(certificate.getType()))
                        {
                            X509Certificate x509 = (X509Certificate)certificate;

                            // Exclude certificates with special uses
                            if (x509.getKeyUsage()!=null)
                            {
                                boolean[] b=x509.getKeyUsage();
                                if (b[KEY_USAGE__KEY_CERT_SIGN])
                                    continue;
                            }

                            // Look for alternative name extensions
                            boolean named=false;
                            Collection<List<?>> altNames = x509.getSubjectAlternativeNames();
                            if (altNames!=null)
                            {
                                for (List<?> list : altNames)
                                {
                                    if (((Number)list.get(0)).intValue() == SUBJECT_ALTERNATIVE_NAMES__DNS_NAME)
                                    {
                                        String cn = list.get(1).toString();
                                        if (LOG.isDebugEnabled())
                                            LOG.debug("Certificate SAN alias={} cn={} in {}",alias,cn,this);
                                        if (cn!=null)
                                        {
                                            named=true;
                                            _certAliases.put(cn,alias);
                                        }
                                    }
                                }
                            }

                            // If no names found, look up the cn from the subject
                            if (!named)
                            {
                                LdapName name=new LdapName(x509.getSubjectX500Principal().getName(X500Principal.RFC2253));
                                for (Rdn rdn : name.getRdns())
                                {
                                    if (rdn.getType().equalsIgnoreCase("cn"))
                                    {
                                        String cn = rdn.getValue().toString();
                                        if (LOG.isDebugEnabled())
                                            LOG.debug("Certificate cn alias={} cn={} in {}",alias,cn,this);
                                        if (cn!=null && cn.contains(".") && !cn.contains(" "))
                                            _certAliases.put(cn,alias);
                                    }
                                }
                            }
                        }
                    }
                }

                // find wild aliases
                _certWilds.clear();
                for (String name : _certAliases.keySet())
                    if (name.startsWith("*."))
                        _certWilds.put(name.substring(1),_certAliases.get(name));

                LOG.info("x509={} wild={} alias={} for {}",_certAliases,_certWilds,_certAlias,this);

                // Instantiate key and trust managers
                KeyManager[] keyManagers = getKeyManagers(keyStore);
                TrustManager[] trustManagers = getTrustManagers(trustStore,crls);

                // Initialize context
                SecureRandom secureRandom = (_secureRandomAlgorithm == null)?null:SecureRandom.getInstance(_secureRandomAlgorithm);
                context = _sslProvider == null ? SSLContext.getInstance(_sslProtocol) : SSLContext.getInstance(_sslProtocol, _sslProvider);
                context.init(keyManagers,trustManagers,secureRandom);
            }
        }
        
        // select the protocols and ciphers
        SSLEngine sslEngine=context.createSSLEngine();
        selectCipherSuites(
                sslEngine.getEnabledCipherSuites(),
                sslEngine.getSupportedCipherSuites());
        selectProtocols(sslEngine.getEnabledProtocols(),sslEngine.getSupportedProtocols());

        _factory = new Factory(keyStore,trustStore,context);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Selected Protocols {} of {}",Arrays.asList(_selectedProtocols),Arrays.asList(sslEngine.getSupportedProtocols()));
            LOG.debug("Selected Ciphers   {} of {}",Arrays.asList(_selectedCipherSuites),Arrays.asList(sslEngine.getSupportedCipherSuites()));
        }

    }

    @Override
    protected void doStop() throws Exception
    {
        _factory = null;
        super.doStop();
        _certAliases.clear();
        _certWilds.clear();
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
        _includeProtocols.clear();
        _includeProtocols.addAll(Arrays.asList(protocols));
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
     * @param keyStorePath
     *            The file or URL of the SSL Key store.
     */
    public void setKeyStorePath(String keyStorePath)
    {
        checkNotStarted();
        try
        {
            _keyStoreResource = Resource.newResource(keyStorePath);
        }
        catch (MalformedURLException e)
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
     * Set the default certificate Alias.
     * <p>This can be used if there are multiple non-SNI certificates
     * to specify the certificate that should be used, or with SNI
     * certificates to set a certificate to try if no others match
     * </p>
     * @param certAlias
     *            Alias of SSL certificate for the connector
     */
    public void setCertAlias(String certAlias)
    {
        checkNotStarted();
        _certAlias = certAlias;
    }

    /**
     * @param trustStorePath
     *            The file name or URL of the trust store location
     */
    public void setTrustStorePath(String trustStorePath)
    {
        checkNotStarted();
        try
        {
            _trustStoreResource = Resource.newResource(trustStorePath);
        }
        catch (MalformedURLException e)
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
     *            The password for the key store.  If null is passed then
     *            the {@link Password#getPassword(String, String, String)} is used to
     *            obtain a password either from the "org.eclipse.jetty.ssl.password"
     *            System property or by prompting for manual entry.
     */
    public void setKeyStorePassword(String password)
    {
        checkNotStarted();

        _keyStorePassword = password==null
            ?Password.getPassword(PASSWORD_PROPERTY,null,null)
            :new Password(password);
    }

    /**
     * @param password
     *            The password (if any) for the specific key within the key store.
     *            If null is passed then
     *            the {@link Password#getPassword(String, String, String)} is used to
     *            obtain a password either from the "org.eclipse.jetty.ssl.keypassword"
     *            System property or by prompting for manual entry.
     */
    public void setKeyManagerPassword(String password)
    {
        checkNotStarted();
        _keyManagerPassword = password==null
            ?Password.getPassword(KEYPASSWORD_PROPERTY,null,null)
            :new Password(password);
    }

    /**
     * @param password
     *            The password for the trust store. If null is passed then
     *            the {@link Password#getPassword(String, String, String)} is used to
     *            obtain a password either from the "org.eclipse.jetty.ssl.password"
     *            System property or by prompting for manual entry.
     */
    public void setTrustStorePassword(String password)
    {
        checkNotStarted();
        _trustStorePassword = password==null
            ?Password.getPassword(PASSWORD_PROPERTY,null,null)
            :new Password(password);
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
        return isStarted()?_factory._context:_setContext;
    }

    /**
     * @param sslContext
     *            Set a preconfigured SSLContext
     */
    public void setSslContext(SSLContext sslContext)
    {
        checkNotStarted();
        _setContext = sslContext;
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
     * @param resource the resource to load the keystore from
     * @return the key store instance
     * @throws Exception if the keystore cannot be loaded
     */
    protected KeyStore loadKeyStore(Resource resource) throws Exception
    {
        return CertificateUtils.getKeyStore(resource, _keyStoreType, _keyStoreProvider,_keyStorePassword==null? null:_keyStorePassword.toString());
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
        String type=_trustStoreType;
        String provider= _trustStoreProvider;
        String passwd=_trustStorePassword==null? null:_trustStorePassword.toString();
        if (resource==null || resource.equals(_keyStoreResource))
        {
            resource=_keyStoreResource;
            if (type==null)
                type=_keyStoreType;
            if (provider==null)
                provider= _keyStoreProvider;
            if (passwd==null)
                passwd=_keyStorePassword==null? null:_keyStorePassword.toString();
        }
            
        return CertificateUtils.getKeyStore(resource,type,provider,passwd);
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

            if (managers!=null)
            {
                if (_certAlias != null)
                {
                    for (int idx = 0; idx < managers.length; idx++)
                    {
                        if (managers[idx] instanceof X509ExtendedKeyManager)
                            managers[idx] = new AliasedX509ExtendedKeyManager((X509ExtendedKeyManager)managers[idx],_certAlias);
                    }
                }

                if (_certAliases.isEmpty() || !_certWilds.isEmpty())
                {
                    for (int idx = 0; idx < managers.length; idx++)
                    {
                        if (managers[idx] instanceof X509ExtendedKeyManager)
                            managers[idx]=new SniX509ExtendedKeyManager((X509ExtendedKeyManager)managers[idx],getCertAlias());
                    }
                }
            }
        }

        LOG.debug("managers={} for {}",managers,this);

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
     * Select protocols to be used by the connector
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported protocols.
     * @param enabledProtocols Array of enabled protocols
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
                if(Arrays.asList(supportedProtocols).contains(protocol))
                    selected_protocols.add(protocol);
                else
                    LOG.info("Protocol {} not supported in {}",protocol,Arrays.asList(supportedProtocols));
            }
        }
        else
            selected_protocols.addAll(Arrays.asList(enabledProtocols));


        // Remove any excluded protocols
        selected_protocols.removeAll(_excludeProtocols);


        if (selected_protocols.isEmpty())
            LOG.warn("No selected protocols from {}",Arrays.asList(supportedProtocols));
        
        _selectedProtocols = selected_protocols.toArray(new String[selected_protocols.size()]);
        
        
        
    }

    /**
     * Select cipher suites to be used by the connector
     * based on configured inclusion and exclusion lists
     * as well as enabled and supported cipher suite lists.
     * @param enabledCipherSuites Array of enabled cipher suites
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
            LOG.warn("No supported ciphers from {}",Arrays.asList(supportedCipherSuites));
        
        if (_cipherComparator!=null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Sorting selected ciphers with {}",_cipherComparator);
            Collections.sort(selected_ciphers,_cipherComparator);
        }
        
        _selectedCipherSuites=selected_ciphers.toArray(new String[selected_ciphers.size()]);
    }

    protected void processIncludeCipherSuites(String[] supportedCipherSuites, List<String> selected_ciphers)
    {
        for (String cipherSuite : _includeCipherSuites)
        {
            Pattern p = Pattern.compile(cipherSuite);
            boolean added=false;
            for (String supportedCipherSuite : supportedCipherSuites)
            {
                Matcher m = p.matcher(supportedCipherSuite);
                if (m.matches())
                {
                    added=true;
                    selected_ciphers.add(supportedCipherSuite);
                }
                
            }
            if (!added)
                LOG.info("No Cipher matching '{}' is supported",cipherSuite);
        }
    }

    protected void removeExcludedCipherSuites(List<String> selected_ciphers)
    {
        for (String excludeCipherSuite : _excludeCipherSuites)
        {
            Pattern excludeCipherPattern = Pattern.compile(excludeCipherSuite);
            for (Iterator<String> i=selected_ciphers.iterator();i.hasNext();)
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
    protected void checkNotStarted()
    {
        if (isStarted())
            throw new IllegalStateException("Cannot modify configuration when "+getState());
    }

    /**
     * Check if the lifecycle has been started and throw runtime exception
     */
    protected void checkIsStarted()
    {
        if (!isStarted())
            throw new IllegalStateException("!STARTED: "+this);
    }

    /**
     * Check if the lifecycle has been started and throw runtime exception
     */
    protected void checkIsRunning()
    {
        if (!isRunning())
            throw new IllegalStateException("!RUNNING: "+this);
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
        _setKeyStore = keyStore;
    }

    public KeyStore getKeyStore()
    {
        return isStarted()?_factory._keyStore:_setKeyStore;
    }

    /** Set the trust store.
     * @param trustStore the trust store to set
     */
    public void setTrustStore(KeyStore trustStore)
    {
        checkNotStarted();
        _setTrustStore = trustStore;
    }

    public KeyStore getTrustStore()
    {
        return isStarted()?_factory._trustStore:_setTrustStore;
    }

    /** Set the key store resource.
     * @param resource the key store resource to set
     */
    public void setKeyStoreResource(Resource resource)
    {
        checkNotStarted();
        _keyStoreResource=resource;
    }

    public Resource getKeyStoreResource()
    {
        return _keyStoreResource;
    }

    /** Set the trust store resource.
     * @param resource the trust store resource to set
     */
    public void setTrustStoreResource(Resource resource)
    {
        checkNotStarted();
        _trustStoreResource=resource;
    }

    public Resource getTrustStoreResource()
    {
        return _keyStoreResource;
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
        checkIsStarted();

        SSLServerSocketFactory factory = _factory._context.getServerSocketFactory();

        SSLServerSocket socket =
            (SSLServerSocket) (host==null ?
                        factory.createServerSocket(port,backlog):
                        factory.createServerSocket(port,backlog,InetAddress.getByName(host)));

        if (getWantClientAuth())
            socket.setWantClientAuth(getWantClientAuth());
        if (getNeedClientAuth())
            socket.setNeedClientAuth(getNeedClientAuth());

        socket.setEnabledCipherSuites(_selectedCipherSuites);
        socket.setEnabledProtocols(_selectedProtocols);

        return socket;
    }

    public SSLSocket newSslSocket() throws IOException
    {
        checkIsStarted();
        
        SSLSocketFactory factory = _factory._context.getSocketFactory();

        SSLSocket socket = (SSLSocket)factory.createSocket();

        if (getWantClientAuth())
            socket.setWantClientAuth(getWantClientAuth());
        if (getNeedClientAuth())
            socket.setNeedClientAuth(getNeedClientAuth());

        socket.setEnabledCipherSuites(_selectedCipherSuites);
        socket.setEnabledProtocols(_selectedProtocols);

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
        checkIsRunning();
        SSLEngine sslEngine=_factory._context.createSSLEngine();
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
        SSLEngine sslEngine=isSessionCachingEnabled()
            ? _factory._context.createSSLEngine(host, port)
            : _factory._context.createSSLEngine();
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

    public void customize(SSLEngine sslEngine)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Customize {}",sslEngine);
        
        SSLParameters sslParams = sslEngine.getSSLParameters();
        sslParams.setEndpointIdentificationAlgorithm(_endpointIdentificationAlgorithm);
        sslParams.setUseCipherSuitesOrder(_useCipherSuitesOrder);
        if (!_certAliases.isEmpty() || !_certWilds.isEmpty())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Enable SNI matching {}",sslEngine);
            sslParams.setSNIMatchers(Collections.singletonList((SNIMatcher)new AliasSNIMatcher()));
        }
        sslParams.setCipherSuites(_selectedCipherSuites);
        sslParams.setProtocols(_selectedProtocols);
        
        if (getWantClientAuth())
            sslParams.setWantClientAuth(true);
        if (getNeedClientAuth())
            sslParams.setNeedClientAuth(true);

        sslEngine.setSSLParameters(sslParams);          
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
                _keyStoreResource,
                _trustStoreResource);
    }



    protected class Factory
    {
        final KeyStore _keyStore;
        final KeyStore _trustStore;
        final SSLContext _context;

        public Factory(KeyStore keyStore, KeyStore trustStore, SSLContext context)
        {
            super();
            _keyStore = keyStore;
            _trustStore = trustStore;
            _context = context;
        }

        @Override
        public String toString()
        {
            return String.format("SslFactory@%x{%s}",System.identityHashCode(this),SslContextFactory.this);
        }
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
                if (_certAliases.size()==0)
                    return true;

                // Try an exact match
                _alias = _certAliases.get(_name.getAsciiName());
                if (_alias!=null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("matched {}->{}",_name.getAsciiName(),_alias);
                    return true;
                }

                // Try wild card matches
                String domain = _name.getAsciiName();
                int dot=domain.indexOf('.');
                if (dot>=0)
                {
                    domain=domain.substring(dot);
                    _alias = _certWilds.get(domain);
                    if (_alias!=null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("wild match {}->{}",_name.getAsciiName(),_alias);
                        return true;
                    }
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug("No match for {}",_name.getAsciiName());

            // Return true and allow the KeyManager to accept or reject when choosing a certificate.
            return true;
        }

        public String getAlias()
        {
            return _alias;
        }

        public String getServerName()
        {
            return _name==null?null:_name.getAsciiName();
        }
    }
}
