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
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import javax.inject.Inject;

import aQute.bnd.osgi.Constants;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;

/**
 * TestJettyOSGiClasspathResources
 *
 */
@RunWith(PaxExam.class)
public class TestJettyOSGiClasspathResources
{
    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {        
        ArrayList<Option> options = new ArrayList<>();
        options.addAll(TestOSGiUtil.configurePaxExamLogging());

        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-with-resources.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*", "javax.activation.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));

        TestOSGiUtil.coreJettyDependencies(options);
        TestOSGiUtil.coreJspDependencies(options);
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-java-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());

        //Note: we have to back down the version of bnd used here because tinybundles expects only this version
        options.add(mavenBundle().groupId("biz.aQute.bnd").artifactId("biz.aQute.bndlib").version("3.5.0").start());
        options.add(mavenBundle().groupId("org.ops4j.pax.tinybundles").artifactId("tinybundles").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty.ee10.osgi").artifactId("test-jetty-ee10-osgi-webapp-resources").type("war").versionAsInProject());
        options.add(CoreOptions.cleanCaches(true));   
        return options.toArray(new Option[options.size()]);
    }
   
    @Test
    public void testWebInfResourceNotOnBundleClasspath() throws Exception
    {
        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            TestOSGiUtil.diagnoseBundles(bundleContext);

        //Test the test-jetty-ee10-osgi-webapp-resource bundle with a
        //Bundle-Classpath that does NOT include WEB-INF/classes
        HttpClient client = new HttpClient();
        try
        {
            client.start();

            String port = System.getProperty("boot.resources.port");
            assertNotNull(port);
            ContentResponse response = client.GET("http://127.0.0.1:" + port + "/test-webapp-resources/hello/a");
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
            //check that fake.properties is only listed once from the classpath
            assertEquals(content.indexOf("fake.properties"), content.lastIndexOf("fake.properties"));
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testWebInfResourceOnBundleClasspath() throws Exception
    {
        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            TestOSGiUtil.diagnoseBundles(bundleContext);

        Bundle webappBundle = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.ee10.osgi.webapp.resources");

        //Make a new bundle based on the test-jetty-ee10-osgi-webapp-resources war bundle, but
        //change the Bundle-Classpath so that WEB-INF/classes IS on the bundle classpath
        File warFile = new File("target/test-jetty-ee10-osgi-webapp-resources.war");
        TinyBundle tiny = TinyBundles.bundle();
        tiny.read(new FileInputStream(warFile));
        tiny.set(Constants.BUNDLE_CLASSPATH, "., WEB-INF/classes/");
        tiny.set(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.jetty.ee10.osgi.webapp.resources.alt");
        InputStream is = tiny.build(TinyBundles.withBnd());
        bundleContext.installBundle("dummyAltLocation", is);

        webappBundle.stop();
        Bundle bundle = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.ee10.osgi.webapp.resources.alt");
        bundle.start();
        
        HttpClient client = new HttpClient();
        try
        {
            client.start();

            String port = System.getProperty("boot.resources.port");
            assertNotNull(port);
            ContentResponse response = client.GET("http://127.0.0.1:" + port + "/test-webapp-resources/hello/a");
            String content = response.getContentAsString();
            assertEquals(HttpStatus.OK_200, response.getStatus());
            //check that fake.properties is only listed once from the classpath
            assertEquals(content.indexOf("fake.properties"), content.lastIndexOf("fake.properties"));
        }
        finally
        {
            client.stop();
        }
    }
}
