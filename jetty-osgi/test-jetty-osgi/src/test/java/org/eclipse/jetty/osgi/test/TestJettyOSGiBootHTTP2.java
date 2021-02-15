//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.osgi.test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.inject.Inject;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class TestJettyOSGiBootHTTP2
{
    private static final String LOG_LEVEL = "WARN";

    @Inject
    private BundleContext bundleContext;

    @Configuration
    public Option[] config()
    {
        ArrayList<Option> options = new ArrayList<>();
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(true, "jetty-http2.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*", "javax.activation.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));
        options.addAll(http2JettyDependencies());

        options.addAll(TestOSGiUtil.coreJettyDependencies());
        options.addAll(TestOSGiUtil.jspDependencies());
        //deploy a test webapp
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("test-jetty-webapp").classifier("webbundle").versionAsInProject());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-openjdk8-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("http2-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("http2-http-client-transport").versionAsInProject().start());

        options.add(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(LOG_LEVEL));
        options.add(systemProperty("org.eclipse.jetty.LEVEL").value("INFO"));
        options.add(CoreOptions.cleanCaches(true));
        return options.toArray(new Option[0]);
    }

    public static List<Option> http2JettyDependencies()
    {
        List<Option> res = new ArrayList<>();
        res.add(CoreOptions.systemProperty("jetty.alpn.protocols").value("h2,http/1.1"));

        String alpnAgent = System.getProperty("mortbay-alpn-agent");
        if (alpnAgent == null)
            throw new IllegalStateException("Define path to alpn agent jar as system property -Dmortbay-alpn-agent");
        File alpnAgentFile = new File(alpnAgent);
        if (!alpnAgentFile.exists())
            throw new IllegalStateException("Unable to find the alpn agent jar here: " + alpnAgent);
        res.add(CoreOptions.vmOptions("-javaagent:" + alpnAgentFile.getAbsolutePath() + "=debug=true"));

        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("jetty-osgi-alpn").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-openjdk8-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-server").versionAsInProject().start());

        res.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("http2-common").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("http2-hpack").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("http2-server").versionAsInProject().start());
        return res;
    }

    public void assertAllBundlesActiveOrResolved()
    {
        TestOSGiUtil.debugBundles(bundleContext);
        TestOSGiUtil.assertAllBundlesActiveOrResolved(bundleContext);
        Bundle openjdk8 = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.alpn.openjdk8.server");
        assertNotNull(openjdk8);
        ServiceReference<?>[] services = openjdk8.getRegisteredServices();
        assertNotNull(services);
        Bundle server = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.alpn.server");
        assertNotNull(server);
    }

    @Test
    public void testHTTP2() throws Exception
    {
        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            assertAllBundlesActiveOrResolved();

        HttpClient httpClient = null;
        HTTP2Client http2Client = null;
        try
        {
            //get the port chosen for https
            String tmp = System.getProperty("boot.https.port");
            assertNotNull(tmp);
            int port = Integer.parseInt(tmp.trim());

            Path path = Paths.get("src", "test", "config");
            File keys = path.resolve("etc").resolve("keystore").toFile();

            //set up client to do http2
            http2Client = new HTTP2Client();
            SslContextFactory sslContextFactory = new SslContextFactory.Client();
            sslContextFactory.setKeyManagerPassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
            sslContextFactory.setTrustStorePath(keys.getAbsolutePath());
            sslContextFactory.setKeyStorePath(keys.getAbsolutePath());
            sslContextFactory.setTrustStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
            sslContextFactory.setEndpointIdentificationAlgorithm(null);
            httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client), sslContextFactory);
            Executor executor = new QueuedThreadPool();
            httpClient.setExecutor(executor);
            httpClient.start();

            ContentResponse response = httpClient.GET("https://localhost:" + port + "/jsp/jstl.jsp");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String body = response.getContentAsString();
            assertTrue("Body contains \"JSTL Example\": " + body, body.contains("JSTL Example"));
        }
        finally
        {
            if (httpClient != null)
                httpClient.stop();
            if (http2Client != null)
                http2Client.stop();
        }
    }
}
