//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;

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
        options.addAll(configureJettyHomeAndPort());
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*"));
        options.addAll(TestOSGiUtil.coreJettyDependencies());

        options.addAll(Arrays.asList(options(systemProperty("pax.exam.logging").value("none"))));
        options.addAll(Arrays.asList(options(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(LOG_LEVEL))));
        options.addAll(Arrays.asList(options(systemProperty("org.eclipse.jetty.LEVEL").value(LOG_LEVEL))));
        TinyBundle bundle = TinyBundles.bundle();
        bundle.add(SomeCustomBean.class);
        bundle.set( Constants.BUNDLE_SYMBOLICNAME, TEST_JETTY_HOME_BUNDLE );
        File etcFolder = new File("src/test/config/etc");
        bundle.add("jettyhome/etc/jetty-http-boot-with-bundle.xml", new FileInputStream(new File(etcFolder, "jetty-http-boot-with-bundle.xml")));
        bundle.add("jettyhome/etc/jetty-with-custom-class.xml", new FileInputStream(new File(etcFolder, "jetty-with-custom-class.xml")));
		options.add(CoreOptions.streamBundle(bundle.build()).startLevel(1));
        return options.toArray(new Option[options.size()]);
    }

    public static List<Option> configureJettyHomeAndPort()
    {
        List<Option> options = new ArrayList<Option>();
        options.add(systemProperty(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS).value("etc/jetty-with-custom-class.xml,etc/jetty-http-boot-with-bundle.xml"));
        options.add(systemProperty("jetty.http.port").value("0"));
        // TODO: FIXME: options.add(systemProperty("jetty.ssl.port").value(String.valueOf(TestOSGiUtil.DEFAULT_SSL_PORT)));
        options.add(systemProperty("jetty.home.bundle").value(TEST_JETTY_HOME_BUNDLE));
        return options;
    }

    @Test
    public void assertAllBundlesActiveOrResolved()
    {
        TestOSGiUtil.assertAllBundlesActiveOrResolved(bundleContext);
    }


    /**
     */
    @Ignore
    @Test
    public void testContextHandlerAsOSGiService() throws Exception
    {
        // now test the context
        HttpClient client = new HttpClient();
        try
        {
            client.start();
            String tmp = System.getProperty("boot.bundle.port");
            assertNotNull(tmp);
            int port = Integer.valueOf(tmp.trim()).intValue();
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
