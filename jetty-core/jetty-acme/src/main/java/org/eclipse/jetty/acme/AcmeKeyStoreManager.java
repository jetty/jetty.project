//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.acme;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Manages keystore operations for ACME certificate management.</p>
 * <p>This class handles:</p>
 * <ul>
 *   <li>Account key pair generation and storage</li>
 *   <li>Domain key pair generation</li>
 *   <li>Certificate Signing Request (CSR) generation</li>
 *   <li>Keystore creation and certificate storage</li>
 *   <li>Self-signed certificate generation for dry-run mode</li>
 * </ul>
 *
 * <p>This implementation uses only standard Java crypto APIs.</p>
 */
public class AcmeKeyStoreManager
{
    private static final Logger LOG = LoggerFactory.getLogger(AcmeKeyStoreManager.class);
    private static final String RSA_ALGORITHM = "RSA";
    private static final int RSA_KEY_SIZE = 2048;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String DEFAULT_ALIAS = "jetty";

    private final Path basePath;

    /**
     * Creates a new keystore manager.
     *
     * @param basePath the base path for resolving relative paths
     */
    public AcmeKeyStoreManager(Path basePath)
    {
        this.basePath = basePath != null ? basePath : Path.of(".");
    }

    /**
     * Loads or generates an account key pair.
     *
     * @param accountKeyPath the path to the account key file
     * @return the account key pair
     * @throws AcmeException if loading or generation fails
     */
    public KeyPair loadOrGenerateAccountKey(Path accountKeyPath) throws AcmeException
    {
        Path resolvedPath = resolvePath(accountKeyPath);

        try
        {
            if (Files.exists(resolvedPath))
            {
                return loadKeyPair(resolvedPath);
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Generating new account key pair at {}", resolvedPath);

            KeyPair keyPair = generateKeyPair();
            saveKeyPair(keyPair, resolvedPath);
            return keyPair;
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to load or generate account key", e);
        }
    }

    /**
     * Generates a new domain key pair.
     *
     * @return the generated key pair
     * @throws AcmeException if generation fails
     */
    public KeyPair generateDomainKeyPair() throws AcmeException
    {
        try
        {
            return generateKeyPair();
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to generate domain key pair", e);
        }
    }

    /**
     * Generates a Certificate Signing Request (CSR) in DER format.
     *
     * @param keyPair the domain key pair
     * @param domains the domain names to include
     * @return the DER-encoded CSR
     * @throws AcmeException if CSR generation fails
     */
    public byte[] generateCSR(KeyPair keyPair, List<String> domains) throws AcmeException
    {
        if (domains.isEmpty())
            throw new AcmeException("At least one domain is required");

        try
        {
            String primaryDomain = domains.get(0);

            // Build CSR using DER encoding directly
            ByteArrayOutputStream csrDer = new ByteArrayOutputStream();

            // Build CertificationRequestInfo
            ByteArrayOutputStream certReqInfo = new ByteArrayOutputStream();

            // Version (INTEGER 0)
            certReqInfo.write(new byte[]{0x02, 0x01, 0x00});

            // Subject (CN=domain)
            byte[] subjectDer = buildSubjectDN(primaryDomain);
            certReqInfo.write(subjectDer);

            // SubjectPublicKeyInfo
            byte[] publicKeyInfo = keyPair.getPublic().getEncoded();
            certReqInfo.write(publicKeyInfo);

            // Attributes with SAN extension
            byte[] attributes = buildCSRAttributes(domains);
            certReqInfo.write(attributes);

            byte[] certReqInfoBytes = certReqInfo.toByteArray();

            // Sign the CertificationRequestInfo
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initSign(keyPair.getPrivate());
            sig.update(wrapSequence(certReqInfoBytes));
            byte[] signature = sig.sign();

            // Build final CSR: SEQUENCE { certificationRequestInfo, signatureAlgorithm, signature }
            ByteArrayOutputStream csr = new ByteArrayOutputStream();

            // CertificationRequestInfo (as SEQUENCE)
            csr.write(wrapSequence(certReqInfoBytes));

            // SignatureAlgorithm (SHA256withRSA OID: 1.2.840.113549.1.1.11)
            byte[] sigAlgId = new byte[]{
                0x30, 0x0d,
                // OID
                0x06, 0x09, 0x2a, (byte)0x86, 0x48, (byte)0x86, (byte)0xf7, 0x0d, 0x01, 0x01, 0x0b,
                // NULL parameters
                0x05, 0x00
            };
            csr.write(sigAlgId);

            // Signature (BIT STRING)
            csr.write(wrapBitString(signature));

            return wrapSequence(csr.toByteArray());
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to generate CSR", e);
        }
    }

    /**
     * Stores a certificate chain in the keystore.
     *
     * @param keystorePath the keystore path
     * @param password the keystore password
     * @param privateKey the private key
     * @param certificates the certificate chain
     * @throws AcmeException if storage fails
     */
    public void storeCertificates(Path keystorePath, String password, PrivateKey privateKey,
                                  List<X509Certificate> certificates) throws AcmeException
    {
        Path resolvedPath = resolvePath(keystorePath);

        try
        {
            // Ensure parent directories exist
            Files.createDirectories(resolvedPath.getParent());

            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            keyStore.load(null, null);

            // Convert list to array
            Certificate[] chain = certificates.toArray(new Certificate[0]);

            // Store private key with certificate chain
            keyStore.setKeyEntry(DEFAULT_ALIAS, privateKey, password.toCharArray(), chain);

            // Save keystore
            try (OutputStream os = Files.newOutputStream(resolvedPath))
            {
                keyStore.store(os, password.toCharArray());
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Stored certificate chain in {}", resolvedPath);
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to store certificates", e);
        }
    }

    /**
     * Loads the current certificate from the keystore.
     *
     * @param keystorePath the keystore path
     * @param password the keystore password
     * @return the certificate, or null if not found
     * @throws AcmeException if loading fails
     */
    public X509Certificate loadCertificate(Path keystorePath, String password) throws AcmeException
    {
        Path resolvedPath = resolvePath(keystorePath);

        if (!Files.exists(resolvedPath))
            return null;

        try
        {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            keyStore.load(Files.newInputStream(resolvedPath), password.toCharArray());

            Certificate cert = keyStore.getCertificate(DEFAULT_ALIAS);
            if (cert instanceof X509Certificate x509)
                return x509;

            return null;
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to load certificate", e);
        }
    }

    /**
     * Generates a self-signed certificate for dry-run mode.
     *
     * @param keyPair the key pair to use
     * @param domains the domain names
     * @param validityDays the validity period in days
     * @return the self-signed certificate
     * @throws AcmeException if generation fails
     */
    public X509Certificate generateSelfSignedCertificate(KeyPair keyPair, List<String> domains, int validityDays)
        throws AcmeException
    {
        if (domains.isEmpty())
            throw new AcmeException("At least one domain is required");

        try
        {
            String primaryDomain = domains.get(0);

            Instant now = Instant.now();
            Date notBefore = Date.from(now);
            Date notAfter = Date.from(now.plus(validityDays, ChronoUnit.DAYS));

            // Build TBSCertificate
            ByteArrayOutputStream tbsCert = new ByteArrayOutputStream();

            // Version [0] EXPLICIT INTEGER { v3(2) }
            tbsCert.write(new byte[]{(byte)0xa0, 0x03, 0x02, 0x01, 0x02});

            // Serial number
            byte[] serialBytes = BigInteger.valueOf(now.toEpochMilli()).toByteArray();
            tbsCert.write(wrapInteger(serialBytes));

            // Signature algorithm (SHA256withRSA)
            byte[] sigAlgId = new byte[]{
                0x30, 0x0d,
                0x06, 0x09, 0x2a, (byte)0x86, 0x48, (byte)0x86, (byte)0xf7, 0x0d, 0x01, 0x01, 0x0b,
                0x05, 0x00
            };
            tbsCert.write(sigAlgId);

            // Issuer (same as subject for self-signed)
            byte[] subjectDer = buildSubjectDN(primaryDomain);
            tbsCert.write(subjectDer);

            // Validity
            tbsCert.write(buildValidity(notBefore, notAfter));

            // Subject
            tbsCert.write(subjectDer);

            // SubjectPublicKeyInfo
            tbsCert.write(keyPair.getPublic().getEncoded());

            // Extensions [3] (SAN)
            byte[] extensions = buildCertExtensions(domains);
            tbsCert.write(extensions);

            byte[] tbsCertBytes = wrapSequence(tbsCert.toByteArray());

            // Sign the TBSCertificate
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initSign(keyPair.getPrivate());
            sig.update(tbsCertBytes);
            byte[] signature = sig.sign();

            // Build final certificate
            ByteArrayOutputStream cert = new ByteArrayOutputStream();
            cert.write(tbsCertBytes);
            cert.write(sigAlgId);
            cert.write(wrapBitString(signature));

            byte[] certDer = wrapSequence(cert.toByteArray());

            // Parse as X509Certificate
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate)cf.generateCertificate(new java.io.ByteArrayInputStream(certDer));
        }
        catch (Exception e)
        {
            throw new AcmeException("Failed to generate self-signed certificate", e);
        }
    }

    private KeyPair generateKeyPair() throws Exception
    {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        keyGen.initialize(RSA_KEY_SIZE, new SecureRandom());
        return keyGen.generateKeyPair();
    }

    private KeyPair loadKeyPair(Path path) throws Exception
    {
        byte[] keyBytes = Files.readAllBytes(path);

        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance(RSA_ALGORITHM);
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

        // Derive public key from private key
        RSAPrivateCrtKey rsaPrivateKey = (RSAPrivateCrtKey)privateKey;
        RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(
            rsaPrivateKey.getModulus(),
            rsaPrivateKey.getPublicExponent()
        );

        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);

        return new KeyPair(publicKey, privateKey);
    }

