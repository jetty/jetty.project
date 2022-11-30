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

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;

/**
 * TestJettyOSGiBootWebAppAsService
 *
 * Tests deployment of a WebAppContext as an osgi Service.
 *
 * Tests the ServiceWebAppProvider.
 *
 * Pax-Exam to make sure the jetty-ee10-osgi-boot can be started along with the
 * httpservice web-bundle. Then make sure we can deploy an OSGi service on the
 * top of this.
 */
@RunWith(PaxExam.class)
public class TestJettyOSGiBootWebAppAsService
{
    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
        ArrayList<Option> options = new ArrayList<>();

        options.addAll(TestOSGiUtil.configurePaxExamLogging());
        
        options.add(TestOSGiUtil.optionalRemoteDebug());
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-webapp-as-service.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));

        TestOSGiUtil.coreJettyDependencies(options);
        TestOSGiUtil.coreJspDependencies(options);
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-java-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());

        options.addAll(testDependencies());
        return options.toArray(new Option[0]);
    }

    public static List<Option> testDependencies()
    {
        List<Option> res = new ArrayList<>();

        // a bundle that registers a webapp as a service for the jetty osgi core
        // to pick up and deploy
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee10.osgi").artifactId("test-jetty-ee10-osgi-webapp").versionAsInProject().start());

        //a bundle that registers a new named Server instance
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee10.osgi").artifactId("test-jetty-ee10-osgi-server").versionAsInProject().start());

        return res;
    }

    @Test
    public void testBundle() throws Exception
    {
        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            TestOSGiUtil.diagnoseBundles(bundleContext);

        ServiceReference<?>[] refs = bundleContext.getServiceReferences(WebAppContext.class.getName(), null);
        assertNotNull(refs);
        assertEquals(2, refs.length);
        WebAppContext wac = (WebAppContext)bundleContext.getService(refs[0]);
        assertEquals("/acme", wac.getContextPath());
        wac = (WebAppContext)bundleContext.getService(refs[1]);
        assertEquals("/acme", wac.getContextPath());

        // now test getting a static file
        HttpClient client = new HttpClient();
        try
        {
            client.start();
            String port = System.getProperty("boot.webapp.service.port");
            assertNotNull(port);

            ContentResponse response = client.GET("http://127.0.0.1:" + port + "/acme/index.html");
            assertEquals("Response status code", HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
            TestOSGiUtil.assertContains("Response contents", content,
                "<h1>Test OSGi WebAppA</h1>");

            response = client.GET("http://127.0.0.1:" + port + "/acme/mime");
            assertEquals("Response status code", HttpStatus.OK_200, response.getStatus());
            content = response.getContentAsString();
            TestOSGiUtil.assertContains("Response contents", content,
                "MIMETYPE=application/gzip");

            port = System.getProperty("bundle.server.port");
            assertNotNull(port);

            response = client.GET("http://127.0.0.1:" + port + "/acme/index.html");
            assertEquals("Response status code", HttpStatus.OK_200, response.getStatus());
            content = response.getContentAsString();
            TestOSGiUtil.assertContains("Response contents", content,
                "<h1>Test OSGi WebAppB</h1>");
        }
        finally
        {
            client.stop();
        }
    }
}
