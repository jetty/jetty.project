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

    @Test
    public void testGlobalAllPermissionLoader() throws Exception
    {

        JettyPolicy ap = new JettyPolicy(  Collections.singleton( MavenTestingUtils.getBasedir().getAbsolutePath() + "/src/test/resources/global-all-permission.policy" ), evaluator );
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

        Permission testPerm = new FilePermission( "/tmp/*", "read" );

        assertTrue( pc.implies( testPerm ) );
    }

    @Test
    public void testMultipleCodebaseFilePermissionLoader() throws Exception
    {
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( MavenTestingUtils.getBasedir().getAbsolutePath()
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

    @Test
    public void testMultipleCodebaseMixedPermissionLoader() throws Exception
    {
        JettyPolicy ap =
            new JettyPolicy( Collections.singleton( MavenTestingUtils.getBasedir().getAbsolutePath()
                + "/src/test/resources/multiple-codebase-mixed-permission.policy" ), evaluator );

        ap.refresh();

        // ap.dump(System.out);
    }

    @Test
    public void testSCLoader() throws Exception
    {
        JettyPolicy ap = new JettyPolicy(Collections.singleton(MavenTestingUtils.getBasedir().getAbsolutePath() + "/src/main/config/lib/policy/jetty.policy"),evaluator);

        ap.refresh();
        ap.dump(System.out);
    }

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
}
