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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import org.eclipse.jetty.quic.quiche.SSLKeyPair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SSLKeyPairTest {

    private SSLKeyPair sut;
    
    @TempDir
    private Path tempDir;

    @BeforeEach
    void setup() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try(InputStream keyStoreInputStream = this.getClass().getResourceAsStream(
            "/certs/client/client.p12")) {
            keyStore.load(keyStoreInputStream, "password".toCharArray());
        }
        sut = new SSLKeyPair(keyStore, "client", "password".toCharArray());
        
    }

    @Test
    void testExport() throws Exception {
        Path expectedKeyPath = Paths.get(this.getClass().getResource("/certs/client/client.key").toURI());
        String expectedKey = Files.readString(expectedKeyPath);
        
        Path expectedCertificatePath = Paths.get(this.getClass().getResource("/certs/client/client.pem").toURI());
        String expectedCertificate = Files.readString(expectedCertificatePath);

        File[] actual = sut.export(tempDir.toFile());
        
        String actualKey = Files.readString(actual[0].toPath());
        String actualCertificate = Files.readString(actual[1].toPath());

        Assertions.assertEquals(expectedKey, actualKey);
        Assertions.assertEquals(expectedCertificate, actualCertificate);
    }
}
