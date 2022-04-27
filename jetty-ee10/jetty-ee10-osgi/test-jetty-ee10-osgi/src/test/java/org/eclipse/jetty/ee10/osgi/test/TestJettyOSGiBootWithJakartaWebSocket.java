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
import java.util.logging.Logger;
import javax.inject.Inject;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.Test;
import org.junit.jupiter.api.Disabled;
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
@Disabled //TODO
@RunWith(PaxExam.class)
public class TestJettyOSGiBootWithJakartaWebSocket
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
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-with-jakarta-websocket.xml"));
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
        startBundle(bundleContext, "org.eclipse.jetty.websocket.jakarta.common");
        startBundle(bundleContext, "org.eclipse.jetty.websocket.jakarta.client");
        startBundle(bundleContext, "org.eclipse.jetty.websocket.jakarta.server");
        startBundle(bundleContext, "org.eclipse.jetty.demos.webapp");

        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            TestOSGiUtil.diagnoseBundles(bundleContext);

        String port = System.getProperty("boot.jakarta.websocket.port");
        assertNotNull(port);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        assertNotNull(container);

        Logger log = Logger.getLogger(this.getClass().getName());

        SimpleJakartaWebSocket socket = new SimpleJakartaWebSocket();
        URI uri = new URI("ws://127.0.0.1:" + port + "/jakarta.websocket/");
        log.info("Attempting to connect to " + uri);
        try (Session session = container.connectToServer(socket, uri))
        {
            log.info("Got session: " + session);
            RemoteEndpoint.Basic remote = session.getBasicRemote();
            log.info("Got remote: " + remote);
            String msg = "Foo";
            remote.sendText(msg);
            log.info("Send message");
            assertTrue(socket.messageLatch.await(2, TimeUnit.SECONDS)); // give remote 1 second to respond
        }
        finally
        {
            assertTrue(socket.closeLatch.await(2, TimeUnit.SECONDS)); // give remote 1 second to acknowledge response
        }
    }

    private void startBundle(BundleContext bundleContext, String symbolicName) throws BundleException
    {
        Bundle bundle = TestOSGiUtil.getBundle(bundleContext, symbolicName);
        assertNotNull("Bundle[" + symbolicName + "] should exist", bundle);
        bundle.start();
    }
}
