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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.osgi.framework.BundleContext;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

/**
 *
 */
@RunWith(PaxExam.class)

public class TestJettyOSGiBootWithWebSocket
{
    private static final String LOG_LEVEL = "WARN";

    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
        ArrayList<Option> options = new ArrayList<>();
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-with-websocket.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.sql.*", "javax.xml.*", "javax.activation.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));

        options.addAll(TestOSGiUtil.coreJettyDependencies());
        options.add(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(LOG_LEVEL));
        options.add(systemProperty("org.eclipse.jetty.LEVEL").value(LOG_LEVEL));
        options.addAll(jspDependencies());
        options.addAll(annotationDependencies());
        return options.toArray(new Option[options.size()]);
    }

    public static List<Option> jspDependencies()
    {
        return TestOSGiUtil.jspDependencies();
    }

    public static List<Option> annotationDependencies()
    {
        List<Option> res = new ArrayList<>();
        //test webapp bundle
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("test-jetty-webapp").classifier("webbundle").versionAsInProject());
        return res;
    }

    public void assertAllBundlesActiveOrResolved()
    {
        TestOSGiUtil.assertAllBundlesActiveOrResolved(bundleContext);
        TestOSGiUtil.debugBundles(bundleContext);
    }

    @Test
    public void testWebsocket() throws Exception
    {
        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            assertAllBundlesActiveOrResolved();

        String port = System.getProperty("boot.websocket.port");
        assertNotNull(port);

        URI uri = new URI("ws://127.0.0.1:" + port + "/ws/foo");

        WebSocketClient client = new WebSocketClient();

        try
        {

            SimpleEchoSocket socket = new SimpleEchoSocket();

            client.start();

            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setSubProtocols("chat");
            client.connect(socket, uri, request);
            // wait for closed socket connection.
            assertTrue(socket.awaitClose(5, TimeUnit.SECONDS));
        }
        finally
        {
            client.stop();
        }
    }
}
