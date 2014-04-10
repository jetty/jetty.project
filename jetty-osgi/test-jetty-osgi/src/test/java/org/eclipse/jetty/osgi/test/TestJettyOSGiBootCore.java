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
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.MavenUtils;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.options.MavenUrlReference.VersionResolver;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;


/**
 * Default OSGi setup integration test
 */
@RunWith( PaxExam.class )
public class TestJettyOSGiBootCore
{
    private static final String LOG_LEVEL = "WARN";
    public static int DEFAULT_JETTY_HTTP_PORT = 9876;

    @Inject
    private BundleContext bundleContext;

    @Configuration
    public Option[] config()
    {
        VersionResolver resolver = MavenUtils.asInProject();
        ArrayList<Option> options = new ArrayList<Option>();
        options.addAll(provisionCoreJetty());
        options.add(CoreOptions.junitBundles());
        options.addAll(httpServiceJetty());
        options.addAll(Arrays.asList(options(systemProperty("pax.exam.logging").value("none"))));
        options.addAll(Arrays.asList(options(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(LOG_LEVEL))));
        options.addAll(Arrays.asList(options(systemProperty("org.eclipse.jetty.LEVEL").value(LOG_LEVEL))));

        return options.toArray(new Option[options.size()]);
    }

    public static List<Option> provisionCoreJetty()
    { 
        List<Option> res = new ArrayList<Option>();
        // get the jetty home config from the osgi boot bundle.
        res.add(CoreOptions.systemProperty("jetty.port").value(String.valueOf(DEFAULT_JETTY_HTTP_PORT)));
        res.add(CoreOptions.systemProperty("jetty.home.bundle").value("org.eclipse.jetty.osgi.boot"));
        res.addAll(coreJettyDependencies());
        return res;
    }
 
     
    public static List<Option> coreJettyDependencies()
    {
        List<Option> res = new ArrayList<Option>();
        
        String jdk = System.getProperty("java.version");
        int firstdot = jdk.indexOf(".");
        jdk = jdk.substring(0,firstdot+2);
        double version = Double.parseDouble(jdk);

        if (version < 1.8)
        {
            res.add(mavenBundle().groupId( "org.ow2.asm" ).artifactId( "asm" ).versionAsInProject().start());
            res.add(mavenBundle().groupId( "org.ow2.asm" ).artifactId( "asm-commons" ).versionAsInProject().start());
            res.add(mavenBundle().groupId( "org.ow2.asm" ).artifactId( "asm-tree" ).versionAsInProject().start());
            res.add(mavenBundle().groupId( "org.apache.aries" ).artifactId( "org.apache.aries.util" ).version("1.0.0").start());
            res.add(mavenBundle().groupId( "org.apache.aries.spifly" ).artifactId( "org.apache.aries.spifly.dynamic.bundle" ).version("1.0.0").start());
        }

        res.add(mavenBundle().groupId( "javax.servlet" ).artifactId( "javax.servlet-api" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "javax.annotation" ).artifactId( "javax.annotation-api" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.apache.geronimo.specs" ).artifactId( "geronimo-jta_1.1_spec" ).version("1.1.1").noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "javax.mail.glassfish" ).version( "1.4.1.v201005082020" ).noStart());
        
     
        res.add(mavenBundle().groupId( "org.eclipse.jetty.toolchain" ).artifactId( "jetty-schemas" ).versionAsInProject().noStart());

        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-deploy" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-server" ).versionAsInProject().noStart());  
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-servlet" ).versionAsInProject().noStart());  
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-util" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-http" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-xml" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-webapp" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-io" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-continuation" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-security" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-servlets" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-client" ).versionAsInProject().noStart());  
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-jndi" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-plus" ).versionAsInProject().noStart());
        if (version < 1.8)
        {
            res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "jetty-annotations" ).versionAsInProject().start());
        }
        res.add(mavenBundle().groupId( "org.eclipse.jetty.websocket" ).artifactId( "websocket-api" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.websocket" ).artifactId( "websocket-common" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.websocket" ).artifactId( "websocket-servlet" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.websocket" ).artifactId( "websocket-server" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.websocket" ).artifactId( "websocket-client" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "javax.websocket" ).artifactId( "javax.websocket-api" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.websocket" ).artifactId( "javax-websocket-client-impl").versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.websocket" ).artifactId( "javax-websocket-server-impl").versionAsInProject().start());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.osgi" ).artifactId( "jetty-osgi-boot" ).versionAsInProject().start());
        return res;
    }
     
    public static List<Option> httpServiceJetty()
    {
        List<Option> res = new ArrayList<Option>();
        res.add(mavenBundle().groupId( "org.eclipse.jetty.osgi" ).artifactId( "jetty-httpservice" ).versionAsInProject().start());
        res.add(mavenBundle().groupId( "org.eclipse.equinox.http" ).artifactId( "servlet" ).versionAsInProject().start());
        return res;
    }
     
    @Ignore
    @Test
    public void assertAllBundlesActiveOrResolved() throws Exception
    {
        
        TestOSGiUtil.debugBundles(bundleContext);
        TestOSGiUtil.assertAllBundlesActiveOrResolved(bundleContext);
    }
     
    /**
     * You will get a list of bundles installed by default
     * plus your testcase, wrapped into a bundle called pax-exam-probe
     */
    @Test
    public void testHttpService() throws Exception
    {
        TestOSGiUtil.testHttpServiceGreetings(bundleContext, "http", DEFAULT_JETTY_HTTP_PORT);
    }
}
