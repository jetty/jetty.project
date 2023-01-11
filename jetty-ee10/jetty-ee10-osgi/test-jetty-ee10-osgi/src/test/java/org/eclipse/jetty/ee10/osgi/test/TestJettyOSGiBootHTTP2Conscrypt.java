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

package org.eclipse.jetty.ee10.osgi.test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.inject.Inject;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Test;
import org.junit.jupiter.api.Disabled;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.wrappedBundle;

@Disabled //wrappedBundle mismatch version of bndutils
//@RunWith(PaxExam.class)
//@ExamReactorStrategy(PerClass.class)
public class TestJettyOSGiBootHTTP2Conscrypt
{
    @Inject
    private BundleContext bundleContext;

    @Configuration
    public Option[] config()
    {
        ArrayList<Option> options = new ArrayList<>();
        
        options.addAll(TestOSGiUtil.configurePaxExamLogging());
           
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(true, "jetty-http2.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*", "javax.activation.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects",
            "sun.security", "sun.security.x509", "sun.security.ssl"));
        options.addAll(http2JettyDependencies());

        TestOSGiUtil.coreJettyDependencies(options);
        TestOSGiUtil.coreJspDependencies(options);
        //deploy a test webapp
        options.add(mavenBundle().groupId("org.eclipse.jetty.ee10.demos").artifactId("jetty-ee10-demo-jsp-webapp").classifier("webbundle").versionAsInProject());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-conscrypt-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("jetty-http2-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("jetty-http2-client-transport").versionAsInProject().start());

        options.add(CoreOptions.cleanCaches(true));
        return options.toArray(new Option[0]);
    }

    public static List<Option> http2JettyDependencies()
    {
        List<Option> res = new ArrayList<>();
        res.add(CoreOptions.systemProperty("jetty.alpn.protocols").value("h2,http/1.1"));
        res.add(CoreOptions.systemProperty("jetty.sslContext.provider").value("Conscrypt"));

        res.add(wrappedBundle(mavenBundle().groupId("org.conscrypt").artifactId("conscrypt-openjdk-uber").versionAsInProject())
            .imports("javax.net.ssl,*")
            .exports("org.conscrypt;version=" + System.getProperty("conscrypt-version"))
            .instructions("Bundle-NativeCode=META-INF/native/libconscrypt_openjdk_jni-linux-x86_64.so")
            .start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee10.osgi").artifactId("jetty-ee10-osgi-alpn").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-conscrypt-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-server").versionAsInProject().start());

        res.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("jetty-http2-common").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("jetty-http2-hpack").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("jetty-http2-server").versionAsInProject().start());
        return res;
    }

    @Test
    public void testHTTP2() throws Exception
    {
        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
        {
            TestOSGiUtil.diagnoseBundles(bundleContext);
            Bundle conscrypt = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.alpn.conscrypt.server");
            assertNotNull(conscrypt);
            ServiceReference<?>[] services = conscrypt.getRegisteredServices();
            assertNotNull(services);
            assertTrue(services.length > 0);
        }

        HTTP2Client client = new HTTP2Client();
        try
        {
            String port = System.getProperty("boot.https.port");
            assertNotNull(port);

            Path path = Paths.get("src", "test", "config");
            File keys = path.resolve("etc").resolve("keystore.p12").toFile();

            ClientConnector clientConnector = new ClientConnector();
            SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
            sslContextFactory.setKeyStorePath(keys.getAbsolutePath());
            sslContextFactory.setKeyStorePassword("storepwd");
            sslContextFactory.setProvider("Conscrypt");
            sslContextFactory.setEndpointIdentificationAlgorithm(null);
            if (JavaVersion.VERSION.getPlatform() < 9)
            {
                // Conscrypt enables TLSv1.3 by default but it's not supported in Java 8.
                sslContextFactory.addExcludeProtocols("TLSv1.3");
            }
            clientConnector.setSslContextFactory(sslContextFactory);
            HTTP2Client http2Client = new HTTP2Client(clientConnector);
            HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
            Executor executor = new QueuedThreadPool();
            httpClient.setExecutor(executor);

            httpClient.start();

            ContentResponse response = httpClient.GET("https://localhost:" + port + "/ee10-demo-jsp/jstl.jsp");
            assertEquals(200, response.getStatus());
            assertTrue(response.getContentAsString().contains("JSTL Example"));
        }
        finally
        {
            client.stop();
        }
    }
}
