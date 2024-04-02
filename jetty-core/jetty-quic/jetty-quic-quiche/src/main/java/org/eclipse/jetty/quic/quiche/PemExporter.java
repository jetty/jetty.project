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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PemExporter
{
    private static final Logger LOG = LoggerFactory.getLogger(PemExporter.class);

    private static final byte[] BEGIN_KEY = "-----BEGIN PRIVATE KEY-----".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END_KEY = "-----END PRIVATE KEY-----".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BEGIN_CERT = "-----BEGIN CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END_CERT = "-----END CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LINE_SEPARATOR = System.getProperty("line.separator").getBytes(StandardCharsets.US_ASCII);
    private static final Base64.Encoder ENCODER = Base64.getMimeEncoder(64, LINE_SEPARATOR);

    private PemExporter()
    {
    }

    /**
     * @return a temp file that gets deleted on exit
     */
    public static Path exportTrustStore(KeyStore keyStore, Path targetFolder) throws Exception
    {
        if (!Files.isDirectory(targetFolder))
            throw new IllegalArgumentException("Target folder is not a directory: " + targetFolder);

        Path path = Files.createTempFile(targetFolder, "truststore-", ".crt");
        try (OutputStream os = Files.newOutputStream(path))
        {
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements())
            {
                String alias = aliases.nextElement();
                Certificate cert = keyStore.getCertificate(alias);
                writeAsPEM(os, cert);
            }
        }
        return path;
    }

    /**
     * @return [0] is the key file, [1] is the cert file.
     */
    public static Path[] exportKeyPair(KeyStore keyStore, String alias, char[] keyPassword, Path targetFolder) throws Exception
    {
        if (!Files.isDirectory(targetFolder))
            throw new IllegalArgumentException("Target folder is not a directory: " + targetFolder);

        Path[] paths = new Path[2];
        paths[1] = targetFolder.resolve(alias + ".crt");
        try (OutputStream os = Files.newOutputStream(paths[1]))
        {
            Certificate[] certChain = keyStore.getCertificateChain(alias);
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
        paths[0] = targetFolder.resolve(alias + ".key");
        try (OutputStream os = Files.newOutputStream(paths[0]))
        {
            Key key = keyStore.getKey(alias, keyPassword);
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

    private static void writeAsPEM(OutputStream outputStream, Key key) throws IOException
    {
        byte[] encoded = ENCODER.encode(key.getEncoded());
        outputStream.write(BEGIN_KEY);
        outputStream.write(LINE_SEPARATOR);
        outputStream.write(encoded);
        outputStream.write(LINE_SEPARATOR);
        outputStream.write(END_KEY);
        outputStream.write(LINE_SEPARATOR);
    }

    private static void writeAsPEM(OutputStream outputStream, Certificate certificate) throws CertificateEncodingException, IOException
    {
        byte[] encoded = ENCODER.encode(certificate.getEncoded());
        outputStream.write(BEGIN_CERT);
        outputStream.write(LINE_SEPARATOR);
        outputStream.write(encoded);
        outputStream.write(LINE_SEPARATOR);
        outputStream.write(END_CERT);
        outputStream.write(LINE_SEPARATOR);
    }
}
