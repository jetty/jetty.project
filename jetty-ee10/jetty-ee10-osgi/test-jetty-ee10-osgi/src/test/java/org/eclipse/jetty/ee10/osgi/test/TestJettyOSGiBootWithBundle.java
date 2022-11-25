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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.osgi.OSGiServerConstants;
import org.junit.Test;
import org.junit.jupiter.api.Disabled;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

/**
 * TestJettyOSGiBootWithBundle
 *
 * Tests reading config from a bundle and loading classes from it
 *
 * Tests the ServiceContextProvider.
 */
@Disabled //TODO
//@RunWith(PaxExam.class)
public class TestJettyOSGiBootWithBundle
{
    private static final String TEST_JETTY_HOME_BUNDLE = "test-jetty-xml-bundle";

    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure() throws IOException
    {
        ArrayList<Option> options = new ArrayList<>();
        
        options.addAll(TestOSGiUtil.configurePaxExamLogging());
        
        options.add(CoreOptions.junitBundles());
        options.addAll(configureJettyHomeAndPort());
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*"));
        TestOSGiUtil.coreJettyDependencies(options);
        TestOSGiUtil.coreJspDependencies(options);
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-java-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());

        TinyBundle bundle = TinyBundles.bundle();
        bundle.add(SomeCustomBean.class);
        bundle.set(Constants.BUNDLE_SYMBOLICNAME, TEST_JETTY_HOME_BUNDLE);
        File etcFolder = new File("src/test/config/etc");
        bundle.add("jettyhome/etc/jetty-http-boot-with-bundle.xml", new FileInputStream(new File(etcFolder, "jetty-http-boot-with-bundle.xml")));
        bundle.add("jettyhome/etc/jetty-with-custom-class.xml", new FileInputStream(new File(etcFolder, "jetty-with-custom-class.xml")));
        options.add(CoreOptions.streamBundle(bundle.build()).startLevel(1));
        return options.toArray(new Option[0]);
    }

    public static List<Option> configureJettyHomeAndPort()
    {
        List<Option> options = new ArrayList<>();
        options.add(systemProperty(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS).value("etc/jetty-with-custom-class.xml,etc/jetty-http-boot-with-bundle.xml"));
        options.add(systemProperty("jetty.http.port").value("0"));
        // TODO: FIXME: options.add(systemProperty("jetty.ssl.port").value(String.valueOf(TestOSGiUtil.DEFAULT_SSL_PORT)));
        options.add(systemProperty("jetty.home.bundle").value(TEST_JETTY_HOME_BUNDLE));
        return options;
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
            String tmp = System.getProperty("boot.bundle.port");
            assertNotNull(tmp);
            int port = Integer.valueOf(tmp.trim());
            ContentResponse response = client.GET("http://127.0.0.1:" + port);
            assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
            String content = new String(response.getContent());
            assertNotNull(content);
        }
        finally
        {
            client.stop();
        }
    }
}
