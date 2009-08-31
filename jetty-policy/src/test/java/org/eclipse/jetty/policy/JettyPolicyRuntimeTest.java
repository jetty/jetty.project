package org.eclipse.jetty.policy;
//========================================================================
//Copyright (c) Webtide LLC
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at 
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses. 
//========================================================================


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

import junit.framework.TestCase;

public class JettyPolicyRuntimeTest extends TestCase
{
    HashMap<String, String> evaluator = new HashMap<String, String>();
    
    private boolean _runningOnWindows;
    
    
    @Override
    protected void setUp() throws Exception
    {
        System.setSecurityManager(null);
        Policy.setPolicy(null);

        _runningOnWindows = System.getProperty( "os.name" ).startsWith( "Windows" );
        
        super.setUp();
        
        evaluator.put("jetty.home",MavenTestingUtils.getBasedir().getAbsolutePath());
        evaluator.put("basedir",MavenTestingUtils.getBasedir().getAbsolutePath());
    }
    
    @Override
    protected void tearDown() throws Exception
    {
        super.tearDown();
        
        System.setSecurityManager( null );
        Policy.setPolicy( null );
    }

    public void testSimplePolicyReplacement() throws Exception
    {   
        
        JettyPolicy ap = new JettyPolicy(getSinglePolicy("global-all-permission.policy"),evaluator);

        ap.refresh();
        
        Policy.setPolicy( ap );       
        System.setSecurityManager( new SecurityManager() );
        
        File test = new File( "/tmp" );
        
        assertTrue ( test.canRead() );
         
    }
    
    public void testRepeatedPolicyReplacement() throws Exception
    {       
        JettyPolicy ap = new JettyPolicy(getSinglePolicy("global-all-permission.policy"),evaluator);

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
        
        JettyPolicy ap2 = new JettyPolicy(getSinglePolicy("global-file-read-only-tmp-permission.policy"),evaluator);
        
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

    public void testPolicyRestrictive() throws Exception
    {
        // TODO - temporary, create alternate file to load for windows
        if (_runningOnWindows)
        {
            // skip run
            return;
        }

        JettyPolicy ap = new JettyPolicy(getSinglePolicy("global-file-read-only-tmp-permission.policy"),evaluator);
        
        ap.refresh();
        
        Policy.setPolicy( ap );
        
        System.setSecurityManager( new SecurityManager() );
       
        File test = new File( "/tmp" );
        
        assertTrue ( test.canRead() );
        
        File test2 = new File( "/tmp/foo" );
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
    
    public void testCertificateLoader()
    throws Exception
    {
        // TODO - temporary, create alternate file to load for windows
        if (_runningOnWindows)
        {
            // skip run
            return;
        }

        JettyPolicy ap = new JettyPolicy(getSinglePolicy("jetty-certificate.policy"),evaluator);

        ap.refresh();
        
        Policy.setPolicy( ap );
        
        System.setSecurityManager( new SecurityManager() );
     
        URL url = MavenTestingUtils.toTargetURL("test-policy/jetty-test-policy.jar");
        
        // System.out.println( "IN HERE: " + url.toString());
        
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
        
        Class<?> clazz = loader.loadClass("org.eclipse.jetty.toolchain.test.policy.Tester");

        Method m = clazz.getMethod("testEcho",new Class[]
        { String.class });

        String foo = (String)m.invoke(clazz.newInstance(),new Object[]
        { "foo" });

        assertEquals("foo",foo);

        Method m2 = clazz.getMethod("testReadSystemProperty",new Class[]
        { String.class });

        m2.invoke(clazz.newInstance(),new Object[]
        { "foo" });

        assertTrue("system property access was granted",true);

       // ap.dump(System.out);
    }
    
    
    public void testBadCertificateLoader()
    throws Exception
    {
        // TODO - temporary, create alternate file to load for windows
        if (_runningOnWindows)
        {
            // skip run
            return;
        }

        JettyPolicy ap = new JettyPolicy(getSinglePolicy("jetty-bad-certificate.policy"),evaluator);

        ap.refresh();
        
        Policy.setPolicy( ap );
        
        System.setSecurityManager( new SecurityManager() );
     
        URL url = MavenTestingUtils.toTargetURL("test-policy/jetty-test-policy.jar");
        
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
            
            String foo = (String)m.invoke( clazz.newInstance(), new Object[] {"foo"} );                    
            
            assertEquals("foo", foo );
            
            Method m2 = clazz.getMethod( "testReadSystemProperty", new Class[] {String.class} );
            
            m2.invoke(clazz.newInstance(),new Object[]
            { "foobar" });

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
