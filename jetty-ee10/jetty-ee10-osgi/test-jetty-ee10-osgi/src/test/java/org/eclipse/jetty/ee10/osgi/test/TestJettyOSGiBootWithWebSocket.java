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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import org.eclipse.jetty.ee10.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
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

/**
 *
 */
@RunWith(PaxExam.class)
public class TestJettyOSGiBootWithWebSocket
{
    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
        ArrayList<Option> options = new ArrayList<>();
        
        options.addAll(TestOSGiUtil.configurePaxExamLogging());
        
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-with-websocket.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.sql.*", "javax.xml.*", "javax.activation.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));

        TestOSGiUtil.coreJettyDependencies(options);
        TestOSGiUtil.coreJspDependencies(options);
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-java-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());
        options.addAll(testJettyWebApp());
        return options.toArray(new Option[0]);
    }

    public static List<Option> testJettyWebApp()
    {
        List<Option> res = new ArrayList<>();
        //test webapp bundle
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee10.demos").artifactId("jetty-ee10-demo-jetty-webapp").classifier("webbundle").versionAsInProject());
        return res;
    }

    public void debugBundles()
    {

    }

    @Test
    public void testWebsocket() throws Exception
    {
        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            TestOSGiUtil.diagnoseBundles(bundleContext);

        String port = System.getProperty("boot.websocket.port");
        assertNotNull(port);

        URI uri = new URI("ws://127.0.0.1:" + port + "/ee10-demo-jetty/ws/foo");
        WebSocketClient client = new WebSocketClient();
        try
        {
            client.start();
            SimpleEchoSocket socket = new SimpleEchoSocket();
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
