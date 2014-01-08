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

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.MavenUtils;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.Configuration;
import org.ops4j.pax.exam.junit.JUnit4TestRunner;
import org.ops4j.pax.exam.options.MavenUrlReference.VersionResolver;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;


/**
 * Default OSGi setup integration test
 */
@RunWith( JUnit4TestRunner.class )
public class TestJettyOSGiBootCore
{
 
    public static int DEFAULT_JETTY_HTTP_PORT = 9876;
     
    @Inject
    private BundleContext bundleContext;
 
    @Configuration
    public Option[] config()
    {
        VersionResolver resolver = MavenUtils.asInProject();
        ArrayList<Option> options = new ArrayList<Option>();
        TestOSGiUtil.addMoreOSGiContainers(options);
        options.addAll(provisionCoreJetty());
        options.add(CoreOptions.junitBundles());
        options.addAll(httpServiceJetty());
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
 
        res.add(mavenBundle().groupId( "org.eclipse.jetty.osgi" ).artifactId( "jetty-osgi-boot" ).versionAsInProject().start());
 
        res.add(mavenBundle().groupId( "javax.servlet" ).artifactId( "javax.servlet-api" ).versionAsInProject().noStart());
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
        res.add(mavenBundle().groupId( "org.eclipse.jetty.websocket" ).artifactId( "websocket-api" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.websocket" ).artifactId( "websocket-common" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.websocket" ).artifactId( "websocket-servlet" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.websocket" ).artifactId( "websocket-server" ).versionAsInProject().noStart());
        res.add(mavenBundle().groupId( "javax.websocket" ).artifactId( "javax.websocket-api" ).versionAsInProject().noStart());
        return res;
    }
     
    public static List<Option> httpServiceJetty()
    {
        List<Option> res = new ArrayList<Option>();
        res.add(mavenBundle().groupId( "org.eclipse.jetty.osgi" ).artifactId( "jetty-httpservice" ).versionAsInProject().start());
        res.add(mavenBundle().groupId( "org.eclipse.equinox.http" ).artifactId( "servlet" ).versionAsInProject().start());
        return res;
    }
     
    @Test
    public void assertAllBundlesActiveOrResolved() throws Exception
    {
        //TestOSGiUtil.debugBundles(bundleContext);
        //Bundle bootBundle = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.osgi.boot");
        //TestOSGiUtil.diagnoseNonActiveOrNonResolvedBundle(bootBundle);
        Bundle httpservicebundle = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.osgi.httpservice");
        TestOSGiUtil.diagnoseNonActiveOrNonResolvedBundle(httpservicebundle);
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
