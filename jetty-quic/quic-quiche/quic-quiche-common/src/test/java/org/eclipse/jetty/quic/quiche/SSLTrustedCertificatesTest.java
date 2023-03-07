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

import org.eclipse.jetty.quic.quiche.SSLTrustedCertificates;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

class SSLTrustedCertificatesTest {

    private SSLTrustedCertificates sut;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setup() throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        try(InputStream trustStoreInputStream = this.getClass().getResourceAsStream(
                "/certs/trustStore.jks")) {
            trustStore.load(trustStoreInputStream, "password".toCharArray());
        }
        sut = new SSLTrustedCertificates(trustStore);
    }

    @Test
    void testExport() throws IOException, URISyntaxException {

        Path expectedPath = Paths.get(this.getClass()
                .getResource("/certs/intermediate_ca/intermediate_ca.pem").toURI());
        String expected = Files.readString(expectedPath);
        
        File file = sut.export(tempDir.toFile());
        String actual = Files.readString(file.toPath());
        
        Assertions.assertEquals(expected, actual);
    }
}
