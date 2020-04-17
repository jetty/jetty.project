//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.osgi.test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.WrappedUrlProvisionOption.OverwriteMode;
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.CoreOptions.wrappedBundle;

/**
 * Helper methods for pax-exam tests
 */
public class TestOSGiUtil
{
    public static final String BUNDLE_DEBUG = "bundle.debug";
    
    /**
     * Null FragmentActivator for the fake bundle
     * that exposes src/test/resources/jetty-logging.properties in
     * the osgi container
     */
    public static class FragmentActivator implements BundleActivator
    {
        @Override
        public void start(BundleContext context) throws Exception
        {
        }

        @Override
        public void stop(BundleContext context) throws Exception
        {
        }
    }

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
        xmlConfigs.append(new File(etc, "jetty-deployer.xml").toURI());
        xmlConfigs.append(";");
        xmlConfigs.append(new File(etc, "jetty-testrealm.xml").toURI());

        options.add(systemProperty(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS).value(xmlConfigs.toString()));
        options.add(systemProperty("jetty.http.port").value("0"));
        options.add(systemProperty("jetty.home").value(etc.getParentFile().getAbsolutePath()));
        options.add(systemProperty("jetty.base").value(etc.getParentFile().getAbsolutePath()));
        return options;
    }
    
    public static List<Option> configurePaxExamLogging()
    {
        //sort out logging from the pax-exam environment
        List<Option> options = new ArrayList<>();
        options.add(systemProperty("pax.exam.logging").value("none"));
        String paxExamLogLevel = System.getProperty("pax.exam.LEVEL", "WARN");
        options.add(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(paxExamLogLevel));
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
        List<Option> res = new ArrayList<>();
        //enables a dump of the status of all deployed bundles
        res.add(systemProperty("bundle.debug").value(Boolean.toString(Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))));

        //add locations to look for jars to deploy
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

        //make src/test/resources/jetty-logging.properties visible to jetty in the osgi container        
        TinyBundle loggingPropertiesBundle = TinyBundles.bundle();
        loggingPropertiesBundle.add("jetty-logging.properties", ClassLoader.getSystemResource("jetty-logging.properties"));
        loggingPropertiesBundle.set(Constants.BUNDLE_SYMBOLICNAME, "jetty-logging-properties");
        loggingPropertiesBundle.set(Constants.FRAGMENT_HOST, "org.eclipse.jetty.logging");
        loggingPropertiesBundle.add(FragmentActivator.class);
        res.add(CoreOptions.streamBundle(loggingPropertiesBundle.build()).noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.toolchain").artifactId("jetty-jakarta-servlet-api").versionAsInProject().start());

        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-commons").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-tree").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.apache.aries.spifly").artifactId("org.apache.aries.spifly.dynamic.bundle").versionAsInProject().start());
        res.add(mavenBundle().groupId("jakarta.annotation").artifactId("jakarta.annotation-api").versionAsInProject().start());
        
        //remove unused imports and exports from jakarta.transaction-api manifest
        res.add(wrappedBundle(mavenBundle().groupId("jakarta.transaction").artifactId("jakarta.transaction-api").versionAsInProject())
            .instructions("Import-Package=javax.transaction.xa&Export-Package=jakarta.transaction.*;version=\"2.0.0\"")
            .overwriteManifest(OverwriteMode.MERGE)
            .start());

        //the slf4j-api jar does not have support for ServiceLoader in osgi in its manifest, so add it now
        res.add(wrappedBundle(mavenBundle().groupId("org.slf4j").artifactId("slf4j-api").versionAsInProject())
            .instructions("Require-Capability=osgi.serviceloader;filter:=\"(osgi.serviceloader=org.slf4j.spi.SLF4JServiceProvider)\",osgi.extender;filter:=\"(osgi.extender=osgi.serviceloader.processor)\"")
            .overwriteManifest(OverwriteMode.MERGE)
            .start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-slf4j-impl").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-util").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-deploy").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-servlet").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-http").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-xml").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-webapp").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-io").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-security").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-servlets").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-client").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-jndi").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-plus").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-annotations").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-core").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-servlet").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-util").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-jetty-api").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-jetty-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-jetty-client").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-jetty-common").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.toolchain").artifactId("jetty-jakarta-websocket-api").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-jakarta-server").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-jakarta-client").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-jakarta-common").versionAsInProject().noStart());
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
        res.add(mavenBundle().groupId("org.mortbay.jasper").artifactId("apache-el").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.mortbay.jasper").artifactId("apache-jsp").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("apache-jsp").versionAsInProject().start());
        res.add(mavenBundle().groupId("jakarta.servlet.jsp.jstl").artifactId("jakarta.servlet.jsp.jstl-api").versionAsInProject());
        //TODO: skip the standard taglib: it wants to use xalan, which doesn't have an osgi manifest
        //res.add(mavenBundle().groupId("org.mortbay.jasper").artifactId("taglibs-standard").versionAsInProject().start());
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
            switch (b.getState())
            {
                case Bundle.INSTALLED:
                {
                    //can't start a fragment bundle
                    if (b.getHeaders().get("Fragment-Host") == null)
                    {
                        diagnoseNonActiveOrNonResolvedBundle(b);
                    }
                }
                default:
                {
                    dumpBundleState(b);
                    break;
                }
            }
        }
    }
    
    protected static void dumpBundleState(Bundle b)
    {
        System.err.println("Bundle: [" + b.getBundleId() +"] " + b + " State=" + b.getState());
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

    protected static SslContextFactory.Client newClientSslContextFactory()
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        return sslContextFactory;
    }

    public static void assertContains(String message, String haystack, String needle)
    {
        assertTrue(message + "\nContains: <" + needle + ">\nIn:\n" + haystack, haystack.contains(needle));
    }

// THIS IS NOT USED???
//
//    protected static void testHttpServiceGreetings(BundleContext bundleContext, String protocol, int port) throws Exception
//    {
//        assertActiveBundle(bundleContext, "org.eclipse.jetty.osgi.boot");
//
//        assertActiveBundle(bundleContext, "org.eclipse.jetty.osgi.httpservice");
//        assertActiveBundle(bundleContext, "org.eclipse.equinox.http.servlet");
//
//        // in the OSGi world this would be bad code and we should use a bundle
//        // tracker.
//        // here we purposely want to make sure that the httpService is actually
//        // ready.
//        ServiceReference<?> sr = bundleContext.getServiceReference(HttpService.class.getName());
//        assertNotNull("The httpServiceOSGiBundle is started and should " + "have deployed a service reference for HttpService", sr);
//        HttpService http = (HttpService)bundleContext.getService(sr);
//        http.registerServlet("/greetings", new HttpServlet()
//        {
//            private static final long serialVersionUID = 1L;
//
//            @Override
//            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
//            {
//                resp.getWriter().write("Hello");
//            }
//        }, null, null);
//
//        // now test the servlet
//        ClientConnector clientConnector = new ClientConnector();
//        clientConnector.setSelectors(1);
//        clientConnector.setSslContextFactory(newClientSslContextFactory());
//        HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
//        try
//        {
//            client.start();
//            ContentResponse response = client.GET(protocol + "://127.0.0.1:" + port + "/greetings");
//            assertEquals(HttpStatus.OK_200, response.getStatus());
//
//            String content = new String(response.getContent());
//            assertEquals("Hello", content);
//        }
//        finally
//        {
//            client.stop();
//        }
//    }
}
