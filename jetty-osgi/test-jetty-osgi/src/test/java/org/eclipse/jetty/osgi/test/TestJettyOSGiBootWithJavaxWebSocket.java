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

package org.eclipse.jetty.osgi.test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.websocket.ContainerProvider;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;

/**
 * Test using websocket in osgi
 */
@RunWith(PaxExam.class)
public class TestJettyOSGiBootWithJavaxWebSocket
{
    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
        ArrayList<Option> options = new ArrayList<>();
        
        options.addAll(TestOSGiUtil.configurePaxExamLogging());
        
        // options.add(TestOSGiUtil.optionalRemoteDebug());
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-with-javax-websocket.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.sql.*", "javax.xml.*", "javax.activation.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));

        TestOSGiUtil.coreJettyDependencies(options);
        TestOSGiUtil.coreJspDependencies(options);
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-java-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());
        options.addAll(testJettyWebApp());
        options.addAll(extraDependencies());
        return options.toArray(new Option[0]);
    }

    public static List<Option> testJettyWebApp()
    {
        List<Option> res = new ArrayList<>();
        //test webapp bundle
        res.add(mavenBundle().groupId("org.eclipse.jetty.demos").artifactId("demo-jetty-webapp").classifier("webbundle").versionAsInProject().noStart());
        return res;
    }

    public static List<Option> extraDependencies()
    {
        List<Option> res = new ArrayList<>();
        //Need an earlier version of bndlib because of tinybundles
        res.add(mavenBundle().groupId("biz.aQute.bnd").artifactId("biz.aQute.bndlib").version("3.5.0").start());
        res.add(mavenBundle().groupId("org.ops4j.pax.tinybundles").artifactId("tinybundles").versionAsInProject().start());
        return res;
    }

    @Test
    public void testWebsocket() throws Exception
    {
        startBundle(bundleContext, "org.eclipse.jetty.websocket.javax.common");
        startBundle(bundleContext, "org.eclipse.jetty.websocket.javax.client");
        startBundle(bundleContext, "org.eclipse.jetty.websocket.javax.server");
        startBundle(bundleContext, "org.eclipse.jetty.demos.webapp");

        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            TestOSGiUtil.diagnoseBundles(bundleContext);

        String port = System.getProperty("boot.javax.websocket.port");
        assertNotNull(port);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        assertNotNull(container);

        SimpleJavaxWebSocket socket = new SimpleJavaxWebSocket();
        URI uri = new URI("ws://127.0.0.1:" + port + "/javax.websocket/");
        try (Session session = container.connectToServer(socket, uri))
        {
            RemoteEndpoint.Basic remote = session.getBasicRemote();
            String msg = "Foo";
            remote.sendText(msg);
            assertTrue(socket.messageLatch.await(1, TimeUnit.SECONDS)); // give remote 1 second to respond
        }
        finally
        {
            assertTrue(socket.closeLatch.await(1, TimeUnit.SECONDS)); // give remote 1 second to acknowledge response
        }
    }

    private void startBundle(BundleContext bundleContext, String symbolicName) throws BundleException
    {
        Bundle bundle = TestOSGiUtil.getBundle(bundleContext, symbolicName);
        assertNotNull("Bundle[" + symbolicName + "] should exist", bundle);
        bundle.start();
    }
}
