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
    private KeyStoreScanner keystoreScanner;

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
            String keystorePath = useKeystore("oldKeystore").toString();
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
        keystoreScanner = new KeyStoreScanner(sslContextFactory, resolveAlias);
        keystoreScanner.setScanInterval(0);
        server.addBean(keystoreScanner);

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

        // Switch to use newKeystore which has a later expiry date.
        useKeystore("newKeystore");
        assertTrue(keystoreScanner.scan(5000));

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
            keystoreScanner.scan(5000);
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
            keystoreScanner.scan(5000);
        }

        // The good keystore is removed, having no keystore causes an exception.
        assertThrows(Throwable.class, this::getCertificateFromServer);

        // Switch to use keystore2 which has a later expiry date.
        useKeystore("newKeystore");
        keystoreScanner.scan(5000);
        X509Certificate cert2 = getCertificateFromServer();
        assertThat(getExpiryYear(cert2), is(2020));
    }

    @Test
    public void testReloadChangingSymbolicLink() throws Exception
    {
        assumeFileSystemSupportsSymlink();
        Path newKeystore = useKeystore("newKeystore", "newKeystore");
        Path oldKeystore = useKeystore("oldKeystore", "oldKeystore");

        Path symlinkKeystorePath = keystoreDir.resolve("symlinkKeystore");
        start(sslContextFactory ->
        {
            Files.createSymbolicLink(symlinkKeystorePath, oldKeystore);
            sslContextFactory.setKeyStorePath(symlinkKeystorePath.toString());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");
        }, false);

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromServer();
        assertThat(getExpiryYear(cert1), is(2015));

        // Change the symlink to point to the newKeystore file location which has a later expiry date.
        Files.delete(symlinkKeystorePath);
        Files.createSymbolicLink(symlinkKeystorePath, newKeystore);
        keystoreScanner.scan(5000);

        // The scanner should have detected the updated keystore, expiry should be renewed.
        X509Certificate cert2 = getCertificateFromServer();
        assertThat(getExpiryYear(cert2), is(2020));
    }

    @Test
    public void testReloadChangingTargetOfSymbolicLink() throws Exception
    {
        assumeFileSystemSupportsSymlink();
        Path keystoreLink = keystoreDir.resolve("symlinkKeystore");
        Path oldKeystoreSrc = MavenTestingUtils.getTestResourcePathFile("oldKeystore");
        Path newKeystoreSrc = MavenTestingUtils.getTestResourcePathFile("newKeystore");
        Path target = keystoreDir.resolve("keystore");

        start(sslContextFactory ->
        {
            Files.copy(oldKeystoreSrc, target);
            Files.createSymbolicLink(keystoreLink, target);
            sslContextFactory.setKeyStorePath(keystoreLink.toString());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setKeyManagerPassword("keypwd");
        });

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromServer();
        assertThat(getExpiryYear(cert1), is(2015));

        // Change the target file of the symlink to the newKeystore which has a later expiry date.
        Files.copy(newKeystoreSrc, target, StandardCopyOption.REPLACE_EXISTING);
        System.err.println("### Triggering scan");
        keystoreScanner.scan(5000);

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
