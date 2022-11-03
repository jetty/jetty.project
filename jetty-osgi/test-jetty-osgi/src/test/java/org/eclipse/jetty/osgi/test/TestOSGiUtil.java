//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.url.mvn.internal.AetherBasedResolver;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

/**
 * Helper methods for pax-exam tests
 */
public class TestOSGiUtil
{

    public static final String BUNDLE_DEBUG = "bundle.debug";

    public static List<Option> configureJettyHomeAndPort(boolean ssl, String jettySelectorFileName)
    {
        File etc = new File(FS.separators("src/test/config/etc"));

        List<Option> options = new ArrayList<>();
        StringBuffer xmlConfigs = new StringBuffer();
        xmlConfigs.append(new File(etc, "jetty.xml").toURI());
        xmlConfigs.append(";");
        if (ssl)
        {
            options.add(CoreOptions.systemProperty("jetty.ssl.port").value("0"));
            xmlConfigs.append(new File(etc, "jetty-ssl.xml").toURI());
            xmlConfigs.append(";");
            xmlConfigs.append(new File(etc, "jetty-alpn.xml").toURI());
            xmlConfigs.append(";");
            xmlConfigs.append(new File(etc, "jetty-https.xml").toURI());
            xmlConfigs.append(";");
        }
        xmlConfigs.append(new File(etc, jettySelectorFileName).toURI());
        xmlConfigs.append(";");
        xmlConfigs.append(new File(etc, "jetty-deploy.xml").toURI());
        xmlConfigs.append(";");
        xmlConfigs.append(new File(etc, "jetty-testrealm.xml").toURI());

        options.add(systemProperty(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS).value(xmlConfigs.toString()));
        options.add(systemProperty("jetty.http.port").value("0"));
        options.add(systemProperty("jetty.home").value(etc.getParentFile().getAbsolutePath()));
        options.add(systemProperty("jetty.base").value(etc.getParentFile().getAbsolutePath()));
        return options;
    }

    public static List<Option> provisionCoreJetty()
    {
        List<Option> res = new ArrayList<>();
        // get the jetty home config from the osgi boot bundle.
        res.add(CoreOptions.systemProperty("jetty.home.bundle").value("org.eclipse.jetty.osgi.boot"));
        res.addAll(coreJettyDependencies());
        return res;
    }

