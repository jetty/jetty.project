package org.eclipse.jetty.policy;
//========================================================================
//Copyright (c) 2003-2009 Mort Bay Consulting Pty. Ltd.
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
import java.security.AccessControlException;
import java.security.Policy;
import java.util.Collections;
import java.util.HashMap;

import org.eclipse.jetty.policy.JettyPolicy;
import org.eclipse.jetty.policy.PropertyEvaluator;


import junit.framework.TestCase;

public class TestJettyPolicyRuntime extends TestCase
{

    PropertyEvaluator evaluator = new PropertyEvaluator( new HashMap<String,String>());
    
    @Override
    protected void setUp()
        throws Exception
    {
        super.setUp();
        
        evaluator.put( "jetty.home", getWorkingDirectory() );
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
         
        // Policy nulling must occur after Security Manager null
        System.setSecurityManager( null );
        Policy.setPolicy( null );
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
              
        System.setSecurityManager( null );
        Policy.setPolicy( null );
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
               
        System.setSecurityManager( null );
        Policy.setPolicy( null );
    }
       
    private String getWorkingDirectory()
    {
        return System.getProperty( "basedir" ); // TODO work in eclipse
    }
}
