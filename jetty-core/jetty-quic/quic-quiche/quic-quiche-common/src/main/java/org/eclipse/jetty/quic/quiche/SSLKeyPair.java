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

package org.eclipse.jetty.quic.quiche;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.util.Base64;

public class SSLKeyPair
{
    private static final byte[] BEGIN_KEY = "-----BEGIN PRIVATE KEY-----".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END_KEY = "-----END PRIVATE KEY-----".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BEGIN_CERT = "-----BEGIN CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END_CERT = "-----END CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LINE_SEPARATOR = System.getProperty("line.separator").getBytes(StandardCharsets.US_ASCII);
    private static final int LINE_LENGTH = 64;

    private final Base64.Encoder encoder = Base64.getMimeEncoder(LINE_LENGTH, LINE_SEPARATOR);
    private final Key key;
    private final Certificate[] certChain;
    private final String alias;

    public SSLKeyPair(File storeFile, String storeType, char[] storePassword, String alias, char[] keyPassword) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, IOException, CertificateException
    {
        KeyStore keyStore = KeyStore.getInstance(storeType);
        try (FileInputStream fis = new FileInputStream(storeFile))
        {
            keyStore.load(fis, storePassword);
            this.alias = alias;
            this.key = keyStore.getKey(alias, keyPassword);
            this.certChain = keyStore.getCertificateChain(alias);
        }
    }

    /**
     * @return [0] is the key file, [1] is the cert file.
     */
    public File[] export(File targetFolder) throws Exception
    {
        File[] files = new File[2];
        files[0] = new File(targetFolder, alias + ".key");
        files[1] = new File(targetFolder, alias + ".crt");

        try (FileOutputStream fos = new FileOutputStream(files[0]))
        {
            writeAsPEM(fos, key);
        }
        try (FileOutputStream fos = new FileOutputStream(files[1]))
        {
            for (Certificate cert : certChain)
                writeAsPEM(fos, cert);
        }
        return files;
    }

    private void writeAsPEM(OutputStream outputStream, Key key) throws IOException
    {
        byte[] encoded = encoder.encode(key.getEncoded());
        outputStream.write(BEGIN_KEY);
        outputStream.write(LINE_SEPARATOR);
        outputStream.write(encoded);
        outputStream.write(LINE_SEPARATOR);
        outputStream.write(END_KEY);
        outputStream.write(LINE_SEPARATOR);
    }

    private void writeAsPEM(OutputStream outputStream, Certificate certificate) throws CertificateEncodingException, IOException
    {
        byte[] encoded = encoder.encode(certificate.getEncoded());
        outputStream.write(BEGIN_CERT);
        outputStream.write(LINE_SEPARATOR);
        outputStream.write(encoded);
        outputStream.write(LINE_SEPARATOR);
        outputStream.write(END_CERT);
        outputStream.write(LINE_SEPARATOR);
    }
}