    private void saveKeyPair(KeyPair keyPair, Path path) throws IOException
    {
        Files.createDirectories(path.getParent());
        byte[] keyBytes = keyPair.getPrivate().getEncoded();
        Files.write(path, keyBytes);
    }

    private Path resolvePath(Path path)
    {
        if (path.isAbsolute())
            return path;
        return basePath.resolve(path);
    }

    // ASN.1 DER encoding helpers

    private byte[] buildSubjectDN(String commonName) throws IOException
    {
        // CN attribute: OID 2.5.4.3
        byte[] cnOid = new byte[]{0x06, 0x03, 0x55, 0x04, 0x03};
        byte[] cnValue = wrapUtf8String(commonName);

        ByteArrayOutputStream attrTypeValue = new ByteArrayOutputStream();
        attrTypeValue.write(cnOid);
        attrTypeValue.write(cnValue);

        byte[] attrSeq = wrapSequence(attrTypeValue.toByteArray());
        byte[] rdnSet = wrapSet(attrSeq);

        return wrapSequence(rdnSet);
    }

    private byte[] buildCSRAttributes(List<String> domains) throws IOException
    {
        // Build SAN extension
        ByteArrayOutputStream sanValues = new ByteArrayOutputStream();
        for (String domain : domains)
        {
            // dNSName [2] IA5String
            byte[] dnsName = domain.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            sanValues.write(0x82);
            sanValues.write(encodeLength(dnsName.length));
            sanValues.write(dnsName);
        }
        byte[] sanSeq = wrapSequence(sanValues.toByteArray());

        // Extension: OID 2.5.29.17 (subjectAltName), not critical, value
        byte[] sanOid = new byte[]{0x06, 0x03, 0x55, 0x1d, 0x11};
        byte[] sanOctetString = wrapOctetString(sanSeq);

        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        ext.write(sanOid);
        ext.write(sanOctetString);
        byte[] extSeq = wrapSequence(ext.toByteArray());
        byte[] extensions = wrapSequence(extSeq);

        // extensionRequest OID: 1.2.840.113549.1.9.14
        byte[] extReqOid = new byte[]{0x06, 0x09, 0x2a, (byte)0x86, 0x48, (byte)0x86, (byte)0xf7, 0x0d, 0x01, 0x09, 0x0e};

        ByteArrayOutputStream attr = new ByteArrayOutputStream();
        attr.write(extReqOid);
        attr.write(wrapSet(extensions));

        byte[] attrSeq = wrapSequence(attr.toByteArray());

        // Attributes [0] IMPLICIT
        return wrapContextTag(0, attrSeq);
    }

