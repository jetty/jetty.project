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

package org.eclipse.jetty.util.security;

import java.security.GeneralSecurityException;
import java.security.InvalidParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Security;
import java.security.cert.CRL;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderResult;
import java.security.cert.CertPathValidator;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convenience class to handle validation of certificates, aliases and keystores
 *
 * Allows specifying Certificate Revocation List (CRL), as well as enabling
 * CRL Distribution Points Protocol (CRLDP) certificate extension support,
 * and also enabling On-Line Certificate Status Protocol (OCSP) support.
 *
 * IMPORTANT: at least one of the above mechanisms *MUST* be configured and
 * operational, otherwise certificate validation *WILL FAIL* unconditionally.
 */
public class CertificateValidator
{
    private static final Logger LOG = LoggerFactory.getLogger(CertificateValidator.class);
    private static AtomicLong __aliasCount = new AtomicLong();

    private KeyStore _trustStore;
    private Collection<? extends CRL> _crls;

    /**
     * Maximum certification path length (n - number of intermediate certs, -1 for unlimited)
     */
    private int _maxCertPathLength = -1;
    /**
     * CRL Distribution Points (CRLDP) support
     */
    private boolean _enableCRLDP = false;
    /**
     * On-Line Certificate Status Protocol (OCSP) support
     */
    private boolean _enableOCSP = false;
    /**
     * Location of OCSP Responder
     */
    private String _ocspResponderURL;

    /**
     * creates an instance of the certificate validator
     *
     * @param trustStore the truststore to use
     * @param crls the Certificate Revocation List to use
     */
    public CertificateValidator(KeyStore trustStore, Collection<? extends CRL> crls)
    {
        if (trustStore == null)
        {
            throw new InvalidParameterException("TrustStore must be specified for CertificateValidator.");
        }

        _trustStore = trustStore;
        _crls = crls;
    }

    /**
     * validates all aliases inside of a given keystore
     *
     * @param keyStore the keystore to validate
     * @throws CertificateException if keystore error and unable to validate
     */
    public void validate(KeyStore keyStore) throws CertificateException
    {
        try
        {
            Enumeration<String> aliases = keyStore.aliases();

            for (; aliases.hasMoreElements(); )
            {
                String alias = aliases.nextElement();

                validate(keyStore, alias);
            }
        }
        catch (KeyStoreException kse)
        {
            throw new CertificateException("Unable to retrieve aliases from keystore", kse);
        }
    }

    /**
     * validates a specific alias inside of the keystore being passed in
     *
     * @param keyStore the keystore to validate
     * @param keyAlias the keyalias in the keystore to valid with
     * @return the keyAlias if valid
     * @throws CertificateException if keystore error and unable to validate
     */
    public String validate(KeyStore keyStore, String keyAlias) throws CertificateException
    {
        String result = null;

        if (keyAlias != null)
        {
            try
            {
                validate(keyStore, keyStore.getCertificate(keyAlias));
            }
            catch (KeyStoreException kse)
            {
                LOG.debug("Unable to validate alias: {}", keyAlias, kse);
                throw new CertificateException("Unable to validate certificate" +
                    " for alias [" + keyAlias + "]: " + kse.getMessage(), kse);
            }
            result = keyAlias;
        }

        return result;
    }

    /**
     * validates a specific certificate inside of the keystore being passed in
     *
     * @param keyStore the keystore to validate against
     * @param cert the certificate to validate
     * @throws CertificateException if keystore error and unable to validate
     */
    public void validate(KeyStore keyStore, Certificate cert) throws CertificateException
    {
        Certificate[] certChain = null;

        if (cert != null && cert instanceof X509Certificate)
        {
            ((X509Certificate)cert).checkValidity();

            String certAlias = null;
            try
            {
                if (keyStore == null)
                {
                    throw new InvalidParameterException("Keystore cannot be null");
                }

                certAlias = keyStore.getCertificateAlias((X509Certificate)cert);
                if (certAlias == null)
                {
                    certAlias = "JETTY" + String.format("%016X", __aliasCount.incrementAndGet());
                    keyStore.setCertificateEntry(certAlias, cert);
                }

                certChain = keyStore.getCertificateChain(certAlias);
                if (certChain == null || certChain.length == 0)
                {
                    throw new IllegalStateException("Unable to retrieve certificate chain");
                }
            }
            catch (KeyStoreException kse)
            {
                LOG.debug("Unable to validate certificate", kse);
                throw new CertificateException("Unable to validate certificate" +
                    (certAlias == null ? "" : " for alias [" + certAlias + "]") + ": " + kse.getMessage(), kse);
            }

            validate(certChain);
        }
    }

    public void validate(Certificate[] certChain) throws CertificateException
    {
        try
        {
            ArrayList<X509Certificate> certList = new ArrayList<X509Certificate>();
            for (Certificate item : certChain)
            {
                if (item == null)
                    continue;

                if (!(item instanceof X509Certificate))
                {
                    throw new IllegalStateException("Invalid certificate type in chain");
                }

                certList.add((X509Certificate)item);
            }

            if (certList.isEmpty())
            {
                throw new IllegalStateException("Invalid certificate chain");
            }

            X509CertSelector certSelect = new X509CertSelector();
            certSelect.setCertificate(certList.get(0));

            // Configure certification path builder parameters
            PKIXBuilderParameters pbParams = new PKIXBuilderParameters(_trustStore, certSelect);
            pbParams.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(certList)));

            // Set maximum certification path length
            pbParams.setMaxPathLength(_maxCertPathLength);

            // Enable revocation checking
            pbParams.setRevocationEnabled(true);

            // Set static Certificate Revocation List
            if (_crls != null && !_crls.isEmpty())
            {
                pbParams.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(_crls)));
            }

            // Enable On-Line Certificate Status Protocol (OCSP) support
            if (_enableOCSP)
            {
                Security.setProperty("ocsp.enable", "true");
            }
            // Enable Certificate Revocation List Distribution Points (CRLDP) support
            if (_enableCRLDP)
            {
                System.setProperty("com.sun.security.enableCRLDP", "true");
            }

            // Build certification path
            CertPathBuilderResult buildResult = CertPathBuilder.getInstance("PKIX").build(pbParams);

            // Validate certification path
            CertPathValidator.getInstance("PKIX").validate(buildResult.getCertPath(), pbParams);
        }
        catch (GeneralSecurityException gse)
        {
            LOG.debug("Unable to validate certificate chain", gse);
            throw new CertificateException("Unable to validate certificate: " + gse.getMessage(), gse);
        }
    }

    public KeyStore getTrustStore()
    {
        return _trustStore;
    }

    public Collection<? extends CRL> getCrls()
    {
        return _crls;
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
     * the certification path (-1 for unlimited)
     */
    public void setMaxCertPathLength(int maxCertPathLength)
    {
        _maxCertPathLength = maxCertPathLength;
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
}
