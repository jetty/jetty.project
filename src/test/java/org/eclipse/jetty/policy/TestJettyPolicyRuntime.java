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

import junit.framework.TestCase;

public class TestJettyPolicyRuntime extends TestCase
{

    HashMap evaluator = new HashMap();
    
    @Override
    protected void setUp() throws Exception
    {
        super.setUp();
        
        evaluator.put( "jetty.home", getWorkingDirectory() );
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
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( getWorkingDirectory() + "/src/test/resources/global-all-permission.policy" ), evaluator );

        ap.refresh();
        
        Policy.setPolicy( ap );       
        System.setSecurityManager( new SecurityManager() );
        
        File test = new File( "/tmp" );
        
        assertTrue ( test.canRead() );
         
    }
    
    public void testRepeatedPolicyReplacement() throws Exception
    {       
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( getWorkingDirectory() + "/src/test/resources/global-all-permission.policy" ), evaluator );

        ap.refresh();
        
        Policy.setPolicy( ap );
        
        System.setSecurityManager( new SecurityManager() );
        
        // Test that the all permission policy allows us to do this
        try
        {
            File test3 = new File( "/tmp/foo/bar/do" );
            test3.mkdirs();
            test3.delete();
            assertTrue( "Under AllPermission we are allowed", true );
        }
        catch ( AccessControlException ace )
        {
            //ace.printStackTrace();
            assertFalse( "Exception was thrown which it shouldn't have been", true );
        }
        
        JettyPolicy ap2 =
            new JettyPolicy( Collections.singleton( getWorkingDirectory() + "/src/test/resources/global-file-read-only-tmp-permission.policy" ), evaluator );
        
        ap2.refresh();
        
        Policy.setPolicy( ap2 );

        // Test that the new policy does replace the old one and we are now now allowed
        try
        {
            File test3 = new File( "/tmp/foo/bar/do" );
            test3.mkdirs();
            assertFalse( "We should be restricted and not get here.", true );
        }
        catch ( AccessControlException ace )
        {
            //ace.printStackTrace();
            assertTrue( "Exception was thrown as it should be.", true );
        }
              
    }


    public void testPolicyRestrictive() throws Exception
    {
        
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( getWorkingDirectory() + "/src/test/resources/global-file-read-only-tmp-permission.policy" ), evaluator );
        
        ap.refresh();
        
        Policy.setPolicy( ap );
        
        System.setSecurityManager( new SecurityManager() );
       
        File test = new File( "/tmp" );
        
        assertTrue ( test.canRead() );
        
        File test2 = new File( "/tmp/foo" );
        assertTrue ( test2.canRead() );
        
        try
        {
            File test3 = new File( "/tmp/foo/bar/do" );
            test3.mkdirs();
            assertTrue( "we should not get here", false );
        }
        catch ( AccessControlException ace )
        {
            //ace.printStackTrace();
            assertTrue( "Exception was thrown", true );
        }

    }
    
    public void testCertificateLoader()
    throws Exception
    {
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( getWorkingDirectory()
            + "/src/test/resources/jetty-certificate.policy" ), evaluator );

        ap.refresh();
        
        Policy.setPolicy( ap );
        
        System.setSecurityManager( new SecurityManager() );
     
        URL url = new URL("file://" + getWorkingDirectory() + "/target/test-policy/jetty-test-policy-1.0-SNAPSHOT.jar");
        
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
            Class clazz = loader.loadClass( "org.eclipse.jetty.toolchain.test.policy.Tester" );
              
            Method m = clazz.getMethod( "testEcho", new Class[] {String.class} );
            
            String foo = (String)m.invoke( clazz.newInstance(), new Object[] {"foo"} );                    
            
            assertEquals("foo", foo );
            
            Method m2 = clazz.getMethod( "testReadSystemProperty", new Class[] {String.class} );
            
            m2.invoke( clazz.newInstance(), new Object[] {"foo"} );                    
            
            assertTrue( "system property access was granted", true );
        }
        catch ( ClassNotFoundException e )
        {
            e.printStackTrace();
            assertFalse( "should not have got here", true );
        }
        catch ( SecurityException e )
        {
            e.printStackTrace();            
            assertFalse( "should not have got here", true );
        }
        catch ( IllegalAccessException e )
        {
            e.printStackTrace();
            assertFalse( "should not have got here", true );
        }
             
    }
    
    
    public void testBadCertificateLoader()
    throws Exception
    {
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( getWorkingDirectory()
            + "/src/test/resources/jetty-bad-certificate.policy" ), evaluator );

        ap.refresh();
        
        Policy.setPolicy( ap );
        
        System.setSecurityManager( new SecurityManager() );
     
        URL url = new URL("file://" + getWorkingDirectory() + "/target/test-policy/jetty-test-policy-1.0-SNAPSHOT.jar");
        
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
        
        boolean excepted = false;
        
        try
        {
            Class clazz = loader.loadClass( "org.eclipse.jetty.toolchain.test.policy.Tester" );
            
            Method m = clazz.getMethod( "testEcho", new Class[] {String.class} );
            
            String foo = (String)m.invoke( clazz.newInstance(), new Object[] {"foo"} );                    
            
            assertEquals("foo", foo );
            
            Method m2 = clazz.getMethod( "testReadSystemProperty", new Class[] {String.class} );
            
            m2.invoke( clazz.newInstance(), new Object[] {"foobar"} );                    
            
        }
        catch ( ClassNotFoundException e )
        {
            e.printStackTrace();
            assertFalse( "should not have got here", true );
        }
        catch ( InvocationTargetException e )
        {
            assertTrue(e.getCause().getMessage().contains( "access denied" ));
            
            excepted = true; // we hope to get here
        }
        catch ( IllegalAccessException e )
        {           
            e.printStackTrace();
            assertFalse( "should not have got here", true );
        }
        
        assertTrue( "checking that we through a security exception", excepted );
       
    }
    
    
    private String getWorkingDirectory()
    {
        String cwd = System.getProperty( "basedir" );
        
        if ( cwd == null )
        {
            cwd = System.getProperty( "user.dir" );
        }
        return cwd;
    }
}
