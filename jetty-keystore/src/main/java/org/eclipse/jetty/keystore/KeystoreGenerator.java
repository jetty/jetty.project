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

package org.eclipse.jetty.keystore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.jetty.util.security.Password;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KeystoreGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(KeystoreGenerator.class);

    @SuppressWarnings("unused")
    public static File generateTestKeystore(String location, String password) throws Exception
    {
        LOG.warn("Generating Test Keystore: DO NOT USE IN PRODUCTION!");

        // Generate an RSA key pair.
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        // Create a self-signed certificate.
        Instant start = Instant.now().minus(Duration.ofDays(1));
        Date notBefore = Date.from(start);
        Date notAfter = Date.from(start.plus(Duration.ofDays(365)));
        BigInteger serial = BigInteger.valueOf(new SecureRandom().nextLong());
        X500Name x500Name = new X500Name("C=US,ST=NE,L=Omaha,O=Webtide,OU=Jetty,CN=localhost");
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(x500Name, serial, notBefore, notAfter, x500Name, keyPair.getPublic());
        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider(new BouncyCastleProvider()).getCertificate(certBuilder.build(contentSigner));

        // Create a keystore using the self-signed certificate.
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        char[] pwdCharArray = new Password(password).toString().toCharArray();
        keystore.load(null, pwdCharArray);
        keystore.setKeyEntry("jetty-test-keystore", keyPair.getPrivate(), pwdCharArray, new Certificate[]{certificate});

        // Write keystore out to a file.
        File keystoreFile = new File(location);
        keystoreFile.deleteOnExit();
        File parentFile = keystoreFile.getAbsoluteFile().getParentFile();
        if (!parentFile.exists() && !parentFile.mkdirs())
            throw new IOException("Could not create directory for test keystore file");
        try (FileOutputStream fos = new FileOutputStream(keystoreFile))
        {
            keystore.store(fos, pwdCharArray);
        }
        return keystoreFile;
    }
}
