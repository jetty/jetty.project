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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Set;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SSLKeyPair
{
    private final Key key;
    private final Certificate[] certChain;
    private final String alias;

    public SSLKeyPair(File storeFile, String storeType, char[] storePassword, String alias, char[] keyPassword) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, IOException, CertificateException
    {
        this(loadKeyStore(storeFile, storeType, storePassword),
                alias,
                keyPassword);
    }

    public SSLKeyPair(SslContextFactory sslContextFactory)
            throws UnrecoverableKeyException, KeyStoreException, NoSuchAlgorithmException {
        this(sslContextFactory.getKeyStore(),
                getAlias(sslContextFactory),
                sslContextFactory.getKeyManagerPassword() == null ?
                        sslContextFactory.getKeyStorePassword().toCharArray() :
                        sslContextFactory.getKeyManagerPassword().toCharArray());
    }

    public SSLKeyPair(KeyStore keyStore, String alias, char[] keyPassword) throws UnrecoverableKeyException, KeyStoreException, NoSuchAlgorithmException {
        this.alias = alias;
        this.key = keyStore.getKey(alias, keyPassword);
        this.certChain = keyStore.getCertificateChain(alias);
    }

    private static KeyStore loadKeyStore(File storeFile, String storeType, char[] storePassword)
            throws CertificateException, IOException, KeyStoreException, NoSuchAlgorithmException {
        KeyStore keyStore = KeyStore.getInstance(storeType);
        try (FileInputStream fis = new FileInputStream(storeFile)) {
            keyStore.load(fis, storePassword);
        }
        return keyStore;
    }

    private static String getAlias(SslContextFactory sslContextFactory) {
        Set<String> aliases = sslContextFactory.getAliases();
        if (aliases.isEmpty()) {
            throw new IllegalStateException("Invalid KeyStore: no aliases");
        }
        String alias = sslContextFactory.getCertAlias();
        if (alias == null) {
            alias = aliases.stream().findFirst().orElse("mykey");
        }
        return alias;
    }

    /**
     * @return [0] is the key file, [1] is the cert file.
     */
    public File[] export(File targetFolder) throws Exception
    {
        File[] files = new File[2];
        files[0] = new File(targetFolder, alias + ".key");
        files[1] = new File(targetFolder, alias + ".crt");

        try (FileWriter fileWriter = new FileWriter(files[0]))
        {
            writeAsPEM(fileWriter, key);
        }
        try (FileWriter fileWriter = new FileWriter(files[1]))
        {
            writeAsPEM(fileWriter, certChain);
        }
        return files;
    }

    private void writeAsPEM(FileWriter fileWriter, Key key) throws IOException
    {
        try(JcaPEMWriter pemWriter = new JcaPEMWriter(fileWriter)) {
            pemWriter.writeObject(new JcaPKCS8Generator((PrivateKey) key, null));
        }
    }

    private void writeAsPEM(FileWriter fileWriter, Certificate[] certChain) throws IOException
    {
        try(JcaPEMWriter pemWriter = new JcaPEMWriter(fileWriter)) {
            for (Certificate certificate : certChain) {
                pemWriter.writeObject(certificate);
            }
        }
    }
}
