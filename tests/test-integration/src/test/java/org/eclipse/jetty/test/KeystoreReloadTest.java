//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Calendar;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.SslKeyStoreScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class KeystoreReloadTest
{
    private static final int scanInterval = 1;
    public WorkDir testdir;
    private Server server;

    public String useKeystore(String keystore) throws Exception
    {
        Path keystoreDir = testdir.getEmptyPathDir();
        Path keystorePath = keystoreDir.resolve("keystore");
        if (Files.exists(keystorePath))
            Files.delete(keystorePath);

        if (keystore == null)
            return null;

        Files.copy(MavenTestingUtils.getTestResourceFile(keystore).toPath(), keystorePath);
        keystorePath.toFile().deleteOnExit();

        if (!Files.exists(keystorePath))
            throw new IllegalStateException("keystore file was not created");

        return keystorePath.toAbsolutePath().toString();
    }

    @BeforeEach
    public void start() throws Exception
    {
        String keystorePath = useKeystore("oldKeystore");
        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystorePath);
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");

        server = new Server();
        SslConnectionFactory sslConnectionFactory = new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString());
        HttpConfiguration httpsConfig = new HttpConfiguration();
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpsConfig);
        ServerConnector connector = new ServerConnector(server, sslConnectionFactory, httpConnectionFactory);
        connector.setPort(8443);
        server.addConnector(connector);

        // Configure Keystore Reload.
        SslKeyStoreScanner keystoreScanner = new SslKeyStoreScanner(sslContextFactory);
        keystoreScanner.setScanInterval(scanInterval);
        server.addBean(keystoreScanner);

        server.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        server.stop();
    }

    @Test
    public void testKeystoreHotReload() throws Exception
    {
        URL serverUrl = server.getURI().toURL();

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromUrl(serverUrl);
        assertThat(getExpiryYear(cert1), is(2015));

        // Switch to use newKeystore which has a later expiry date.
        useKeystore("newKeystore");
        Thread.sleep(Duration.ofSeconds(scanInterval * 2).toMillis());

        // The scanner should have detected the updated keystore, expiry should be renewed.
        X509Certificate cert2 = getCertificateFromUrl(serverUrl);
        assertThat(getExpiryYear(cert2), is(2020));
    }

    @Test
    public void testReloadWithBadKeystore() throws Exception
    {
        URL serverUrl = server.getURI().toURL();

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromUrl(serverUrl);
        assertThat(getExpiryYear(cert1), is(2015));

        // Switch to use badKeystore which has the incorrect passwords.
        try (StacklessLogging ignored = new StacklessLogging(SslKeyStoreScanner.class))
        {
            useKeystore("badKeystore");
            Thread.sleep(Duration.ofSeconds(scanInterval * 2).toMillis());
        }

        // The good keystore is removed, now the bad keystore now causes an exception.
        assertThrows(Throwable.class, () -> getCertificateFromUrl(serverUrl));
    }

    @Test
    public void testKeystoreRemoval() throws Exception
    {
        URL serverUrl = server.getURI().toURL();

        // Check the original certificate expiry.
        X509Certificate cert1 = getCertificateFromUrl(serverUrl);
        assertThat(getExpiryYear(cert1), is(2015));

        // Delete the keystore.
        try (StacklessLogging ignored = new StacklessLogging(SslKeyStoreScanner.class))
        {
            useKeystore(null);
            Thread.sleep(Duration.ofSeconds(scanInterval * 2).toMillis());
        }

        // The good keystore is removed, having no keystore causes an exception.
        assertThrows(Throwable.class, () -> getCertificateFromUrl(serverUrl));

        // Switch to use keystore2 which has a later expiry date.
        useKeystore("newKeystore");
        Thread.sleep(Duration.ofSeconds(scanInterval * 2).toMillis());
        X509Certificate cert2 = getCertificateFromUrl(serverUrl);
        assertThat(getExpiryYear(cert2), is(2020));
    }

    public static int getExpiryYear(X509Certificate cert)
    {
        Calendar instance = Calendar.getInstance();
        instance.setTime(cert.getNotAfter());
        return instance.get(Calendar.YEAR);
    }

    public static X509Certificate getCertificateFromUrl(URL serverUrl) throws Exception
    {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[0], new TrustManager[] {new DefaultTrustManager()}, new SecureRandom());
        SSLContext.setDefault(ctx);

        HttpsURLConnection connection = (HttpsURLConnection)serverUrl.openConnection();
        connection.setHostnameVerifier((a, b) -> true);
        connection.connect();
        Certificate[] certs = connection.getServerCertificates();
        connection.disconnect();

        assertThat(certs.length, is(1));
        return (X509Certificate)certs[0];
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