    private byte[] buildValidity(Date notBefore, Date notAfter) throws IOException
    {
        java.text.SimpleDateFormat utcFormat = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
        utcFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

        byte[] notBeforeBytes = wrapUtcTime(utcFormat.format(notBefore));
        byte[] notAfterBytes = wrapUtcTime(utcFormat.format(notAfter));

        ByteArrayOutputStream validity = new ByteArrayOutputStream();
        validity.write(notBeforeBytes);
        validity.write(notAfterBytes);

        return wrapSequence(validity.toByteArray());
    }

    private byte[] buildCertExtensions(List<String> domains) throws IOException
    {
        // Basic Constraints: CA=false
        byte[] bcOid = new byte[]{0x06, 0x03, 0x55, 0x1d, 0x13};
        byte[] bcValue = wrapOctetString(wrapSequence(new byte[0]));

        ByteArrayOutputStream bcExt = new ByteArrayOutputStream();
        bcExt.write(bcOid);
        bcExt.write(new byte[]{0x01, 0x01, (byte)0xff});
        bcExt.write(bcValue);
        byte[] bcExtSeq = wrapSequence(bcExt.toByteArray());

        // Subject Alternative Name
        ByteArrayOutputStream sanValues = new ByteArrayOutputStream();
        for (String domain : domains)
        {
            byte[] dnsName = domain.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            sanValues.write(0x82);
            sanValues.write(encodeLength(dnsName.length));
            sanValues.write(dnsName);
        }
        byte[] sanSeq = wrapSequence(sanValues.toByteArray());

        byte[] sanOid = new byte[]{0x06, 0x03, 0x55, 0x1d, 0x11};
        byte[] sanOctetString = wrapOctetString(sanSeq);

        ByteArrayOutputStream sanExt = new ByteArrayOutputStream();
        sanExt.write(sanOid);
        sanExt.write(sanOctetString);
        byte[] sanExtSeq = wrapSequence(sanExt.toByteArray());

        ByteArrayOutputStream allExts = new ByteArrayOutputStream();
        allExts.write(bcExtSeq);
        allExts.write(sanExtSeq);

        byte[] extsSeq = wrapSequence(allExts.toByteArray());

        // Extensions [3] EXPLICIT
        return wrapContextTag(3, extsSeq);
    }

