//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import junit.framework.Assert;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.Configuration;
import org.ops4j.pax.exam.junit.JUnit4TestRunner;
import org.osgi.framework.BundleContext;

/**
 * Pax-Exam to make sure the jetty-osgi-boot can be started along with the httpservice web-bundle.
 * Then make sure we can deploy an OSGi service on the top of this.
 */
@RunWith( JUnit4TestRunner.class )
public class TestJettyOSGiBootWithJsp extends AbstractTestOSGi
{
    private static final boolean LOGGING_ENABLED = true;
    private static final boolean REMOTE_DEBUGGING = false;

    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {

        ArrayList<Option> options = new ArrayList<Option>();

        addMoreOSGiContainers(options);

        options.add(CoreOptions.junitBundles());
        options.addAll(configureJettyHomeAndPort("jetty-selector.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*",
                "org.w3c.*", "javax.xml.*"));
        options.addAll(TestJettyOSGiBootCore.coreJettyDependencies());

        // Enable Logging
        if(LOGGING_ENABLED) {
            options.addAll(Arrays.asList(options(
                // install log service using pax runners profile abstraction (there are more profiles, like DS)
            // logProfile(),
            // this is how you set the default log level when using pax logging (logProfile)
            systemProperty( "org.ops4j.pax.logging.DefaultServiceLog.level" ).value( "INFO" )
            )));
        }

        options.addAll(jspDependencies());

        // Remote JDWP Debugging, this won't work with the forked container.
//      if(REMOTE_DEBUGGING) {
//          options.addAll(Arrays.asList(options(
//              // this just adds all what you write here to java vm argumenents of the (new) osgi process.
//              PaxRunnerOptions.vmOption( "-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5006" )
//          )));
//      }

        //bug at the moment: this would make the httpservice catch all
        //requests and prevent the webapp at the root context to catch any of them.
        //options.addAll(TestJettyOSGiBootCore.httpServiceJetty());

        return options.toArray(new Option[options.size()]);
    }

    public static List<Option> configureJettyHomeAndPort(String jettySelectorFileName)
    {
        File etcFolder = new File("src/test/config/etc");
        String etc = "file://" + etcFolder.getAbsolutePath();
        List<Option> options = new ArrayList<Option>();
        options.add(systemProperty(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS)
                .value(etc + "/jetty.xml;" +
                       etc + "/" + jettySelectorFileName + ";" +
                       etc + "/jetty-deployer.xml;" +
                       etc + "/jetty-testrealm.xml"));
        options.add(systemProperty("jetty.port").value(String.valueOf(TestJettyOSGiBootCore.DEFAULT_JETTY_HTTP_PORT)));
        options.add(systemProperty("jetty.home").value(etcFolder.getParentFile().getAbsolutePath()));
        return options;
    }

    public static List<Option> jspDependencies() {
        List<Option> res = new ArrayList<Option>();
        /* orbit deps */
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "javax.servlet.jsp" ).versionAsInProject());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "javax.servlet.jsp.jstl" ).versionAsInProject());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "javax.el" ).versionAsInProject());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "com.sun.el" ).versionAsInProject());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "org.apache.jasper.glassfish" ).versionAsInProject());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "org.apache.taglibs.standard.glassfish" ).versionAsInProject());
        res.add(mavenBundle().groupId( "org.eclipse.jetty.orbit" ).artifactId( "org.eclipse.jdt.core" ).versionAsInProject());

        /* jetty-osgi deps */
        res.add(mavenBundle().groupId( "org.eclipse.jetty.osgi" ).artifactId( "jetty-osgi-boot-jsp" ).versionAsInProject().noStart());

        res.add(mavenBundle().groupId( "org.eclipse.jetty" ).artifactId( "test-jetty-webapp" ).classifier("webbundle").versionAsInProject());

        return res;
    }

    @Test
    public void assertAllBundlesActiveOrResolved()
    {
        assertAllBundlesActiveOrResolved(bundleContext);
    }

    //at the moment can't run httpservice with jsp at the same time.
    //that is a regression in jetty-9
    @Ignore
    @Test
    public void testHttpService() throws Exception
    {
        super.testHttpServiceGreetings(bundleContext, "http", TestJettyOSGiBootCore.DEFAULT_JETTY_HTTP_PORT);
    }

    @Test
    public void testJspDump() throws Exception
    {

//System.err.println("http://127.0.0.1:9876/jsp/dump.jsp  sleeping....");
//Thread.currentThread().sleep(5000000);
        //now test the jsp/dump.jsp
        HttpClient client = new HttpClient();
        try
        {
            client.start();
            ContentResponse response = client.GET("http://127.0.0.1:"+
                    TestJettyOSGiBootCore.DEFAULT_JETTY_HTTP_PORT+"/jsp/dump.jsp");
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

            String content = new String(response.getContent());
            //System.err.println("content: " + content);
            Assert.assertTrue(content.contains("<tr><th>ServletPath:</th><td>/jsp/dump.jsp</td></tr>"));
        }
        finally
        {
            client.stop();
        }

    }


}
