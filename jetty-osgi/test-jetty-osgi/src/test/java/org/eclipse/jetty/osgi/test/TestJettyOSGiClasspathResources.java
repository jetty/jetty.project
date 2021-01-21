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
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import javax.inject.Inject;

import aQute.bnd.osgi.Constants;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
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
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

/**
 * TestJettyOSGiClasspathResources
 *
 */

@RunWith(PaxExam.class)
public class TestJettyOSGiClasspathResources
{
    private static final String LOG_LEVEL = "WARN";

    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {        
        ArrayList<Option> options = new ArrayList<>();
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-with-resources.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*", "javax.activation.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));

        options.addAll(TestOSGiUtil.coreJettyDependencies());
        //Note: we have to back down the version of bnd used here because tinybundles expects only this version
        options.add(mavenBundle().groupId("biz.aQute.bnd").artifactId("biz.aQute.bndlib").version("3.5.0").start());
        options.add(mavenBundle().groupId("org.ops4j.pax.tinybundles").artifactId("tinybundles").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("test-jetty-osgi-webapp-resources").type("war").versionAsInProject());
        options.add(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(LOG_LEVEL));
        options.add(systemProperty("org.eclipse.jetty.LEVEL").value(LOG_LEVEL));
        options.add(CoreOptions.cleanCaches(true));   
        return options.toArray(new Option[options.size()]);
    }
    
    @Test
    public void testWebInfResourceNotOnBundleClasspath() throws Exception
    {
        //Test the test-jetty-osgi-webapp-resource bundle with a
        //Bundle-Classpath that does NOT include WEB-INF/classes
        HttpClient client = new HttpClient();
        try
        {
            client.start();

            String port = System.getProperty("boot.resources.port");
            assertNotNull(port);
            ContentResponse response = client.GET("http://127.0.0.1:" + port + "/hello/a");
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
        Bundle webappBundle = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.osgi.webapp.resources");

        //Make a new bundle based on the test-jetty-osgi-webapp-resources war bundle, but
        //change the Bundle-Classpath so that WEB-INF/classes IS on the bundle classpath
        File warFile = new File("target/test-jetty-osgi-webapp-resources.war");
        TinyBundle tiny = TinyBundles.bundle();
        tiny.read(new FileInputStream(warFile));
        tiny.set(Constants.BUNDLE_CLASSPATH, "., WEB-INF/classes/");
        tiny.set(Constants.BUNDLE_SYMBOLICNAME, "org.eclipse.jetty.osgi.webapp.resources.alt");
        InputStream is = tiny.build(TinyBundles.withBnd());
        bundleContext.installBundle("dummyAltLocation", is);

        webappBundle.stop();
        Bundle bundle = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.osgi.webapp.resources.alt");
        bundle.start();
        
        HttpClient client = new HttpClient();
        try
        {
            client.start();

            String port = System.getProperty("boot.resources.port");
            assertNotNull(port);
            ContentResponse response = client.GET("http://127.0.0.1:" + port + "/hello/a");
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