    private byte[] wrapSequence(byte[] content)
    {
        return wrapTag(0x30, content);
    }

    private byte[] wrapSet(byte[] content)
    {
        return wrapTag(0x31, content);
    }

    private byte[] wrapOctetString(byte[] content)
    {
        return wrapTag(0x04, content);
    }

    private byte[] wrapBitString(byte[] content)
    {
        // BIT STRING with 0 unused bits
        byte[] result = new byte[content.length + 1];
        result[0] = 0;
        System.arraycopy(content, 0, result, 1, content.length);
        return wrapTag(0x03, result);
    }

    private byte[] wrapInteger(byte[] content)
    {
        return wrapTag(0x02, content);
    }

    private byte[] wrapUtf8String(String s)
    {
        return wrapTag(0x0c, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private byte[] wrapUtcTime(String time)
    {
        return wrapTag(0x17, time.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private byte[] wrapContextTag(int tagNumber, byte[] content)
    {
        return wrapTag(0xa0 | tagNumber, content);
    }

    private byte[] wrapTag(int tag, byte[] content)
    {
        byte[] lengthBytes = encodeLength(content.length);
        byte[] result = new byte[1 + lengthBytes.length + content.length];
        result[0] = (byte)tag;
        System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.length);
        System.arraycopy(content, 0, result, 1 + lengthBytes.length, content.length);
        return result;
    }

    private byte[] encodeLength(int length)
    {
        if (length < 128)
        {
            return new byte[]{(byte)length};
        }
        else if (length < 256)
        {
            return new byte[]{(byte)0x81, (byte)length};
        }
        else if (length < 65536)
        {
            return new byte[]{(byte)0x82, (byte)(length >> 8), (byte)length};
        }
        else
        {
            return new byte[]{(byte)0x83, (byte)(length >> 16), (byte)(length >> 8), (byte)length};
        }
    }
}
