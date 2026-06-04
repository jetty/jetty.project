//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee.osgi.test;

import java.util.ArrayList;
import javax.inject.Inject;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee.common.EnterpriseEditionVersion;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.osgi.framework.BundleContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;

/**
 * Pax-Exam to make sure the jetty-ee-osgi-boot can be started along with the
 * httpservice web-bundle. Then make sure we can deploy an OSGi service on the
 * top of this.
 */
@RunWith(PaxExam.class)
public class TestJettyOSGiBootWithJspC
{
    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
        ArrayList<Option> options = new ArrayList<>();

        options.addAll(TestOSGiUtil.configurePaxExamLogging());

        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.configureJettyHomeAndPort(false, "jetty-http-boot-with-jspc.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res", "com.sun.org.apache.xml.internal.utils",
            "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
            "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects"));
        TestOSGiUtil.coreJettyDependencies(options);
        TestOSGiUtil.coreJspDependencies(options);
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-java-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());
        options.add(CoreOptions.cleanCaches(true));

        String jspcDemoWebappArtifactId = switch(EnterpriseEditionVersion.getEnterpriseEditionVersion())
        {
            case EE10 -> "jetty-servlet6-demo-jspc-webapp";
            case EE11 -> "jetty-servlet6-demo-jspc-6-1-webapp";
            case EE12 -> "jetty-servlet6-demo-jspc-6-2-webapp";
        };
        options.add(mavenBundle().groupId("org.eclipse.jetty.demos").artifactId(jspcDemoWebappArtifactId).classifier("webbundle-ee").versionAsInProject());
        return options.toArray(new Option[0]);
    }

    @Test
    public void testJspDump() throws Exception
    {
        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            TestOSGiUtil.diagnoseBundles(bundleContext);

        try (HttpClient client = new HttpClient())
        {
            client.start();

            String port = System.getProperty("boot.jspc.port");
            assertNotNull(port);
            String path = switch(EnterpriseEditionVersion.getEnterpriseEditionVersion())
            {
                case EE10 -> "/servlet6-demo-jspc/jstl.jsp";
                case EE11 -> "/servlet6-demo-jspc-61/jstl.jsp";
                case unknown, EE12 -> "/servlet6-demo-jspc-62/jstl.jsp";
            };
            ContentResponse response = client.GET("http://127.0.0.1:" + port + path);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            String content = response.getContentAsString();
            assertTrue(content.contains("JSTL Example"));
        }
    }
}
