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

import java.io.IOException;
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
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.ops4j.pax.exam.Option;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpService;

/**
 * Helper methods for pax-exam tests
 */
public class TestOSGiUtil
{

    protected static Bundle getBundle(BundleContext bundleContext, String symbolicName)
    {
            Map<String,Bundle> _bundles = new HashMap<String, Bundle>();
            for (Bundle b : bundleContext.getBundles())
            {
                Bundle prevBundle = _bundles.put(b.getSymbolicName(), b);
                String err = prevBundle != null ? "2 versions of the bundle " + b.getSymbolicName()
                                                + " "
                                                + b.getHeaders().get("Bundle-Version")
                                                + " and "
                                                + prevBundle.getHeaders().get("Bundle-Version") : "";
                                                Assert.assertNull(err, prevBundle);
            }
        return _bundles.get(symbolicName);
    }

    protected static void assertActiveBundle(BundleContext bundleContext, String symbolicName) throws Exception
    {
        Bundle b = getBundle(bundleContext, symbolicName);
        Assert.assertNotNull(b);
        Assert.assertEquals(b.getSymbolicName() + " must be active.", Bundle.ACTIVE, b.getState());
    }

    protected static void assertActiveOrResolvedBundle(BundleContext bundleContext, String symbolicName) throws Exception
    {
        Bundle b = getBundle(bundleContext, symbolicName);
        Assert.assertNotNull(b);
        if (b.getHeaders().get("Fragment-Host") == null) diagnoseNonActiveOrNonResolvedBundle(b);
        Assert.assertTrue(b.getSymbolicName() + " must be active or resolved. It was " + b.getState(),
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
            Assert.assertTrue("Bundle: " + b
                              + " (state should be "
                              + "ACTIVE["
                              + Bundle.ACTIVE
                              + "] or RESOLVED["
                              + Bundle.RESOLVED
                              + "]"
                              + ", but was ["
                              + b.getState()
                              + "])", (b.getState() == Bundle.ACTIVE) || (b.getState() == Bundle.RESOLVED));
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
            System.err.println("    " + b.getSymbolicName() + " " + b.getLocation() + " " + b.getVersion()+ " " + b.getState());
        }
    }
   
    protected static ServiceReference[] getServices (String service, BundleContext bundleContext) throws Exception
    {
       return bundleContext.getAllServiceReferences(service, null);
    }

    protected static SslContextFactory newSslContextFactory()
    {
        SslContextFactory sslContextFactory = new SslContextFactory(true);
        sslContextFactory.setEndpointIdentificationAlgorithm("");
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
        ServiceReference sr = bundleContext.getServiceReference(HttpService.class.getName());
        Assert.assertNotNull("The httpServiceOSGiBundle is started and should " + "have deployed a service reference for HttpService", sr);
        HttpService http = (HttpService) bundleContext.getService(sr);
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
        HttpClient client = protocol.equals("https") ? new HttpClient(newSslContextFactory()) : new HttpClient();
        try
        {
            client.start();
            ContentResponse response = client.GET(protocol + "://127.0.0.1:" + port + "/greetings");
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

            String content = new String(response.getContent());
            Assert.assertEquals("Hello", content);
        }
        finally
        {
            client.stop();
        }
    }
}
