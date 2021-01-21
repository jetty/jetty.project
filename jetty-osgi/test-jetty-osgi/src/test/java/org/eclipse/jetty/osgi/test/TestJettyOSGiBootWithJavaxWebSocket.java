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

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.websocket.ContainerProvider;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import aQute.bnd.osgi.Constants;
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

/**
 * Test using websocket in osgi
 */
@RunWith(PaxExam.class)
public class TestJettyOSGiBootWithJavaxWebSocket
{
    private static final String LOG_LEVEL = "WARN";

    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
        ArrayList<Option> options = new ArrayList<>();
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-with-javax-websocket.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.sql.*", "javax.xml.*", "javax.activation.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));

        options.addAll(TestOSGiUtil.coreJettyDependencies());
        options.add(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(LOG_LEVEL));
        options.add(systemProperty("org.eclipse.jetty.LEVEL").value(LOG_LEVEL));
        options.addAll(jspDependencies());
        options.addAll(annotationDependencies());
        options.addAll(extraDependencies());
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

    public static List<Option> extraDependencies()
    {
        List<Option> res = new ArrayList<>();
        //Need an earlier version of bndlib because of tinybundles
        res.add(mavenBundle().groupId("biz.aQute.bnd").artifactId("biz.aQute.bndlib").version("3.5.0").start());
        res.add(mavenBundle().groupId("org.ops4j.pax.tinybundles").artifactId("tinybundles").versionAsInProject().start());
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

        //this is necessary because the javax.websocket-api jar does not have manifest headers
        //that allow it to use ServiceLoader in osgi, this corrects that defect
        TinyBundle bundle = TinyBundles.bundle();
        bundle.set(Constants.FRAGMENT_HOST, "javax.websocket-api");
        bundle.set(Constants.REQUIRE_CAPABILITY,
            "osgi.serviceloader;filter:=\"(osgi.serviceloader=javax.websocket.ContainerProvider)\";resolution:=optional;cardinality:=multiple, osgi.extender; filter:=\"(osgi.extender=osgi.serviceloader.processor)\"");
        bundle.set(Constants.BUNDLE_SYMBOLICNAME, "javax.websocket.api.fragment");
        InputStream is = bundle.build(TinyBundles.withBnd());
        bundleContext.installBundle("dummyLocation", is);

        Bundle websocketApiBundle = TestOSGiUtil.getBundle(bundleContext, "javax.websocket-api");
        assertNotNull(websocketApiBundle);
        websocketApiBundle.update();
        websocketApiBundle.start();

        Bundle javaxWebsocketClient = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.websocket.javax.websocket");
        assertNotNull(javaxWebsocketClient);
        javaxWebsocketClient.start();

        Bundle javaxWebsocketServer = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.websocket.javax.websocket.server");
        assertNotNull(javaxWebsocketServer);
        javaxWebsocketServer.start();

        String port = System.getProperty("boot.javax.websocket.port");
        assertNotNull(port);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        assertNotNull(container);

        SimpleJavaxWebSocket socket = new SimpleJavaxWebSocket();
        URI uri = new URI("ws://127.0.0.1:" + port + "/javax.websocket/");
        Session session = container.connectToServer(socket, uri);
        try
        {
            RemoteEndpoint.Basic remote = session.getBasicRemote();
            String msg = "Foo";
            remote.sendText(msg);
            assertTrue(socket.messageLatch.await(1, TimeUnit.SECONDS)); // give remote 1 second to respond
        }
        finally
        {
            session.close();
            assertTrue(socket.closeLatch.await(1, TimeUnit.SECONDS)); // give remote 1 second to acknowledge response
        }
    }
}
