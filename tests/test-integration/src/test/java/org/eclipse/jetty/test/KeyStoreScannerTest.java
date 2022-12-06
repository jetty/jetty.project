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

package org.eclipse.jetty.test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.KeyStoreScanner;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@ExtendWith(WorkDirExtension.class)
public class KeyStoreScannerTest
{
    public WorkDir testdir;
    private Server server;
    private Path keystoreDir;
    private KeyStoreScanner keyStoreScanner;

    @BeforeEach
    public void before()
    {
        keystoreDir = testdir.getEmptyPathDir();
    }

    @FunctionalInterface
    public interface Configuration
    {
        void configure(SslContextFactory sslContextFactory) throws Exception;
    }

    public void start() throws Exception
    {
        start(sslContextFactory ->
        {
            String keystorePath = useKeystore("oldKeyStore").toString();
            sslContextFactory.setKeyStorePath(keystorePath);
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");
        });
    }

    public void start(Configuration configuration) throws Exception
    {
        start(configuration, true);
    }

    public void start(Configuration configuration, boolean resolveAlias) throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        configuration.configure(sslContextFactory);

        server = new Server();
        SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString());
        HttpConfiguration httpsConfig = new HttpConfiguration();
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpsConfig);
        ServerConnector connector = new ServerConnector(server, sslConnectionFactory, httpConnectionFactory);
        server.addConnector(connector);

        // Configure Keystore Reload.
        keyStoreScanner = new KeyStoreScanner(sslContextFactory);
        keyStoreScanner.setScanInterval(0);
        server.addBean(keyStoreScanner);

        server.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testKeystoreHotReload() throws Exception
    {
        start();

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromServer();
        assertThat(getExpiryYear(cert1), is(2015));

        // Switch to use newKeyStore which has a later expiry date.
        useKeystore("newKeyStore");
        assertTrue(keyStoreScanner.scan(5000));

        // The scanner should have detected the updated keystore, expiry should be renewed.
        X509Certificate cert2 = getCertificateFromServer();
        assertThat(getExpiryYear(cert2), is(2020));
    }

    @Test
    public void testReloadWithBadKeystore() throws Exception
    {
        start();

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromServer();
        assertThat(getExpiryYear(cert1), is(2015));

        // Switch to use badKeystore which has the incorrect passwords.
        try (StacklessLogging ignored = new StacklessLogging(KeyStoreScanner.class))
        {
            useKeystore("badKeystore");
            keyStoreScanner.scan(5000);
        }

        // The good keystore is removed, now the bad keystore now causes an exception.
        assertThrows(Throwable.class, this::getCertificateFromServer);
    }

    @Test
    public void testKeystoreRemoval() throws Exception
    {
        start();

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromServer();
        assertThat(getExpiryYear(cert1), is(2015));

        // Delete the keystore.
        try (StacklessLogging ignored = new StacklessLogging(KeyStoreScanner.class))
        {
            Path keystorePath = keystoreDir.resolve("keystore");
            assertTrue(Files.deleteIfExists(keystorePath));
            keyStoreScanner.scan(5000);
        }

        // The good keystore is removed, having no keystore causes an exception.
        assertThrows(Throwable.class, this::getCertificateFromServer);

        // Switch to use keystore2 which has a later expiry date.
        useKeystore("newKeyStore");
        keyStoreScanner.scan(5000);
        X509Certificate cert2 = getCertificateFromServer();
        assertThat(getExpiryYear(cert2), is(2020));
    }

    @Test
    public void testReloadChangingSymbolicLink() throws Exception
    {
        assumeFileSystemSupportsSymlink();
        Path newKeyStore = useKeystore("newKeyStore", "newKeyStore");
        Path oldKeyStore = useKeystore("oldKeyStore", "oldKeyStore");

        Path symlinkKeystorePath = keystoreDir.resolve("symlinkKeystore");
        start(sslContextFactory ->
        {
            Files.createSymbolicLink(symlinkKeystorePath, oldKeyStore);
            sslContextFactory.setKeyStorePath(symlinkKeystorePath.toString());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");
        }, false);

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromServer();
        assertThat(getExpiryYear(cert1), is(2015));

        // Change the symlink to point to the newKeyStore file location which has a later expiry date.
        Files.delete(symlinkKeystorePath);
        Files.createSymbolicLink(symlinkKeystorePath, newKeyStore);
        keyStoreScanner.scan(5000);

        // The scanner should have detected the updated keystore, expiry should be renewed.
        X509Certificate cert2 = getCertificateFromServer();
        assertThat(getExpiryYear(cert2), is(2020));
    }

    @Test
    public void testReloadChangingTargetOfSymbolicLink() throws Exception
    {
        assumeFileSystemSupportsSymlink();
        Path keystoreLink = keystoreDir.resolve("symlinkKeystore");
        Path oldKeyStoreSrc = MavenTestingUtils.getTestResourcePathFile("oldKeyStore");
        Path newKeyStoreSrc = MavenTestingUtils.getTestResourcePathFile("newKeyStore");
        Path target = keystoreDir.resolve("keystore");

        start(sslContextFactory ->
        {
            Files.copy(oldKeyStoreSrc, target);
            Files.createSymbolicLink(keystoreLink, target);
            sslContextFactory.setKeyStorePath(keystoreLink.toString());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");
        });

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromServer();
        assertThat(getExpiryYear(cert1), is(2015));

        // Change the target file of the symlink to the newKeyStore which has a later expiry date.
        Files.copy(newKeyStoreSrc, target, StandardCopyOption.REPLACE_EXISTING);
        System.err.println("### Triggering scan");
        keyStoreScanner.scan(5000);

        // The scanner should have detected the updated keystore, expiry should be renewed.
        X509Certificate cert2 = getCertificateFromServer();
        assertThat(getExpiryYear(cert2), is(2020));
    }

    @Test
    public void testReloadChangingLinkTargetOfSymbolicLink() throws Exception
    {
        assumeFileSystemSupportsSymlink();
        Path oldKeyStoreSrc = MavenTestingUtils.getTestResourcePathFile("oldKeyStore");
        Path newKeyStoreSrc = MavenTestingUtils.getTestResourcePathFile("newKeyStore");

        Path sslDir = keystoreDir.resolve("ssl");
        Path optDir = keystoreDir.resolve("opt");
        Path optKeystoreLink = optDir.resolve("keystore");
        Path optKeystore1 = optDir.resolve("keystore.1");
        Path optKeystore2 = optDir.resolve("keystore.2");
        Path keystoreFile = sslDir.resolve("keystore");

        start(sslContextFactory ->
        {
            FS.ensureEmpty(sslDir);
            FS.ensureEmpty(optDir);
            Files.copy(oldKeyStoreSrc, optKeystore1);
            Files.createSymbolicLink(optKeystoreLink, optKeystore1);
            Files.createSymbolicLink(keystoreFile, optKeystoreLink);

            sslContextFactory.setKeyStorePath(keystoreFile.toString());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");
        });

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromServer();
        assertThat(getExpiryYear(cert1), is(2015));

        // Create a new keystore file
        Files.copy(newKeyStoreSrc, optKeystore2);
        // Change (middle) link to new keystore
        Files.delete(optKeystoreLink);
        Files.createSymbolicLink(optKeystoreLink, optKeystore2);
        System.err.println("### Triggering scan");
        keyStoreScanner.scan(5000);

        // The scanner should have detected the updated keystore, expiry should be renewed.
        X509Certificate cert2 = getCertificateFromServer();
        assertThat(getExpiryYear(cert2), is(2020));
    }

    /**
     * Test a doubly-linked keystore, and refreshing by only modifying the middle symlink.
     */
    @Test
    public void testDoublySymlinked() throws Exception
    {
        assumeFileSystemSupportsSymlink();
        Path oldKeyStoreSrc = MavenTestingUtils.getTestResourcePathFile("oldKeyStore");
        Path newKeyStoreSrc = MavenTestingUtils.getTestResourcePathFile("newKeyStore");

        Path sslDir = keystoreDir.resolve("ssl");
        Path dataDir = sslDir.resolve("data");
        Path timestampNovDir = sslDir.resolve("2022-11");
        Path timestampDecDir = sslDir.resolve("2022-12");
        Path targetNov = timestampNovDir.resolve("keystore.p12");
        Path targetDec = timestampDecDir.resolve("keystore.p12");

        boolean followLinks = false; // follow keystore links

        start(sslContextFactory ->
        {
            // What we want is ..
            // ssl/keystore.p12 -> data/keystore.p12
            // ssl/data/ -> 2022-11/
            // ssl/2022-11/keystore.p12 (file)

            FS.ensureEmpty(sslDir);
            FS.ensureEmpty(timestampNovDir);
            FS.ensureEmpty(timestampDecDir);
            Files.copy(oldKeyStoreSrc, targetNov);
            Files.copy(newKeyStoreSrc, targetDec);

            // Create symlink of data/ to 2022-11/
            Files.createSymbolicLink(dataDir, timestampNovDir.getFileName());

            // Create symlink of keystore.p12 to data/keystore.p12
            Path keystoreLink = sslDir.resolve("keystore.p12");
            Files.createSymbolicLink(keystoreLink, Paths.get("data/keystore.p12"));

            sslContextFactory.setKeyStorePath(keystoreLink.toString());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");
        }, followLinks);

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromServer();
        assertThat(getExpiryYear(cert1), is(2015));

        // Change the data/ symlink to 2022-12/ which points to a newer certificate
        Files.delete(dataDir);
        Files.createSymbolicLink(dataDir, timestampDecDir.getFileName());
        System.err.println("### Triggering scan");
        keyStoreScanner.scan(5000);

        // The scanner should have detected the updated keystore, expiry should be renewed.
        X509Certificate cert2 = getCertificateFromServer();
        assertThat(getExpiryYear(cert2), is(2020));
    }

    public Path useKeystore(String keystoreToUse, String keystorePath) throws Exception
    {
        return useKeystore(MavenTestingUtils.getTestResourcePath(keystoreToUse), keystoreDir.resolve(keystorePath));
    }

    public Path useKeystore(Path keystoreToUse, Path keystorePath) throws Exception
    {
        if (Files.exists(keystorePath))
            Files.delete(keystorePath);

        Files.copy(keystoreToUse, keystorePath);

        if (!Files.exists(keystorePath))
            throw new IllegalStateException("keystore file was not created");

        return keystorePath.toAbsolutePath();
    }

    public Path useKeystore(String keystore) throws Exception
    {
        Path keystorePath = keystoreDir.resolve("keystore");
        if (Files.exists(keystorePath))
            Files.delete(keystorePath);

        Files.copy(MavenTestingUtils.getTestResourcePath(keystore), keystorePath);

        if (!Files.exists(keystorePath))
            throw new IllegalStateException("keystore file was not created");

        return keystorePath.toAbsolutePath();
    }

    public static int getExpiryYear(X509Certificate cert)
    {
        Calendar instance = Calendar.getInstance();
        instance.setTime(cert.getNotAfter());
        return instance.get(Calendar.YEAR);
    }

    public X509Certificate getCertificateFromServer() throws Exception
    {
        URL serverUrl = server.getURI().toURL();
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[0], new TrustManager[] {new DefaultTrustManager()}, new SecureRandom());
        SSLContext.setDefault(ctx);

        HttpsURLConnection connection = (HttpsURLConnection)serverUrl.openConnection();
        connection.setHostnameVerifier((a, b) -> true);
        connection.setRequestProperty("Connection", "close");
        connection.connect();
        Certificate[] certs = connection.getServerCertificates();
        connection.disconnect();

        assertThat(certs.length, is(1));
        return (X509Certificate)certs[0];
    }

    private void assumeFileSystemSupportsSymlink() throws IOException
    {
        // Make symlink
        Path dir = MavenTestingUtils.getTargetTestingPath("symlink-test");
        FS.ensureEmpty(dir);

        Path foo = dir.resolve("foo");
        Path bar = dir.resolve("bar");

        try
        {
            Files.createFile(foo);
            Files.createSymbolicLink(bar, foo);
        }
        catch (UnsupportedOperationException | FileSystemException e)
        {
            // if unable to create symlink, no point testing the rest
            // this is the path that Microsoft Windows takes.
            assumeFalse(true, "Not supported");
        }
    }

    private static class DefaultTrustManager implements X509TrustManager
    {
        @Override
        public void checkClientTrusted(X509Certificate[] arg0, String arg1)
        {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] arg0, String arg1)
        {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            return null;
        }
    }
}
