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

package org.eclipse.jetty.quic.quiche;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.util.Base64;
import java.util.Set;

import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SSLKeyPair
{
    private static final Logger LOG = LoggerFactory.getLogger(SSLKeyPair.class);

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

    public SSLKeyPair(KeyStore keyStore, String alias, char[] keyPassword) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException
    {
        this.alias = alias;
        this.key = keyStore.getKey(alias, keyPassword);
        this.certChain = keyStore.getCertificateChain(alias);
    }

    /**
     * @return [0] is the key file, [1] is the cert file.
     */
    public Path[] export(Path targetFolder) throws Exception
    {
        if (!Files.isDirectory(targetFolder))
            throw new IllegalArgumentException("Target folder is not a directory: " + targetFolder);

        Path[] paths = new Path[2];
        paths[0] = targetFolder.resolve(alias + ".key");
        paths[1] = targetFolder.resolve(alias + ".crt");

        try (OutputStream os = Files.newOutputStream(paths[1]))
        {
            for (Certificate cert : certChain)
                writeAsPEM(os, cert);
            Files.setPosixFilePermissions(paths[1], Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        catch (UnsupportedOperationException e)
        {
            // Expected on Windows.
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to set Posix file permissions", e);
        }
        try (OutputStream os = Files.newOutputStream(paths[0]))
        {
            writeAsPEM(os, key);
            Files.setPosixFilePermissions(paths[0], Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        catch (UnsupportedOperationException e)
        {
            // Expected on Windows.
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to set Posix file permissions", e);
        }

        return paths;
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
