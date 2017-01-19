//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;

import org.codehaus.plexus.util.IOUtil;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.junit.Ignore;
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
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

/**
 * TestJettyOSGiBootWithBundle
 * 
 * Tests reading config from a bundle and loading clases from it
 * 
 * Tests the ServiceContextProvider.
 * 
 */
@RunWith(PaxExam.class)
public class TestJettyOSGiBootWithBundle
{
    private static final String TEST_JETTY_HOME_BUNDLE = "test-jetty-xml-bundle";


	private static final String LOG_LEVEL = "WARN";


    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure() throws IOException
    {
        ArrayList<Option> options = new ArrayList<Option>();
        options.add(CoreOptions.junitBundles());
        options.addAll(configureJettyHomeAndPort("jetty-http.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*"));
        options.addAll(TestJettyOSGiBootCore.coreJettyDependencies());

        options.addAll(Arrays.asList(options(systemProperty("pax.exam.logging").value("none"))));
        options.addAll(Arrays.asList(options(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(LOG_LEVEL))));
        options.addAll(Arrays.asList(options(systemProperty("org.eclipse.jetty.LEVEL").value(LOG_LEVEL))));
        TinyBundle bundle = TinyBundles.bundle();
        bundle.add(SomeCustomBean.class);
        bundle.set( Constants.BUNDLE_SYMBOLICNAME, TEST_JETTY_HOME_BUNDLE );
        File etcFolder = new File("src/test/config/etc");
        bundle.add("jettyhome/etc/jetty-http.xml", new FileInputStream(new File(etcFolder, "jetty-http.xml")));
        bundle.add("jettyhome/etc/jetty-with-custom-class.xml", new FileInputStream(new File(etcFolder, "jetty-with-custom-class.xml")));
		options.add(CoreOptions.streamBundle(bundle.build()).startLevel(1));
        return options.toArray(new Option[options.size()]);
    }

    public static List<Option> configureJettyHomeAndPort(String jettySelectorFileName)
    {
        List<Option> options = new ArrayList<Option>();
        options.add(systemProperty(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS).value("etc/jetty-with-custom-class.xml,etc/jetty-http.xml"));
        options.add(systemProperty("jetty.http.port").value(String.valueOf(TestJettyOSGiBootCore.DEFAULT_HTTP_PORT)));
        options.add(systemProperty("jetty.ssl.port").value(String.valueOf(TestJettyOSGiBootCore.DEFAULT_SSL_PORT)));
        options.add(systemProperty("jetty.home.bundle").value(TEST_JETTY_HOME_BUNDLE));
        return options;
    }

    @Ignore
    @Test
    public void assertAllBundlesActiveOrResolved()
    {
        TestOSGiUtil.assertAllBundlesActiveOrResolved(bundleContext);
    }


    /**
     */
    @Test
    public void testContextHandlerAsOSGiService() throws Exception
    {
        // now test the context
        HttpClient client = new HttpClient();
        try
        {
            client.start();
            ContentResponse response = client.GET("http://127.0.0.1:" + TestJettyOSGiBootCore.DEFAULT_HTTP_PORT);
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
