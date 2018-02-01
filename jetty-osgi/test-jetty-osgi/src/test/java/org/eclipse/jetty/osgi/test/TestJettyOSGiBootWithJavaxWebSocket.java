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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

import java.io.File;
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
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import aQute.bnd.osgi.Constants;

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
        ArrayList<Option> options = new ArrayList<Option>();
        options.add(CoreOptions.junitBundles());
        options.addAll(configureJettyHomeAndPort("jetty-http-boot-with-javax-websocket.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.sql.*","javax.xml.*", "javax.activation.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res","com.sun.org.apache.xml.internal.utils",
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

    public static List<Option> configureJettyHomeAndPort(String jettySelectorFileName)
    {
        File etc = new File("src/test/config/etc");
      
        List<Option> options = new ArrayList<Option>();
        StringBuffer xmlConfigs = new StringBuffer();
        xmlConfigs.append(new File(etc, "jetty.xml").toURI());
        xmlConfigs.append(";");
        xmlConfigs.append(new File(etc,jettySelectorFileName).toURI());
        xmlConfigs.append(";");
        xmlConfigs.append(new File(etc, "jetty-ssl.xml").toURI());
        xmlConfigs.append(";");
        xmlConfigs.append(new File(etc, "jetty-https.xml").toURI());
        xmlConfigs.append(";");
        xmlConfigs.append(new File(etc, "jetty-deployer.xml").toURI());
        xmlConfigs.append(";");
        xmlConfigs.append(new File(etc, "jetty-testrealm.xml").toURI());
        
        options.add(systemProperty(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS).value(xmlConfigs.toString()));
        options.add(systemProperty("jetty.http.port").value("0"));
        options.add(systemProperty("jetty.ssl.port").value(String.valueOf(TestOSGiUtil.DEFAULT_SSL_PORT)));
        options.add(systemProperty("jetty.home").value(etc.getParentFile().getAbsolutePath()));
        return options;
    }

    public static List<Option> jspDependencies()
    {
        return TestOSGiUtil.jspDependencies();
    }

    public static List<Option> annotationDependencies()
    {
        List<Option> res = new ArrayList<Option>();
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "javax.mail.glassfish" ).version( "1.4.1.v201005082020" ).noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.tests").artifactId("test-mock-resources").versionAsInProject());
        //test webapp bundle
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("test-jetty-webapp").classifier("webbundle").versionAsInProject());
        return res;
    }
    public static List<Option> extraDependencies()
    {
        List<Option> res = new ArrayList<Option>();
        res.add(mavenBundle().groupId("biz.aQute.bnd").artifactId("bndlib").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ops4j.pax.tinybundles").artifactId("tinybundles").version("2.1.1").start());        
        return res;
    }


    @Ignore
    public void assertAllBundlesActiveOrResolved()
    {
        TestOSGiUtil.assertAllBundlesActiveOrResolved(bundleContext);
        TestOSGiUtil.debugBundles(bundleContext);
    }
    


    @Test
    public void testWebsocket() throws Exception
    {
        //this is necessary because the javax.websocket-api jar does not have manifest headers
        //that allow it to use ServiceLoader in osgi, this corrects that defect
        TinyBundle bundle = TinyBundles.bundle();
        bundle.set(Constants.FRAGMENT_HOST, "javax.websocket-api");
        bundle.set(Constants.REQUIRE_CAPABILITY, 
                   "osgi.serviceloader;filter:=\"(osgi.serviceloader=javax.websocket.ContainerProvider)\";resolution:=optional;cardinality:=multiple, osgi.extender; filter:=\"(osgi.extender=osgi.serviceloader.processor)\"");
        bundle.set(Constants.BUNDLE_SYMBOLICNAME, "javax.websocket.api.fragment");
        InputStream is = bundle.build(TinyBundles.withBnd());        
        Bundle installed = bundleContext.installBundle("dummyLocation", is);
        
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
        
        
        String tmp = System.getProperty("boot.javax.websocket.port");
        assertNotNull(tmp);
        int port = Integer.valueOf(tmp.trim()).intValue();

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        assertNotNull(container);

        SimpleJavaxWebSocket socket = new SimpleJavaxWebSocket();
        URI uri = new URI("ws://127.0.0.1:" + port+"/javax.websocket/");
        Session session = container.connectToServer(socket,uri);
        try
        {
            RemoteEndpoint.Basic remote = session.getBasicRemote();
            String msg = "Foo";
            remote.sendText(msg);
            assertTrue(socket.messageLatch.await(1,TimeUnit.SECONDS)); // give remote 1 second to respond
        }
        finally
        {
            session.close();
            assertTrue(socket.closeLatch.await(1,TimeUnit.SECONDS)); // give remote 1 second to acknowledge response
        }
    }
}
