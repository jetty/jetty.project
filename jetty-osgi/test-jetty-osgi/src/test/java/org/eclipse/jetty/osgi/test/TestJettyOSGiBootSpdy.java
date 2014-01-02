//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.CoreOptions.options;


import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.Configuration;
import org.ops4j.pax.exam.junit.JUnit4TestRunner;
import org.osgi.framework.BundleContext;

/**
 * SPDY setup.
 */
@RunWith(JUnit4TestRunner.class)
public class TestJettyOSGiBootSpdy
{
    private static final boolean LOGGING_ENABLED = false;
    
    private static final String JETTY_SPDY_PORT = "jetty.spdy.port";

    private static final int DEFAULT_JETTY_SPDY_PORT = 9877;

    @Inject
    private BundleContext bundleContext;

    @Configuration
    public Option[] config()
    {
        ArrayList<Option> options = new ArrayList<Option>();

        TestOSGiUtil.addMoreOSGiContainers(options);

      
        options.addAll(TestJettyOSGiBootWithJsp.configureJettyHomeAndPort("jetty-spdy.xml"));
        options.addAll(TestJettyOSGiBootCore.coreJettyDependencies());
        options.add(CoreOptions.junitBundles());
        options.addAll(TestJettyOSGiBootCore.httpServiceJetty());
        options.addAll(spdyJettyDependencies());
        
        String logLevel = "WARN";
        
        // Enable Logging
        if (LOGGING_ENABLED)
            logLevel = "INFO";
        

        options.addAll(Arrays.asList(options(
                                             // install log service using pax runners profile abstraction (there
                                             // are more profiles, like DS)
                                             // logProfile(),
                                             // this is how you set the default log level when using pax logging
                                             // (logProfile)
                                             systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(logLevel),
                                             systemProperty("org.eclipse.jetty.LEVEL").value(logLevel))));
        return options.toArray(new Option[options.size()]);
    }

    public static List<Option> spdyJettyDependencies()
    {
        List<Option> res = new ArrayList<Option>();
        res.add(CoreOptions.systemProperty(JETTY_SPDY_PORT).value(String.valueOf(DEFAULT_JETTY_SPDY_PORT)));
        // java
        // -Xbootclasspath/p:${settings.localRepository}/org/mortbay/jetty/npn/npn-boot/${npn-version}/npn-boot-${npn-version}.jar
        // res.add(CoreOptions.vmOptions("-Xbootclasspath/p:"+System.getenv("HOME")+"/.m2/repository/org/mortbay/jetty/npn/npn-boot/"+npnBootVersion+"/npn-boot-"+npnBootVersion+".jar"));
        String npnBoot = System.getProperty("mortbay-npn-boot");
        if (npnBoot == null) { throw new IllegalStateException("Define path to npn boot jar as system property -Dmortbay-npn-boot"); }
        File checkNpnBoot = new File(npnBoot);
        if (!checkNpnBoot.exists()) { throw new IllegalStateException("Unable to find the npn boot jar here: " + npnBoot); }

        res.add(CoreOptions.vmOptions("-Xbootclasspath/p:" + npnBoot));
       // res.add(CoreOptions.bootDelegationPackages("org.eclipse.jetty.npn"));

        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("jetty-osgi-npn").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.spdy").artifactId("spdy-core").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.spdy").artifactId("spdy-server").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.spdy").artifactId("spdy-http-common").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.spdy").artifactId("spdy-http-server").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.spdy").artifactId("spdy-client").versionAsInProject().noStart());
        return res;
    }

    @Test
    public void checkNpnBootOnBootstrapClasspath() throws Exception
    {
        Class<?> npn = Thread.currentThread().getContextClassLoader().loadClass("org.eclipse.jetty.npn.NextProtoNego");
        Assert.assertNotNull(npn);
        Assert.assertNull(npn.getClassLoader());
    }

    @Test
    public void assertAllBundlesActiveOrResolved()
    {
        TestOSGiUtil.assertAllBundlesActiveOrResolved(bundleContext);
    }

    @Test
    public void testSpdyOnHttpService() throws Exception
    {
        TestOSGiUtil.testHttpServiceGreetings(bundleContext, "https", DEFAULT_JETTY_SPDY_PORT);
    }

}
