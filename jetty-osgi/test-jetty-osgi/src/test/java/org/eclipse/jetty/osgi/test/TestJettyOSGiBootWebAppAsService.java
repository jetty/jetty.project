//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.Configuration;
import org.ops4j.pax.exam.junit.JUnit4TestRunner;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

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
@RunWith(JUnit4TestRunner.class)
public class TestJettyOSGiBootWebAppAsService
{
    private static final boolean LOGGING_ENABLED = false;

    private static final boolean REMOTE_DEBUGGING = false;

    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
        ArrayList<Option> options = new ArrayList<Option>();
        TestOSGiUtil.addMoreOSGiContainers(options);

        options.add(CoreOptions.junitBundles());
        options.addAll(configureJettyHomeAndPort("jetty-selector.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*"));
        options.addAll(TestJettyOSGiBootCore.coreJettyDependencies());
        
        String logLevel = "WARN";
        if (LOGGING_ENABLED)
            logLevel = "INFO";
        
        options.addAll(Arrays.asList(options(
                                             // install log service using pax runners profile abstraction (there
                                             // are more profiles, like DS)
                                             // logProfile(),
                                             // this is how you set the default log level when using pax logging
                                             // (logProfile)
                                             systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(logLevel),
                                             systemProperty("org.eclipse.jetty.LEVEL").value(logLevel))));

        options.addAll(jspDependencies());
        return options.toArray(new Option[options.size()]);
    }
    

    public static List<Option> configureJettyHomeAndPort(String jettySelectorFileName)
    {
        File etcFolder = new File("src/test/config/etc");
        String etc = "file://" + etcFolder.getAbsolutePath();
        List<Option> options = new ArrayList<Option>();
        options.add(systemProperty(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS).value(etc     + "/jetty.xml;"
                + etc
                + "/"
                + jettySelectorFileName
                + ";"
                + etc
                + "/jetty-deployer.xml;"
                + etc
                + "/jetty-testrealm.xml"));
        options.add(systemProperty("jetty.port").value(String.valueOf(TestJettyOSGiBootCore.DEFAULT_JETTY_HTTP_PORT)));
        options.add(systemProperty("jetty.home").value(etcFolder.getParentFile().getAbsolutePath()));
        return options;
    }

    public static List<Option> jspDependencies()
    {
        List<Option> res = new ArrayList<Option>();
        
        //jsp bundles
        res.add(mavenBundle().groupId("javax.servlet.jsp").artifactId("javax.servlet.jsp-api").versionAsInProject());
        res.add(mavenBundle().groupId("org.eclipse.jetty.orbit").artifactId("javax.servlet.jsp.jstl").versionAsInProject());
        res.add(mavenBundle().groupId("org.eclipse.jetty.orbit").artifactId("org.apache.taglibs.standard.glassfish").versionAsInProject());
        res.add(mavenBundle().groupId("org.glassfish").artifactId("javax.el").versionAsInProject());
        res.add(mavenBundle().groupId("org.eclipse.jetty.orbit").artifactId("org.eclipse.jdt.core").versionAsInProject());
        res.add(mavenBundle().groupId("org.eclipse.jetty.toolchain").artifactId("jetty-jsp-fragment").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("jetty-osgi-boot-jsp").versionAsInProject().noStart());

        // a bundle that registers a webapp as a service for the jetty osgi core
        // to pick up and deploy
        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("test-jetty-osgi-webapp").versionAsInProject().start());
        return res;
    }

    @Test
    public void assertAllBundlesActiveOrResolved()
    {
        TestOSGiUtil.assertAllBundlesActiveOrResolved(bundleContext);
    }

    /**
     */
    @Test
    public void testBundle() throws Exception
    {
        // now test getting a static file
        HttpClient client = new HttpClient();
        try
        {
            client.start();

            ContentResponse response = client.GET("http://127.0.0.1:" + TestJettyOSGiBootCore.DEFAULT_JETTY_HTTP_PORT + "/acme/index.html");
            assertEquals(HttpStatus.OK_200, response.getStatus());

            String content = new String(response.getContent());
            assertTrue(content.indexOf("<h1>Test OSGi WebApp</h1>") != -1);
        }
        finally
        {
            client.stop();
        }

        ServiceReference[] refs = bundleContext.getServiceReferences(ContextHandler.class.getName(), null);
        assertNotNull(refs);
        assertEquals(1, refs.length);
        WebAppContext wac = (WebAppContext) bundleContext.getService(refs[0]);
        assertEquals("/acme", wac.getContextPath());
    }

}
