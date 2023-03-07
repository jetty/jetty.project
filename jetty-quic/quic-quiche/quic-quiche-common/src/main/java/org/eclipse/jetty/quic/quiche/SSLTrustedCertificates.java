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

import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SSLTrustedCertificates {

    private final Set<Certificate> trustedCertificates;

    public SSLTrustedCertificates(KeyStore trustStore) throws KeyStoreException, IOException{
            trustedCertificates = getTrustedCertificates(trustStore);
    }

    private Set<Certificate> getTrustedCertificates(KeyStore trustStore) throws KeyStoreException {
        Set<Certificate> trustedCertificates = Collections.newSetFromMap(new ConcurrentHashMap<>());

        for (Iterator<String> it = trustStore.aliases().asIterator(); it.hasNext();) {
            String certificateAlias = it.next();

            Certificate[] certificateChain = trustStore.getCertificateChain(certificateAlias);
            if (certificateChain != null) {
                Collections.addAll(trustedCertificates, certificateChain);
                continue;
            }

            trustedCertificates.add(trustStore.getCertificate(certificateAlias));
        }

        return trustedCertificates;
    }

    public File export(File targetFolder) throws IOException {
        File trustStoreFile = new File(targetFolder, "trustedCertificates.pem");
        try(FileWriter fileWriter = new FileWriter(trustStoreFile)) {
            writeAsPEM(fileWriter, trustedCertificates);
        }

        return trustStoreFile;
    }

    private void writeAsPEM(FileWriter fileWriter, Set<Certificate> certificates) throws IOException
    {
        try(JcaPEMWriter pemWriter = new JcaPEMWriter(fileWriter)) {
            for (Certificate certificate : certificates) {
                pemWriter.writeObject(certificate);
            }
        }
    }
}
