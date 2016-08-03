//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Ignore;
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
import static org.ops4j.pax.exam.CoreOptions.options;
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
        ArrayList<Option> options = new ArrayList<Option>();
        options.add(CoreOptions.junitBundles());
        options.addAll(configureJettyHomeAndPort("jetty-http.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res","com.sun.org.apache.xml.internal.utils",
                                               "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
                                               "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));

        options.addAll(TestJettyOSGiBootCore.coreJettyDependencies());
        options.addAll(Arrays.asList(options(systemProperty("pax.exam.logging").value("none"))));
        options.addAll(Arrays.asList(options(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(LOG_LEVEL))));
        options.addAll(Arrays.asList(options(systemProperty("org.eclipse.jetty.LEVEL").value(LOG_LEVEL))));

        options.addAll(TestJettyOSGiBootCore.jspDependencies());
        options.addAll(testDependencies());
        return options.toArray(new Option[options.size()]);
    }

    public static List<Option> configureJettyHomeAndPort(String jettySelectorFileName)
    {
        File etcFolder = new File("src/test/config/etc");
        String etc = "file://" + etcFolder.getAbsolutePath();
        List<Option> options = new ArrayList<Option>();
        options.add(systemProperty(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS).value(etc + "/jetty.xml;"
                + etc
                + "/"
                + jettySelectorFileName
                + ";"
                + etc
                + "/jetty-deployer.xml;"
                + etc
                + "/jetty-testrealm.xml"));
        options.add(systemProperty("jetty.http.port").value(String.valueOf(TestJettyOSGiBootCore.DEFAULT_HTTP_PORT)));
        options.add(systemProperty("jetty.ssl.port").value(String.valueOf(TestJettyOSGiBootCore.DEFAULT_SSL_PORT)));
        options.add(systemProperty("jetty.home").value(etcFolder.getParentFile().getAbsolutePath()));
        return options;
    }

    public static List<Option> testDependencies()
    {
        List<Option> res = new ArrayList<Option>();


        // a bundle that registers a webapp as a service for the jetty osgi core
        // to pick up and deploy
        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("test-jetty-osgi-webapp").versionAsInProject().start());

        
        
        
        //a bundle that registers a new named Server instance
        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("test-jetty-osgi-server").versionAsInProject().start());
       
        return res;
    }

    @Ignore
    @Test
    public void assertAllBundlesActiveOrResolved()
    {
        //TestOSGiUtil.assertAllBundlesActiveOrResolved(bundleContext);
        TestOSGiUtil.debugBundles(bundleContext);
    }



    @Test
    public void testBundle() throws Exception
    {
        // now test getting a static file
        HttpClient client = new HttpClient();
        try
        {
            client.start();
            
            ContentResponse response = client.GET("http://127.0.0.1:" + TestJettyOSGiBootCore.DEFAULT_HTTP_PORT + "/acme/index.html");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = new String(response.getContent());
            assertTrue(content.indexOf("<h1>Test OSGi WebAppA</h1>") != -1);

            response = client.GET("http://127.0.0.1:" + TestJettyOSGiBootCore.DEFAULT_HTTP_PORT + "/acme/mime");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            content = new String(response.getContent());
            assertTrue(content.indexOf("MIMETYPE=application/gzip") != -1);

            response = client.GET("http://127.0.0.1:" + "9999" + "/acme/index.html");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            content = new String(response.getContent());
            assertTrue(content.indexOf("<h1>Test OSGi WebAppB</h1>") != -1);
        }
        finally
        {
            client.stop();
        }

        ServiceReference[] refs = bundleContext.getServiceReferences(WebAppContext.class.getName(), null);
        assertNotNull(refs);
        assertEquals(2, refs.length);
        WebAppContext wac = (WebAppContext) bundleContext.getService(refs[0]);
        assertEquals("/acme", wac.getContextPath());
        wac = (WebAppContext) bundleContext.getService(refs[1]);
        assertEquals("/acme", wac.getContextPath());
    }
}
