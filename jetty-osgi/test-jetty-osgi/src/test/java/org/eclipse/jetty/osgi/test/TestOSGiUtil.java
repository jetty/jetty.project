//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;

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
        xmlConfigs.append(new File(etc, "jetty-deploy.xml").toURI());
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

    public static Option optionalRemoteDebug()
    {
        return CoreOptions.when(Boolean.getBoolean("pax.exam.debug.remote"))
            .useOptions(CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"));
    }

    public static List<Option> coreJettyDependencies(boolean withJsp)
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
        
        /*
         * Jetty 10 uses slf4j 2.0.0 by default, however we want to test with slf4j 1.7.30 for backwards compatibility.
         * To do that, we need to use slf4j-simple as the logging implementation. We make a simplelogger.properties
         * file available so that jetty logging can be configured
         */
        res.add(mavenBundle().groupId("org.slf4j").artifactId("slf4j-api").versionAsInProject().noStart());
        TinyBundle simpleLoggingPropertiesBundle = TinyBundles.bundle();
        simpleLoggingPropertiesBundle.add("simplelogger.properties", ClassLoader.getSystemResource("simplelogger.properties"));
        simpleLoggingPropertiesBundle.set(Constants.BUNDLE_SYMBOLICNAME, "simple-logger-properties");
        simpleLoggingPropertiesBundle.set(Constants.FRAGMENT_HOST, "slf4j-simple");
        simpleLoggingPropertiesBundle.add(FragmentActivator.class);
        res.add(CoreOptions.streamBundle(simpleLoggingPropertiesBundle.build()).noStart());
        res.add(mavenBundle().groupId("org.slf4j").artifactId("slf4j-simple").versionAsInProject().noStart());
        
        /*
         * NOTE: when running with slf4j >= 2.0.0, remove the slf4j simple logger above and uncomment the following lines

        TinyBundle loggingPropertiesBundle = TinyBundles.bundle();
        loggingPropertiesBundle.add("jetty-logging.properties", ClassLoader.getSystemResource("jetty-logging.properties"));
        loggingPropertiesBundle.set(Constants.BUNDLE_SYMBOLICNAME, "jetty-logging-properties");
        loggingPropertiesBundle.set(Constants.FRAGMENT_HOST, "org.eclipse.jetty.logging");
        loggingPropertiesBundle.add(FragmentActivator.class);
        res.add(CoreOptions.streamBundle(loggingPropertiesBundle.build()).noStart());
        //Fix missing ServiceLoader in slf4j-api 2.0.0 manifest
        res.add(wrappedBundle(mavenBundle().groupId("org.slf4j").artifactId("slf4j-api").versionAsInProject()
            .instructions("Require-Capability=osgi.serviceloader;filter:=\"(osgi.serviceloader=org.slf4j.spi.SLF4JServiceProvider)\",osgi.extender;filter:=\"(osgi.extender=osgi.serviceloader.processor)\"")
            .overwriteManifest(OverwriteMode.MERGE)
            .start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-slf4j-impl").versionAsInProject().start());
        */
        res.add(mavenBundle().groupId("org.eclipse.jetty.toolchain").artifactId("jetty-servlet-api").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.platform").artifactId("org.eclipse.osgi.util").versionAsInProject());
        res.add(mavenBundle().groupId("org.eclipse.platform").artifactId("org.eclipse.osgi.services").versionAsInProject());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-commons").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-tree").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-analysis").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-util").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.apache.aries.spifly").artifactId("org.apache.aries.spifly.dynamic.bundle").versionAsInProject().start());
        res.add(mavenBundle().groupId("jakarta.annotation").artifactId("jakarta.annotation-api").versionAsInProject().start());
        res.add(mavenBundle().groupId("jakarta.enterprise").artifactId("jakarta.enterprise.cdi-api").versionAsInProject().start());
        res.add(mavenBundle().groupId("jakarta.interceptor").artifactId("jakarta.interceptor-api").versionAsInProject().start());
        res.add(mavenBundle().groupId("jakarta.transaction").artifactId("jakarta.transaction-api").versionAsInProject().start());
        //if not deploying jsp, then just deploy the el-api because jakarta.enterprise depends on it
        if (!withJsp)
            res.add(mavenBundle().groupId("jakarta.el").artifactId("jakarta.el-api").versionAsInProject().start());

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
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-core-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-core-client").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-core-common").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-servlet").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-jetty-api").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-jetty-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-jetty-client").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-jetty-common").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.toolchain").artifactId("jetty-javax-websocket-api").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-javax-server").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-javax-client").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("websocket-javax-common").versionAsInProject().noStart());

        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("jetty-osgi-boot").versionAsInProject().start());

        if (withJsp)
        {
            res.add(mavenBundle().groupId("org.eclipse.jetty.orbit").artifactId("javax.servlet.jsp.jstl").versionAsInProject());
            res.add(mavenBundle().groupId("org.mortbay.jasper").artifactId("apache-el").versionAsInProject().start());
            res.add(mavenBundle().groupId("org.mortbay.jasper").artifactId("apache-jsp").versionAsInProject().start());
            res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("apache-jsp").versionAsInProject().start());
            res.add(mavenBundle().groupId("org.glassfish.web").artifactId("javax.servlet.jsp.jstl").versionAsInProject().start());
            res.add(mavenBundle().groupId("org.eclipse.jdt").artifactId("ecj").versionAsInProject().start());
            res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("jetty-osgi-boot-jsp").versionAsInProject().noStart());
        }
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

    protected static void diagnoseBundles(BundleContext bundleContext)
    {
        System.err.println("ACTIVE: " + Bundle.ACTIVE);
        System.err.println("RESOLVED: " + Bundle.RESOLVED);
        System.err.println("INSTALLED: " + Bundle.INSTALLED);
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
                    dumpBundle(b);
                    break;
                }
                default:
                {
                    dumpBundle(b);
                }
            }
        }
    }
    
    protected static void dumpBundle(Bundle b)
    {
        System.err.println("    " + b.getBundleId() + " " + b.getSymbolicName() + " " + b.getLocation() + " " + b.getVersion() + " " + b.getState());
    }

    protected static void diagnoseNonActiveOrNonResolvedBundle(Bundle b)
    {        
        if (b.getState() != Bundle.ACTIVE && b.getHeaders().get("Fragment-Host") == null)
        {
            try
            {
                System.err.println("Trying to start the bundle " + b.getSymbolicName() + " that was supposed to be active or resolved.");
                b.start();
                System.err.println(b.getSymbolicName() + " did start");
            }
            catch (Throwable t)
            {
                System.err.println(b.getSymbolicName() + " failed to start");
                t.printStackTrace(System.err);
            }
        }
    }

    protected static void dumpBundles(BundleContext bundleContext)
    {
        System.err.println("ACTIVE: " + Bundle.ACTIVE);
        System.err.println("RESOLVED: " + Bundle.RESOLVED);
        System.err.println("INSTALLED: " + Bundle.INSTALLED);
        for (Bundle b : bundleContext.getBundles())
            dumpBundle(b);
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
}
