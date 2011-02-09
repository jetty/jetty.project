package org.eclipse.jetty.util.security;

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

import java.security.KeyStore;
import java.security.KeyStoreException;
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

import org.eclipse.jetty.util.log.Log;

/**
 * Convenience class to handle validation of certificates, aliases and keystores
 *
 * Currently handles certificate revocation lists, should evolve to handle ocsp as well
 * 
 * TODO: consider the case of a null trust store, is that important?
 * TODO: add what support for ocsp is needed, if any
 */
public class CertificateValidator
{
    private KeyStore _trustStore;
    private Collection<? extends CRL> _crls;
    private int _maxCertPathLength = -1;
    
    /**
     * creates an instance of the certificate validator 
     *
     * @param trustStore 
     * @param crls
     */
    public CertificateValidator(KeyStore trustStore, Collection<? extends CRL> crls)
    {
        _trustStore = trustStore;
        _crls = crls;
    }
    
    /**
     * validates all aliases inside of a given keystore
     * 
     * @param keyStore
     * @throws CertificateException
     */
    public void validate( KeyStore keyStore ) throws CertificateException
    {
        try
        {
            Enumeration<String> aliases = keyStore.aliases();
            
            for ( ; aliases.hasMoreElements(); )
            {
                String alias = aliases.nextElement();
                
                validate(keyStore,alias);
            }
            
        }
        catch ( KeyStoreException kse )
        {
            throw new CertificateException("error obtaining aliases", kse);
        }
    }
    

    /**
     * validates a specific alias inside of the keystore being passed in
     * 
     * @param keyStore
     * @param keyAlias
     * @return
     * @throws CertificateException
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
            catch (KeyStoreException ex)
            {
                Log.debug(ex);
                throw new CertificateException("Unable to validate certificate for alias [" +
                                               keyAlias + "]: " + ex.getMessage());
            }
            result = keyAlias;            
        }
        
        return result;
    }
    
    /**
     * validates a specific certificate inside of the keystore being passed in
     * 
     * @param keyStore
     * @param cert
     * @throws CertificateException
     */
    public void validate(KeyStore keyStore, Certificate cert) throws CertificateException
    {
        if (cert != null && cert instanceof X509Certificate)
        {
            ((X509Certificate)cert).checkValidity();
            
            String certAlias = "[none]";
            try
            {
                certAlias = keyStore.getCertificateAlias((X509Certificate)cert);
                Certificate[] certChain = keyStore.getCertificateChain(certAlias);
                   
                ArrayList<X509Certificate> certList = new ArrayList<X509Certificate>();
                for (Certificate item : certChain)
                {
                    if (!(item instanceof X509Certificate))
                    {
                        throw new CertificateException("Invalid certificate type in chain");
                    }
                    certList.add((X509Certificate)item);
                }

                if (certList.isEmpty())
                {
                    throw new CertificateException("Invalid certificate chain");
                    
                }

                X509CertSelector certSelect = new X509CertSelector();
                certSelect.setCertificate(certList.get(0));
                
                // Configure certification path builder parameters
                PKIXBuilderParameters pbParams = new PKIXBuilderParameters(_trustStore, certSelect);
                pbParams.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(certList)));

                // Set static Certificate Revocation List
                if (_crls != null && !_crls.isEmpty())
                {
                    pbParams.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(_crls)));
                }

                // Enable revocation checking
                pbParams.setRevocationEnabled(true);

                // Set maximum certification path length
                pbParams.setMaxPathLength(_maxCertPathLength);
 
                // Build certification path
                CertPathBuilderResult buildResult = CertPathBuilder.getInstance("PKIX").build(pbParams);               
                
                // Validate certification path
                CertPathValidator.getInstance("PKIX").validate(buildResult.getCertPath(),pbParams);
            }
            catch (Exception ex)
            {
                Log.debug(ex);
                throw new CertificateException("Unable to validate certificate for alias [" +
                                               certAlias + "]: " + ex.getMessage());
            }
        } 
    }

    public int getMaxCertPathLength()
    {
        return _maxCertPathLength;
    }

    public void setMaxCertPathLength(int maxCertPathLength)
    {
        _maxCertPathLength = maxCertPathLength;
    }

    public KeyStore getTrustStore()
    {
        return _trustStore;
    }

    public Collection<? extends CRL> getCrls()
    {
        return _crls;
    }
}
