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
import javax.inject.Inject;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

/**
 * TestJettyOSGiBootContextAsService
 *
 * Tests deployment of a ContextHandler as an osgi Service.
 *
 * Tests the ServiceContextProvider.
 */
@RunWith(PaxExam.class)
public class TestJettyOSGiBootContextAsService
{
    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
        ArrayList<Option> options = new ArrayList<>();
        
        options.addAll(TestOSGiUtil.configurePaxExamLogging());
        
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-context-as-service.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*"));
        TestOSGiUtil.coreJettyDependencies(options);
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-java-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());

        // a bundle that registers a webapp as a service for the jetty osgi core to pick up and deploy
        options.add(mavenBundle().groupId("org.eclipse.jetty.ee10.osgi").artifactId("test-jetty-ee10-osgi-context").versionAsInProject().start());

        options.add(systemProperty("org.ops4j.pax.url.mvn.localRepository").value(System.getProperty("mavenRepoPath")));

        return options.toArray(new Option[0]);
    }

    @Test
    public void testContextHandlerAsOSGiService() throws Exception
    {
        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            TestOSGiUtil.diagnoseBundles(bundleContext);

        // now test the context
        HttpClient client = new HttpClient();
        try
        {
            client.start();
            String tmp = System.getProperty("boot.context.service.port");
            assertNotNull(tmp);
            int port = Integer.valueOf(tmp);
            ContentResponse response = client.GET("http://127.0.0.1:" + port + "/acme/index.html");
            assertEquals(HttpStatus.OK_200, response.getStatus());

            String content = new String(response.getContent());
            assertTrue(content.contains("<h1>Test OSGi Context</h1>"));
        }
        finally
        {
            client.stop();
        }

        ServiceReference[] refs = bundleContext.getServiceReferences(ContextHandler.class.getName(), null);
        assertNotNull(refs);
        assertEquals(1, refs.length);
        ContextHandler ch = (ContextHandler)bundleContext.getService(refs[0]);
        assertEquals("/acme", ch.getContextPath());

        // Stop the bundle with the ContextHandler in it and check the jetty
        // Context is destroyed for it.
        // TODO: think of a better way to communicate this to the test, other
        // than checking stderr output
        Bundle testWebBundle = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.ee10.osgi.testcontext");
        assertNotNull("Could not find the org.eclipse.jetty.test-jetty-ee10-osgi-context.jar bundle", testWebBundle);
        assertEquals("The bundle org.eclipse.jetty.testcontext is not correctly resolved", Bundle.ACTIVE, testWebBundle.getState());
        testWebBundle.stop();
    }
}
