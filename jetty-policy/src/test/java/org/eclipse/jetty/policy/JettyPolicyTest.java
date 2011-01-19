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

package org.eclipse.jetty.policy;

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
import java.util.PropertyPermission;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class JettyPolicyTest
{
    private HashMap<String, String> evaluator = new HashMap<String, String>();

    @Before
    public void setUp() throws Exception
    {
        evaluator.put("jetty.home",MavenTestingUtils.getBaseURI().toASCIIString());
        evaluator.put("basedir",MavenTestingUtils.getBaseURI().toASCIIString());
    }


    /**
     * Simple test for loading a policy file and validating that the AllPermission
     * was granted successfully.
     */
    @Test
    public void testGlobalAllPermissionLoader() throws Exception
    {

        JettyPolicy ap = new JettyPolicy(  Collections.singleton( MavenTestingUtils.getBasedir().getAbsolutePath() + "/src/test/resources/global-all-permission.policy" ), evaluator );
        ap.refresh();

        PermissionCollection pc = ap.getPermissions( new ProtectionDomain( null, null ) );

        assertNotNull( pc );

        Permission testPerm = new FilePermission( "/tmp", "read" );

        assertTrue( pc.implies( testPerm ) );

//        for ( Enumeration<Permission> e = pc.elements(); e.hasMoreElements(); )
//        {
//            System.out.println( "Permission: " + e.nextElement().getClass().getName() );
//        }
    }

    /** 
     * Simple test of loading a policy file with a single codebase defined that grants specific 
     * FilePermission.  Then test that read and write were granted but delete was not.
     */
    @Test
    public void testSingleCodebaseFilePermissionLoader() throws Exception
    {
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( MavenTestingUtils.getBasedir().getAbsolutePath()
                + "/src/test/resources/single-codebase-file-permission.policy" ), evaluator );
        ap.refresh();

        URL url = new URL( "file:///foo.jar" );
        CodeSource cs = new CodeSource( url, new Certificate[0]);

        PermissionCollection pc = ap.getPermissions( cs );

        assertNotNull( pc );

        Permission testReadPerm = new FilePermission( "/tmp/*", "read" );
        Permission testWritePerm = new FilePermission( "/tmp/*", "write" );
        Permission testDeletePerm = new FilePermission( "/tmp/*", "delete" );

        assertTrue( pc.implies( testReadPerm ) );
        assertTrue( pc.implies( testWritePerm ) );  
        assertFalse(pc.implies( testDeletePerm ) );
    }

    /**
     * Tests multiple codebases in a single policy file are loaded correctly and that the various 
     * grants do indeed work accordingly
     * 
     * @throws Exception
     */
    @Test
    public void testMultipleCodebaseFilePermissionLoader() throws Exception
    {
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( MavenTestingUtils.getBasedir().getAbsolutePath()
                + "/src/test/resources/multiple-codebase-file-permission.policy" ), evaluator );

        ap.refresh();
        
        // test the bar.jar codebase grant
        URL url = new URL( "file:///bar.jar" );
        CodeSource cs = new CodeSource( url, new Certificate[0]);

        PermissionCollection barPermissionCollection = ap.getPermissions( cs );

        assertNotNull( barPermissionCollection );

        Permission testBarPerm = new FilePermission( "/tmp/*", "read,write" );
        Permission testBarPerm2 = new FilePermission( "/usr/*", "read" ); // only read was granted
        Permission testBarPerm3 = new FilePermission( "/usr/*", "write" ); // only read was granted

        assertTrue( barPermissionCollection.implies( testBarPerm ) );
        assertTrue( barPermissionCollection.implies( testBarPerm2 ) );
        assertFalse( barPermissionCollection.implies( testBarPerm3 ) );
        
        // test the global permission grant
        PermissionCollection globalPermissionCollection = ap.getPermissions( new ProtectionDomain( null, null ) );
        
        assertNotNull( globalPermissionCollection );
        
        Permission testPropertyPermission = new PropertyPermission("main.class","read");
        assertTrue( globalPermissionCollection.implies(testPropertyPermission));
        // its global so it ought to be global, double check that
        assertTrue( barPermissionCollection.implies(testPropertyPermission));
        
        // test the foo.jar codebase grant
        URL fooUrl = new URL( "file:///foo.jar" );
        CodeSource fooCodeSource = new CodeSource( fooUrl, new Certificate[0]);

        PermissionCollection fooPermissionCollection = ap.getPermissions( fooCodeSource );

        assertNotNull( fooPermissionCollection );
        
        Permission testFooPerm = new FilePermission( "/tmp/*", "read,write" );
        Permission testFooPerm2 = new FilePermission( "/tmp/*", "read,write,delete" );

        assertTrue( fooPermissionCollection.implies(testFooPerm) );
        assertFalse( fooPermissionCollection.implies(testFooPerm2) );

        // make sure that the foo codebase isn't getting bar permissions
        assertFalse( fooPermissionCollection.implies(testBarPerm2) );
        // but make sure that foo codebase is getting global
        assertTrue( fooPermissionCollection.implies(testPropertyPermission));        
    }

    @Test
    public void testMultipleCodebaseMixedPermissionLoader() throws Exception
    {
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( MavenTestingUtils.getBasedir().getAbsolutePath()
                + "/src/test/resources/multiple-codebase-mixed-permission.policy" ), evaluator );

        ap.refresh();

        // test the bar.jar codebase grant
        URL url = new URL( "file:///bar.jar" );
        CodeSource cs = new CodeSource( url, new Certificate[0]);

        PermissionCollection barPermissionCollection = ap.getPermissions( cs );

        assertNotNull( barPermissionCollection );

        Permission testBarPerm = new FilePermission( "/tmp/*", "read,write" );
        Permission testBarPerm2 = new FilePermission( "/usr/*", "read" );

        assertTrue( barPermissionCollection.implies( testBarPerm ) );
        assertTrue( barPermissionCollection.implies( testBarPerm2 ) );
        
        // test the global permission grant
        PermissionCollection globalPermissionCollection = ap.getPermissions( new ProtectionDomain( null, null ) );
        
        assertNotNull( globalPermissionCollection );
        
        Permission testPropertyPermission = new PropertyPermission("main.class","read");
        assertTrue( globalPermissionCollection.implies(testPropertyPermission));
        // its global so it ought to be global, double check that
        assertTrue( barPermissionCollection.implies(testPropertyPermission));
        
        // test the foo.jar codebase grant
        URL fooUrl = new URL( "file:///foo.jar" );
        CodeSource fooCodeSource = new CodeSource( fooUrl, new Certificate[0]);

        PermissionCollection fooPermissionCollection = ap.getPermissions( fooCodeSource );

        assertNotNull( fooPermissionCollection );
        
        Permission testFooPerm = new FilePermission( "/tmp/*", "read,write" );
        Permission testFooPerm2 = new FilePermission( "/tmp/*", "read,write,delete" );

        assertTrue( fooPermissionCollection.implies(testFooPerm) );
        assertFalse( fooPermissionCollection.implies(testFooPerm2) );

        // make sure that the foo codebase isn't getting bar permissions
        assertFalse( fooPermissionCollection.implies(testBarPerm2) );
        // but make sure that foo codebase is getting global
        assertTrue( fooPermissionCollection.implies(testPropertyPermission));    
    }

    /**
     * Sanity check that jetty policy file parses
     * 
     * TODO insert typical jetty requirements in here to test
     * 
     * @throws Exception
     */
    @Test
    public void testSCLoader() throws Exception
    {
        JettyPolicy ap = new JettyPolicy(Collections.singleton(MavenTestingUtils.getBasedir().getAbsolutePath() + "/src/main/config/lib/policy/jetty.policy"),evaluator);

        ap.refresh();
    }

    /**
     * Test the simple loading of multiple files with no overlapping of security permission code sources
     * @throws Exception
     */
    @Test
    public void testMultipleFilePermissionLoader() throws Exception
    {
        Set<String> files = new HashSet<String>();

        files.add( MavenTestingUtils.getBasedir().getAbsolutePath() + "/src/test/resources/single-codebase-file-permission.policy" );
        files.add( MavenTestingUtils.getBasedir().getAbsolutePath() + "/src/test/resources/single-codebase-file-permission-2.policy" );

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
    
    /**
     * Tests the aggregation of multiple policy files into the same protection 
     * domain of a granted codesource
     * 
     * @throws Exception
     */
    @Test
    public void testAggregateMultipleFilePermissionLoader() throws Exception
    {
        Set<String> files = new HashSet<String>();

        files.add( MavenTestingUtils.getBasedir().getAbsolutePath() + "/src/test/resources/single-codebase-file-permission.policy" );
        files.add( MavenTestingUtils.getBasedir().getAbsolutePath() + "/src/test/resources/single-codebase-file-permission-2.policy" );
        files.add( MavenTestingUtils.getBasedir().getAbsolutePath() + "/src/test/resources/single-codebase-file-permission-3.policy" );

        JettyPolicy ap = new JettyPolicy( files, evaluator );

        ap.refresh();

        URL url = new URL( "file:///bar.jar" );
        CodeSource cs = new CodeSource( url, new Certificate[0]);

        PermissionCollection pc = ap.getPermissions( cs );

        assertNotNull( pc );

        Permission testPerm = new FilePermission( "/tmp/*", "read, write" );
        Permission testPerm2 = new FilePermission( "/usr/*", "write" );

        // this tests that two policy files granting to the same codebase aggregate
        // together their permissions, /tmp/* should be read, write after loading policy 2 and 3
        assertTrue( pc.implies( testPerm ) );
        assertFalse( pc.implies( testPerm2 ) );
               
    }
    
}
