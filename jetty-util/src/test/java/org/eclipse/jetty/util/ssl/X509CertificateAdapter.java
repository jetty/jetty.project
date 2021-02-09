//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Principal;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Set;

/**
 * Bogus X509Certificate to aide in testing
 */
public class X509CertificateAdapter extends X509Certificate
{
    @Override
    public void checkValidity() throws CertificateExpiredException, CertificateNotYetValidException
    {
    }

    @Override
    public void checkValidity(Date date) throws CertificateExpiredException, CertificateNotYetValidException
    {
    }

    @Override
    public byte[] getEncoded() throws CertificateEncodingException
    {
        return new byte[0];
    }

    @Override
    public boolean hasUnsupportedCriticalExtension()
    {
        return false;
    }

    @Override
    public Set<String> getCriticalExtensionOIDs()
    {
        return null;
    }

    @Override
    public Set<String> getNonCriticalExtensionOIDs()
    {
        return null;
    }

    @Override
    public byte[] getExtensionValue(String oid)
    {
        return new byte[0];
    }

    @Override
    public void verify(PublicKey key) throws CertificateException, NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException, SignatureException
    {
    }

    @Override
    public void verify(PublicKey key, String sigProvider) throws CertificateException, NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException, SignatureException
    {
    }

    @Override
    public String toString()
    {
        return null;
    }

    @Override
    public PublicKey getPublicKey()
    {
        return null;
    }

    @Override
    public int getVersion()
    {
        return 0;
    }

    @Override
    public BigInteger getSerialNumber()
    {
        return null;
    }

    @Override
    public Principal getIssuerDN()
    {
        return null;
    }

    @Override
    public Principal getSubjectDN()
    {
        return null;
    }

    @Override
    public Date getNotBefore()
    {
        return null;
    }

    @Override
    public Date getNotAfter()
    {
        return null;
    }

    @Override
    public byte[] getTBSCertificate() throws CertificateEncodingException
    {
        return new byte[0];
    }

    @Override
    public byte[] getSignature()
    {
        return new byte[0];
    }

    @Override
    public String getSigAlgName()
    {
        return null;
    }

    @Override
    public String getSigAlgOID()
    {
        return null;
    }

    @Override
    public byte[] getSigAlgParams()
    {
        return new byte[0];
    }

    @Override
    public boolean[] getIssuerUniqueID()
    {
        return new boolean[0];
    }

    @Override
    public boolean[] getSubjectUniqueID()
    {
        return new boolean[0];
    }

    @Override
    public boolean[] getKeyUsage()
    {
        return new boolean[0];
    }

    @Override
    public int getBasicConstraints()
    {
        return 0;
    }
}
