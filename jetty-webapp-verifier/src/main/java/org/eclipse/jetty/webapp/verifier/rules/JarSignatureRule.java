// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.webapp.verifier.rules;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.webapp.verifier.AbstractRule;

/**
 * Signed Jar Verifier
 */
public class JarSignatureRule extends AbstractRule
{
    private String _keystoreLocation = System.getProperty("java.home") + "/lib/security/cacerts";
    private String _type = "JKS"; // default
    private String _alias = "verisignclass3ca"; // default
    private KeyStore _keystore;

    private static X509Certificate[] _trustedCertificates;

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.webapp.verifier.AbstractRule#getDescription()
     */
    public String getDescription()
    {   
        return "verifies that the given keystore contains the certificates required for all jar files present";
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.webapp.verifier.AbstractRule#getName()
     */
    public String getName()
    {
        return "jar-signature";
    }

    /* ------------------------------------------------------------ */
    /**
     * @param jar
     * @return
     */
    private List<JarEntry> resolveJar(JarFile jar)
    {
        List<JarEntry> entries = new ArrayList<JarEntry>();

        Enumeration<JarEntry> e = jar.entries();

        while (e.hasMoreElements())
        {
            JarEntry jarEntry = e.nextElement();
            try
            {
                entries.add(jarEntry); // for further verification
                IO.toString(jar.getInputStream(jar.getEntry(jarEntry.getName())));
            }
            catch (IOException e1)
            {
                throw new SecurityException(e1);
            }
        }

        return entries;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the _keystore.
     * 
     * @param keystore
     *            the _keystore to set
     */
    public void setKeystoreLocation( String keyStoreLocation )
    {
        _keystoreLocation = keyStoreLocation;
    }

    @Override
    public void initialize()
    {
        try
        {
            _keystore = KeyStore.getInstance(_type);

            InputStream istream = new File( _keystoreLocation ).toURL().openStream();

            _keystore.load(istream,null);

            _trustedCertificates = new X509Certificate[]
            { (X509Certificate)_keystore.getCertificate(_alias) };

        }
        catch (Throwable t)
        {
            exception(_keystoreLocation, t.getMessage(), t);
        }
    }

    public void setType(String type)
    {
        _type = type;
    }

    /**
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitWebInfLibJar(java.lang.String, java.io.File, java.util.jar.JarFile)
     */
    @Override
    public void visitWebInfLibJar(String path, File archive, JarFile jar)
    {

        try
        {
            if (jar.getManifest() == null)
            {
                error(jar.toString(),"missing manifest.mf, can not be signed");
            }

            List<JarEntry> entries = resolveJar(jar);

            for (JarEntry jarEntry : entries)
            {
                if (!jarEntry.isDirectory() && !jarEntry.getName().startsWith("META-INF"))
                {
                    Certificate[] certs = jarEntry.getCertificates();

                    if (certs == null || certs.length == 0)
                    {
                        error(jarEntry.getName(),"entry has not been signed");
                    }
                    else
                    {
                        X509Certificate[] chainRoots = getChainRoots(certs);
                        boolean signed = false;

                        for (int i = 0; i < chainRoots.length; i++)
                        {
                            if (isTrusted(chainRoots[i]))
                            {
                                signed = true;
                                break;
                            }
                        }
                        if (!signed)
                        {
                            error(jarEntry.getName(),"Untrusted provider's JAR");
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            exception(jar.getName(), e.getMessage(), e);
        }
    }

    private boolean isTrusted(X509Certificate certificate)
    {
        for (int i = 0; i < _trustedCertificates.length; i++)
        {
            if (certificate.getSubjectDN().equals(_trustedCertificates[i].getSubjectDN()))
            {
                if (certificate.equals(_trustedCertificates[i]))
                {
                    return true;
                }
            }
        }

        for (int i = 0; i < _trustedCertificates.length; i++)
        {
            if (certificate.getIssuerDN().equals(_trustedCertificates[i].getSubjectDN()))
            {
                try
                {
                    certificate.verify(_trustedCertificates[i].getPublicKey());
                    return true;
                }
                catch (Exception e)
                {
                }
            }
        }
        return false;
    }

    /**
     * Returns a array of certificates with the root certificate of each chain
     * 
     * @param certificates an array of X509 certificate's
     * @return an array of X509 certificate's with the root certificate of each chain
     */
    private X509Certificate[] getChainRoots(Certificate[] certificates)
    {
        List<X509Certificate> chainRoots = new ArrayList<X509Certificate>();
        
        for (int i = 0; i < certificates.length - 1; i++)
        {
            if (!((X509Certificate)certificates[i + 1]).getSubjectDN().equals(((X509Certificate)certificates[i]).getIssuerDN()))
            {
                chainRoots.add((X509Certificate)certificates[i]);
            }
        }

        chainRoots.add((X509Certificate)certificates[certificates.length - 1]);
        
        return chainRoots.toArray(new X509Certificate[chainRoots.size()]);
    }

}
