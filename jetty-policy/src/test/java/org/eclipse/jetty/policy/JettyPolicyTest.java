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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.FilePermission;
import java.net.URL;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.HashMap;
import java.util.PropertyPermission;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Before;
import org.junit.Test;

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
        JettyPolicy ap = new JettyPolicy(  MavenTestingUtils.getTestResourceDir("policy-test-1").getAbsolutePath(), evaluator );

        ap.refresh();

        PermissionCollection pc = ap.getPermissions(new ProtectionDomain(null,null));

        assertNotNull(pc);

        Permission testPerm = new FilePermission("/tmp","read");

        assertTrue(pc.implies(testPerm));

    }

    /** 
     * Simple test of loading a policy file with a single codebase defined that grants specific 
     * FilePermission.  Then test that read and write were granted but delete was not.
     */
    @Test
    public void testSingleCodebaseFilePermissionLoader() throws Exception
    {
        JettyPolicy ap = new JettyPolicy(  MavenTestingUtils.getTestResourceDir("policy-test-2").getAbsolutePath(), evaluator );
        
        ap.refresh();

        URL url = new URL("file:///foo.jar");
        CodeSource cs = new CodeSource(url,new Certificate[0]);

        PermissionCollection pc = ap.getPermissions(cs);

        assertNotNull(pc);

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
        JettyPolicy ap = new JettyPolicy(  MavenTestingUtils.getTestResourceDir("policy-test-3").getAbsolutePath(), evaluator );

        ap.refresh();
        
        // test the bar.jar codebase grant
        URL url = new URL("file:///bar.jar");
        CodeSource cs = new CodeSource(url,new Certificate[0]);

        PermissionCollection barPermissionCollection = ap.getPermissions(cs);

        assertNotNull( barPermissionCollection );

        Permission testBarPerm = new FilePermission("/tmp/*","read,write");
        Permission testBarPerm2 = new FilePermission("/usr/*","read"); // only read was granted
        Permission testBarPerm3 = new FilePermission("/usr/*","write"); // only read was granted

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
        JettyPolicy ap = new JettyPolicy(  MavenTestingUtils.getTestResourceDir("policy-test-4").getAbsolutePath(), evaluator );

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
        JettyPolicy ap = new JettyPolicy(MavenTestingUtils.getProjectDir("src/main/config/lib/policy").getAbsolutePath(),evaluator);

        ap.refresh();
    }

    /**
     * Test the simple loading of multiple files with no overlapping of security permission code sources
     * @throws Exception
     */
    @Test
    public void testMultipleFilePermissionLoader() throws Exception
    {
        JettyPolicy ap = new JettyPolicy(  MavenTestingUtils.getTestResourceDir("policy-test-5").getAbsolutePath(), evaluator );

        ap.refresh();

        URL url = new URL("file:///bar.jar");
        CodeSource cs = new CodeSource(url,new Certificate[0]);

        PermissionCollection pc = ap.getPermissions(cs);

        assertNotNull(pc);

        Permission testPerm = new FilePermission("/tmp/*","read");
        Permission testPerm2 = new FilePermission("/usr/*","write"); //

        assertTrue(pc.implies(testPerm));
        assertFalse(pc.implies(testPerm2));
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
        JettyPolicy ap = new JettyPolicy(  MavenTestingUtils.getTestResourceDir("policy-test-6").getAbsolutePath(), evaluator );

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
    
    
    /**
     * test the resolution of the loading of the policy files
     * 
     * @throws Exception
     */
//    @Test
//    public void testPolicyDirectories() throws Exception
//    {
//        Set<String> files = new HashSet<String>();
//
//        files.add( MavenTestingUtils.getTestResourceFile("single-codebase-file-permission.policy").getAbsolutePath() );
//        files.add( MavenTestingUtils.getTestResourceDir("context").getAbsolutePath() );
//
//        JettyPolicy ap = new JettyPolicy( files, evaluator );
//
//        Assert.assertEquals(3, ap.getKnownPolicyFiles().size());      
//               
//    }
    
//    /**
//     * test the discovery and loading of template files
//     * 
//     * @throws Exception
//     */
//    @Test
//    public void testTemplateDirectories() throws Exception
//    {
//        Set<String> policyFiles = new HashSet<String>();
//        Set<String> templateFiles = new HashSet<String>();
//
//        policyFiles.add(MavenTestingUtils.getTestResourceFile("single-codebase-file-permission.policy").getAbsolutePath());
//        policyFiles.add(MavenTestingUtils.getTestResourceDir("context").getAbsolutePath());
//
//        templateFiles.add(MavenTestingUtils.getTestResourceDir("template").getAbsolutePath());
//
//        JettyPolicy ap = new JettyPolicy(policyFiles,templateFiles,evaluator);
//
//        Assert.assertEquals(3,ap.getKnownPolicyFiles().size());
//
//        Assert.assertEquals(2,ap.getKnownTemplateFiles().size());
//
//    }
//
//    /**
//     * tests the assigning of a template to a codesource
//     * 
//     * @throws Exception
//     */
//    @Test
//    public void testTemplateAssign() throws Exception
//    {
//        Set<String> policyFiles = new HashSet<String>();
//        Set<String> templateFiles = new HashSet<String>();
//
//        policyFiles.add(MavenTestingUtils.getTestResourceFile("single-codebase-file-permission.policy").getAbsolutePath());
//        policyFiles.add(MavenTestingUtils.getTestResourceDir("context").getAbsolutePath());
//
//        templateFiles.add(MavenTestingUtils.getTestResourceDir("template").getAbsolutePath());
//
//        JettyPolicy ap = new JettyPolicy(policyFiles,templateFiles,evaluator);
//
//        ap.assignTemplate("file:///template.jar",new String[]
//        { "template1", "template2" });
//
//        Assert.assertEquals(2,ap.getAssignedTemplates("file:///template.jar").length);
//
//    }
//
//    /**
//     * tests the assigning of a template to a codesource
//     * 
//     * @throws Exception
//     */
//    @Test
//    public void testTemplateRemove() throws Exception
//    {
//        Set<String> policyFiles = new HashSet<String>();
//        Set<String> templateFiles = new HashSet<String>();
//
//        policyFiles.add(MavenTestingUtils.getTestResourceFile("single-codebase-file-permission.policy").getAbsolutePath());
//        policyFiles.add(MavenTestingUtils.getTestResourceDir("context").getAbsolutePath());
//
//        templateFiles.add(MavenTestingUtils.getTestResourceDir("template").getAbsolutePath());
//
//        JettyPolicy ap = new JettyPolicy(policyFiles,templateFiles,evaluator);
//
//        ap.assignTemplate("file:///template.jar",new String[]
//        { "template1", "template2" });
//
//        Assert.assertEquals(2,ap.getAssignedTemplates("file:///template.jar").length);
//
//        ap.unassignTemplates("file:///template.jar");
//
//        Assert.assertEquals(0,ap.getAssignedTemplates("file:///template.jar").length);
//
//    }
//
//    @Test
//    public void testTemplatePermissions() throws Exception
//    {
//        Set<String> policyFiles = new HashSet<String>();
//        Set<String> templateFiles = new HashSet<String>();
//
//        policyFiles.add(MavenTestingUtils.getTestResourceFile("single-codebase-file-permission.policy").getAbsolutePath());
//        policyFiles.add(MavenTestingUtils.getTestResourceDir("context").getAbsolutePath());
//
//        templateFiles.add(MavenTestingUtils.getTestResourceDir("template").getAbsolutePath());
//
//        JettyPolicy ap = new JettyPolicy(policyFiles,templateFiles,evaluator);
//        
//        URL url = new URL("file:///template.jar");
//        CodeSource cs = new CodeSource(url,new Certificate[0]);
//
//        PermissionCollection pc = ap.getPermissions(cs);
//
//        assertNotNull(pc);
//
//        Permission testPerm = new FilePermission("/tmp/*","read");
//        Permission testPerm2 = new FilePermission("/tmp/*","write");
//
//        // no templates have been assigned
//        assertFalse(pc.implies(testPerm));
//
//        ap.assignTemplate("file:///template.jar",new String[] {"template1"});
//        
//        PermissionCollection pc2 = ap.getPermissions(cs);
//
//        assertNotNull(pc2);
//        
//        assertTrue(pc2.implies(testPerm));
//        assertFalse(pc2.implies(testPerm2));
//        
//        
//        ap.assignTemplate("file:///template.jar",new String[] {"template1", "template2"});
//        
//        PermissionCollection pc3 = ap.getPermissions(cs);
//
//        assertNotNull(pc3);
//        
//        assertTrue(pc3.implies(testPerm));
//        assertTrue(pc3.implies(testPerm2));
//    }
}
