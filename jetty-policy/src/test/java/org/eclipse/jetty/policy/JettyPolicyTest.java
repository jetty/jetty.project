package org.eclipse.jetty.policy;
//========================================================================
//Copyright (c) Webtide LLC
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//
//The Apache License v2.0 is available at
//http://www.apache.org/licenses/LICENSE-2.0.txt
//
//You may elect to redistribute this code under either of these licenses.
//========================================================================

import java.io.FilePermission;
import java.net.URL;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import junit.framework.TestCase;

public class JettyPolicyTest extends TestCase
{
    HashMap<String, String> evaluator = new HashMap<String, String>();
   

    @Override
    protected void setUp()
        throws Exception
    {
        super.setUp();

        evaluator.put("jetty.home",MavenTestingUtils.getBasedir().getAbsolutePath());
        evaluator.put("basedir",MavenTestingUtils.getBasedir().getAbsolutePath());
    }

    public void testGlobalAllPermissionLoader()
        throws Exception
    {
        
        JettyPolicy ap =
            new JettyPolicy(  Collections.singleton( getWorkingDirectory() + "/src/test/resources/global-all-permission.policy" ), evaluator );

        ap.refresh();

        
        PermissionCollection pc = ap.getPermissions( new ProtectionDomain( null, null ) );
        
        assertNotNull( pc );
        
        Permission testPerm = new FilePermission( "/tmp", "read" );
        
        assertTrue( pc.implies( testPerm ) );
        
        for ( Enumeration<Permission> e = pc.elements(); e.hasMoreElements(); ) 
        {
            System.out.println( "Permission: " + e.nextElement().getClass().getName() );
        }
        
    }

    public void testSingleCodebaseFilePermissionLoader()
        throws Exception
    {
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( getWorkingDirectory()
                + "/src/test/resources/single-codebase-file-permission.policy" ), evaluator );

        ap.refresh();
        
        URL url = new URL( "file:///foo.jar" ); 
        CodeSource cs = new CodeSource( url, new Certificate[0]);
        
        PermissionCollection pc = ap.getPermissions( cs );
        
        assertNotNull( pc );
        
        Permission testPerm = new FilePermission( "/tmp/*", "read" );
        
        assertTrue( pc.implies( testPerm ) );
              
    }

    public void testMultipleCodebaseFilePermissionLoader()
        throws Exception
    {
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( getWorkingDirectory()
                + "/src/test/resources/multiple-codebase-file-permission.policy" ), evaluator );

        ap.refresh();
        
        // ap.dump(System.out);

        URL url = new URL( "file:///bar.jar" ); 
        CodeSource cs = new CodeSource( url, new Certificate[0]);
        
        PermissionCollection pc = ap.getPermissions( cs );
        
        assertNotNull( pc );
        
        Permission testPerm = new FilePermission( "/tmp/*", "read,write" );
        Permission testPerm2 = new FilePermission( "/usr/*", "write" ); // only read was granted
        
        assertTrue( pc.implies( testPerm ) );
        assertFalse( pc.implies( testPerm2 ) );
        
    }

    public void testMultipleCodebaseMixedPermissionLoader()
        throws Exception
    {
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( getWorkingDirectory()
                + "/src/test/resources/multiple-codebase-mixed-permission.policy" ), evaluator );

        ap.refresh();

        // ap.dump(System.out);
    }
    
    public void testSCLoader() throws Exception
    {
        JettyPolicy ap = new JettyPolicy(Collections.singleton(getWorkingDirectory() + "/src/main/config/lib/policy/jetty.policy"),evaluator);

        ap.refresh();
        ap.dump(System.out);
    }

    public void testMultipleFilePermissionLoader()
        throws Exception
    {
        Set<String> files = new HashSet<String>();
        
        files.add( getWorkingDirectory() + "/src/test/resources/single-codebase-file-permission.policy" );
        files.add( getWorkingDirectory() + "/src/test/resources/single-codebase-file-permission-2.policy" );
        
        JettyPolicy ap = new JettyPolicy( files, evaluator );

        ap.refresh();
        
        URL url = new URL( "file:///bar.jar" ); 
        CodeSource cs = new CodeSource( url, new Certificate[0]);
        
        PermissionCollection pc = ap.getPermissions( cs );
        
        assertNotNull( pc );
        
        Permission testPerm = new FilePermission( "/tmp/*", "read" );
        Permission testPerm2 = new FilePermission( "/usr/*", "write" ); // 
        
        assertTrue( pc.implies( testPerm ) );
        assertFalse( pc.implies( testPerm2 ) );
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
