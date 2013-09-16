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

package org.eclipse.jetty.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessControlException;
import java.security.Policy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class JettyPolicyRuntimeTest
{
    private HashMap<String, String> evaluator = new HashMap<String, String>();

    @Before
    public void init() throws Exception
    {
        System.setSecurityManager(null);
        Policy.setPolicy(null);

        evaluator.put("jetty.home",MavenTestingUtils.getBaseURI().toASCIIString());
        evaluator.put("basedir",MavenTestingUtils.getBaseURI().toASCIIString());
    }

    @After
    public void destroy() throws Exception
    {
        System.setSecurityManager(null);
        Policy.setPolicy(null);
        IO.delete(new File ("/tmp", "foo"));
    }

    @Test
    public void testSimplePolicyReplacement() throws Exception
    {
    	Assume.assumeTrue(!OS.IS_WINDOWS); // Ignore test if running under windows.
        JettyPolicy ap = new JettyPolicy(MavenTestingUtils.getTestResourceDir("runtime-test-1").getAbsolutePath(), evaluator);
        ap.refresh();

        Policy.setPolicy( ap );
        System.setSecurityManager( new SecurityManager() );

        File test = new File( "/tmp" );

        assertTrue( test.canRead() );
    }

    @Test
    public void testRepeatedPolicyReplacement() throws Exception
    {
    	Assume.assumeTrue(!OS.IS_WINDOWS); // Ignore test if running under windows.
        JettyPolicy ap = new JettyPolicy(MavenTestingUtils.getTestResourceDir("runtime-test-2/a").getAbsolutePath(),evaluator);
        ap.refresh();

        Policy.setPolicy( ap );
        System.setSecurityManager( new SecurityManager() );

        // Test that the all permission policy allows us to do this
        try
        {
            File test3 = new File( "/tmp/foo/bar/do" );
            test3.mkdirs();
            test3.delete();
        }
        catch ( AccessControlException ace )
        {
            ace.printStackTrace(System.err);
            fail("Should NOT have thrown an AccessControlException");
        }

        JettyPolicy ap2 = new JettyPolicy(MavenTestingUtils.getTestResourceDir("runtime-test-2/b").getAbsolutePath(),evaluator);
        ap2.refresh();

        Policy.setPolicy( ap2 );

        // Test that the new policy does replace the old one and we are now not allowed
        try
        {
            File test3 = new File( "/tmp/foo/bar/do" );
            test3.mkdirs();

            fail("Should have thrown an AccessControlException");
        }
        catch ( AccessControlException ace )
        {
            // Expected Path
        }
    }

    @Test
    public void testPolicyRestrictive() throws Exception
    {
        // TODO - temporary, create alternate file to load for windows
    	Assume.assumeTrue(!OS.IS_WINDOWS); // Ignore test if running under windows.

        JettyPolicy ap = new JettyPolicy(MavenTestingUtils.getTestResourceDir("runtime-test-3").getAbsolutePath(),evaluator);
        ap.refresh();

        Policy.setPolicy( ap );
        System.setSecurityManager( new SecurityManager() );

        File test = new File( "/tmp" );

        assertTrue ( test.canRead() );

        File test2 = new File( "/tmp/foo" );
        test2.mkdirs();
        assertTrue ( test2.canRead() );

        try
        {
            File test3 = new File("/tmp/foo/bar/do");
            test3.mkdirs();

            fail("Should have thrown an AccessControlException");
        }
        catch (AccessControlException ace)
        {
            // Expected Path
        }
    }

    @Test
    public void testCertificateLoader() throws Exception
    {
        // TODO - temporary, create alternate file to load for windows
    	Assume.assumeTrue(!OS.IS_WINDOWS); // Ignore test if running under windows.

        JettyPolicy ap = new JettyPolicy(MavenTestingUtils.getTestResourceDir("runtime-test-4").getAbsolutePath(),evaluator);
        ap.refresh();

    
        URL url = MavenTestingUtils.getTargetURL("test-policy/jetty-test-policy.jar");

        //System.out.println(url.toURI().toASCIIString());
        //System.out.println(MavenTestingUtils.getBaseURI().toASCIIString());

        URLClassLoader loader ;
        if (Thread.currentThread().getContextClassLoader() != null )
        {
            loader = new URLClassLoader( new URL[]{ url }, Thread.currentThread().getContextClassLoader() );
        }
        else
        {
            loader = new URLClassLoader( new URL[]{ url }, ClassLoader.getSystemClassLoader() );
        }

        Thread.currentThread().setContextClassLoader(loader);

        Policy.setPolicy( ap );
        System.setSecurityManager( new SecurityManager() );

        
        ap.refresh();

        ap.dump(System.out);

        
        Class<?> clazz = loader.loadClass("org.eclipse.jetty.toolchain.test.policy.Tester");

        Method m = clazz.getMethod("testEcho",new Class[]
        { String.class });

        String foo = (String)m.invoke(clazz.newInstance(), "foo");

        assertEquals("foo",foo);

        Method m2 = clazz.getMethod("testReadSystemProperty",new Class[]
        { String.class });

        m2.invoke(clazz.newInstance(), "foo");

        assertTrue("system property access was granted",true);

        // ap.dump(System.out);
    }

    @Test
    public void testBadCertificateLoader() throws Exception
    {
        // TODO - temporary, create alternate file to load for windows
    	Assume.assumeTrue(!OS.IS_WINDOWS); // Ignore test if running under windows.

        JettyPolicy ap = new JettyPolicy(MavenTestingUtils.getTestResourceDir("runtime-test-5").getAbsolutePath(),evaluator);
        ap.refresh();

        Policy.setPolicy( ap );
        System.setSecurityManager( new SecurityManager() );

        URL url = MavenTestingUtils.getTargetURL("test-policy/jetty-test-policy.jar");

        URLClassLoader loader ;
        if (Thread.currentThread().getContextClassLoader() != null )
        {
            loader = new URLClassLoader( new URL[]{ url }, Thread.currentThread().getContextClassLoader() );
        }
        else
        {
            loader = new URLClassLoader( new URL[]{ url }, ClassLoader.getSystemClassLoader() );
        }

        Thread.currentThread().setContextClassLoader(loader);

        ap.refresh();

        try
        {
            Class<?> clazz = loader.loadClass("org.eclipse.jetty.toolchain.test.policy.Tester");

            Method m = clazz.getMethod( "testEcho", new Class[] {String.class} );

            String foo = (String)m.invoke( clazz.newInstance(), "foo");

            assertEquals("foo", foo );

            Method m2 = clazz.getMethod( "testReadSystemProperty", new Class[] {String.class} );

            m2.invoke(clazz.newInstance(), "foobar");

            fail("Should have thrown an InvocationTargetException");
        }
        catch ( InvocationTargetException e )
        {
            assertTrue(e.getCause().getMessage().contains( "access denied" ));
        }
    }

    private Set<String> getSinglePolicy(String name)
    {
        return Collections.singleton(MavenTestingUtils.getTestResourceFile(name).getAbsolutePath());
    }
}
