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

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.webapp.WebAppContext;
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
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

/**
 * TestJettyOSGiBootWebAppAsService
 *
 * Tests deployment of a WebAppContext as an osgi Service.
 *
 * Tests the ServiceWebAppProvider.
 *
 * Pax-Exam to make sure the jetty-osgi-boot can be started along with the
 * httpservice web-bundle. Then make sure we can deploy an OSGi service on the
 * top of this.
 */
@RunWith(PaxExam.class)
public class TestJettyOSGiBootWebAppAsService
{
    private static final String LOG_LEVEL = "WARN";

    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
        ArrayList<Option> options = new ArrayList<>();
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-webapp-as-service.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));

        options.addAll(TestOSGiUtil.coreJettyDependencies());
        options.add(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(LOG_LEVEL));
        options.add(systemProperty("org.eclipse.jetty.LEVEL").value(LOG_LEVEL));

        options.addAll(TestOSGiUtil.jspDependencies());
        options.addAll(testDependencies());
        return options.toArray(new Option[options.size()]);
    }

    public static List<Option> testDependencies()
    {
        List<Option> res = new ArrayList<>();

        // a bundle that registers a webapp as a service for the jetty osgi core
        // to pick up and deploy
        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("test-jetty-osgi-webapp").versionAsInProject().start());

        //a bundle that registers a new named Server instance
        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("test-jetty-osgi-server").versionAsInProject().start());

        return res;
    }

    public void assertAllBundlesActiveOrResolved()
    {
        TestOSGiUtil.debugBundles(bundleContext);
        TestOSGiUtil.assertAllBundlesActiveOrResolved(bundleContext);
    }

    @Test
    public void testBundle() throws Exception
    {

        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            assertAllBundlesActiveOrResolved();

        // now test getting a static file
        HttpClient client = new HttpClient();
        try
        {
            client.start();
            String port = System.getProperty("boot.webapp.service.port");
            assertNotNull(port);

            ContentResponse response = client.GET("http://127.0.0.1:" + port + "/acme/index.html");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
            assertTrue(content.indexOf("<h1>Test OSGi WebAppA</h1>") != -1);

            response = client.GET("http://127.0.0.1:" + port + "/acme/mime");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            content = response.getContentAsString();
            assertTrue(content.indexOf("MIMETYPE=application/gzip") != -1);

            port = System.getProperty("bundle.server.port");
            assertNotNull(port);

            response = client.GET("http://127.0.0.1:" + port + "/acme/index.html");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            content = response.getContentAsString();
            assertTrue(content.indexOf("<h1>Test OSGi WebAppB</h1>") != -1);
        }
        finally
        {
            client.stop();
        }

        ServiceReference<?>[] refs = bundleContext.getServiceReferences(WebAppContext.class.getName(), null);
        assertNotNull(refs);
        assertEquals(2, refs.length);
        WebAppContext wac = (WebAppContext)bundleContext.getService(refs[0]);
        assertEquals("/acme", wac.getContextPath());
        wac = (WebAppContext)bundleContext.getService(refs[1]);
        assertEquals("/acme", wac.getContextPath());
    }
}