    public static Option optionalRemoteDebug()
    {
        return CoreOptions.when(Boolean.getBoolean("pax.exam.debug.remote"))
            .useOptions(CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"));
    }

    public static List<Option> coreJettyDependencies()
    {
        AetherBasedResolver l;
        List<Option> res = new ArrayList<>();
        res.add(systemProperty("bundle.debug").value(Boolean.toString(Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))));
        String mavenRepoPath = System.getProperty("mavenRepoPath");
        if (!StringUtil.isBlank(mavenRepoPath))
        {
            res.add(systemProperty("org.ops4j.pax.url.mvn.localRepository").value(mavenRepoPath));
            res.add(systemProperty("org.ops4j.pax.url.mvn.defaultRepositories").value("file://" + mavenRepoPath + "@id=local.repo"));
            res.add(systemProperty("org.ops4j.pax.url.mvn.useFallbackRepositories").value(Boolean.FALSE.toString()));
            res.add(systemProperty("org.ops4j.pax.url.mvn.repositories").value("+https://repo1.maven.org/maven2@id=maven.central.repo"));
        }
        String settingsFilePath = System.getProperty("settingsFilePath");
        if (!StringUtil.isBlank(settingsFilePath))
        {
            res.add(systemProperty("org.ops4j.pax.url.mvn.settings").value(System.getProperty("settingsFilePath")));
        }
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.cm").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.component").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.event").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.function").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.function").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.promise").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.measurement").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.position").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.tracker").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.xml").versionAsInProject());
        res.add(mavenBundle().groupId("org.eclipse.platform").artifactId("org.eclipse.osgi.util").versionAsInProject());
        res.add(mavenBundle().groupId("org.eclipse.platform").artifactId("org.eclipse.osgi.services").versionAsInProject());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-commons").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-tree").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-analysis").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-util").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.apache.aries.spifly").artifactId("org.apache.aries.spifly.dynamic.bundle").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.toolchain").artifactId("jetty-osgi-servlet-api").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("javax.annotation").artifactId("javax.annotation-api").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.apache.geronimo.specs").artifactId("geronimo-jta_1.1_spec").version("1.1.1").start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-util").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-util-ajax").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-deploy").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-servlet").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-http").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-xml").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-webapp").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-io").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-continuation").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-security").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-servlets").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-client").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-jndi").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-plus").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-annotations").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-api").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-common").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-servlet").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-client").versionAsInProject().start());
        res.add(mavenBundle().groupId("javax.websocket").artifactId("javax.websocket-api").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("javax-websocket-client-impl").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("javax-websocket-server-impl").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("jetty-osgi-boot").versionAsInProject().start());
        return res;
    }

    public static List<Option> consoleDependencies()
    {
        List<Option> res = new ArrayList<>();
        res.add(systemProperty("osgi.console").value("6666"));
        res.add(systemProperty("osgi.console.enable.builtin").value("true"));
        return res;
    }

    public static List<Option> jspDependencies()
    {
        List<Option> res = new ArrayList<>();

        //jetty jsp bundles  
        res.add(mavenBundle().groupId("org.eclipse.jetty.orbit").artifactId("javax.servlet.jsp.jstl").versionAsInProject());
        res.add(mavenBundle().groupId("org.mortbay.jasper").artifactId("apache-el").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.mortbay.jasper").artifactId("apache-jsp").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("apache-jsp").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.glassfish.web").artifactId("javax.servlet.jsp.jstl").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jdt").artifactId("ecj").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("jetty-osgi-boot-jsp").versionAsInProject().noStart());
        return res;
    }

    public static List<Option> httpServiceJetty()
    {
        List<Option> res = new ArrayList<>();
        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("jetty-httpservice").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.equinox.http").artifactId("servlet").versionAsInProject().start());
        return res;
    }

    protected static Bundle getBundle(BundleContext bundleContext, String symbolicName)
    {
        Map<String, Bundle> bundles = new HashMap<>();
        for (Bundle b : bundleContext.getBundles())
        {
            Bundle prevBundle = bundles.put(b.getSymbolicName(), b);
            String err = prevBundle != null ? "2 versions of the bundle " + b.getSymbolicName() +
                    " " +
                    b.getHeaders().get("Bundle-Version") +
                    " and " +
                    prevBundle.getHeaders().get("Bundle-Version") : "";
            assertNull(err, prevBundle);
        }
        return bundles.get(symbolicName);
    }

    protected static void assertActiveBundle(BundleContext bundleContext, String symbolicName) throws Exception
    {
        Bundle b = getBundle(bundleContext, symbolicName);
        assertNotNull(b);
        assertEquals(b.getSymbolicName() + " must be active.", Bundle.ACTIVE, b.getState());
    }

    protected static void assertActiveOrResolvedBundle(BundleContext bundleContext, String symbolicName) throws Exception
    {
        Bundle b = getBundle(bundleContext, symbolicName);
        assertNotNull(b);
        if (b.getHeaders().get("Fragment-Host") == null)
            diagnoseNonActiveOrNonResolvedBundle(b);
        assertTrue(b.getSymbolicName() + " must be active or resolved. It was " + b.getState(),
            b.getState() == Bundle.ACTIVE || b.getState() == Bundle.RESOLVED);
    }

    protected static void assertAllBundlesActiveOrResolved(BundleContext bundleContext)
    {
        for (Bundle b : bundleContext.getBundles())
        {
            if (b.getState() == Bundle.INSTALLED)
            {
                diagnoseNonActiveOrNonResolvedBundle(b);
            }
            assertTrue("Bundle: " + b +
                    " (state should be " +
                    "ACTIVE[" +
                    Bundle.ACTIVE +
                    "] or RESOLVED[" +
                    Bundle.RESOLVED +
                    "]" +
                    ", but was [" +
                    b.getState() +
                    "])", (b.getState() == Bundle.ACTIVE) || (b.getState() == Bundle.RESOLVED));
        }
    }

    protected static boolean diagnoseNonActiveOrNonResolvedBundle(Bundle b)
    {
        if (b.getState() != Bundle.ACTIVE && b.getHeaders().get("Fragment-Host") == null)
        {
            try
            {
                System.err.println("Trying to start the bundle " + b.getSymbolicName() + " that was supposed to be active or resolved.");
                b.start();
                System.err.println(b.getSymbolicName() + " did start");
                return true;
            }
            catch (Throwable t)
            {
                System.err.println(b.getSymbolicName() + " failed to start");
                t.printStackTrace(System.err);
                return false;
            }
        }
        System.err.println(b.getSymbolicName() + " was already started");
        return false;
    }

    protected static void debugBundles(BundleContext bundleContext)
    {
        Map<String, Bundle> bundlesIndexedBySymbolicName = new HashMap<String, Bundle>();
        System.err.println("Active " + Bundle.ACTIVE);
        System.err.println("RESOLVED " + Bundle.RESOLVED);
        System.err.println("INSTALLED " + Bundle.INSTALLED);
        for (Bundle b : bundleContext.getBundles())
        {
            bundlesIndexedBySymbolicName.put(b.getSymbolicName(), b);
            System.err.println("    " + b.getBundleId() + " " + b.getSymbolicName() + " " + b.getLocation() + " " + b.getVersion() + " " + b.getState());
        }
    }

    @SuppressWarnings("rawtypes")
    protected static ServiceReference[] getServices(String service, BundleContext bundleContext) throws Exception
    {
        return bundleContext.getAllServiceReferences(service, null);
    }

    protected static SslContextFactory newClientSslContextFactory()
    {
        SslContextFactory sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        return sslContextFactory;
    }

    protected static void testHttpServiceGreetings(BundleContext bundleContext, String protocol, int port) throws Exception
    {
        assertActiveBundle(bundleContext, "org.eclipse.jetty.osgi.boot");

        assertActiveBundle(bundleContext, "org.eclipse.jetty.osgi.httpservice");
        assertActiveBundle(bundleContext, "org.eclipse.equinox.http.servlet");

        // in the OSGi world this would be bad code and we should use a bundle
        // tracker.
        // here we purposely want to make sure that the httpService is actually
        // ready.
        ServiceReference<?> sr = bundleContext.getServiceReference(HttpService.class.getName());
        assertNotNull("The httpServiceOSGiBundle is started and should " + "have deployed a service reference for HttpService", sr);
        HttpService http = (HttpService)bundleContext.getService(sr);
        http.registerServlet("/greetings", new HttpServlet()
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.getWriter().write("Hello");
            }
        }, null, null);

        // now test the servlet
        HttpClient client = protocol.equals("https") ? new HttpClient(newClientSslContextFactory()) : new HttpClient();
        try
        {
            client.start();
            ContentResponse response = client.GET(protocol + "://127.0.0.1:" + port + "/greetings");
            assertEquals(HttpStatus.OK_200, response.getStatus());

            String content = new String(response.getContent());
            assertEquals("Hello", content);
        }
        finally
        {
            client.stop();
        }
    }
}
