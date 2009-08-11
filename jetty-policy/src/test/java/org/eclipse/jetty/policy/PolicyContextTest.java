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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import junit.framework.TestCase;

import org.eclipse.jetty.policy.entry.GrantEntry;
import org.eclipse.jetty.policy.entry.KeystoreEntry;
import org.eclipse.jetty.policy.loader.PolicyFileScanner;

public class PolicyContextTest
    extends TestCase
{
    public static final String __PRINCIPAL = "javax.security.auth.x500.X500Principal \"CN=Jetty Policy,OU=Artifact,O=Jetty Project,L=Earth,ST=Internet,C=US\"";
    
    private boolean _runningOnWindows;
    
    
    @Override
    protected void setUp()
        throws Exception
    {
        _runningOnWindows = System.getProperty( "os.name" ).startsWith( "Windows" );
        
        System.setProperty( "basedir", getWorkingDirectory() );
        
        super.setUp();
    }

    public void testSelfPropertyExpansion() throws Exception
    {
        
        PolicyContext context = new PolicyContext();       
        PolicyFileScanner loader = new PolicyFileScanner();       
        List<GrantEntry> grantEntries = new ArrayList<GrantEntry>();
        List<KeystoreEntry> keystoreEntries = new ArrayList<KeystoreEntry>();
        
        File policyFile = new File( getWorkingDirectory() + "/src/test/resources/context/jetty-certificate.policy" );              
   
        loader.scanStream( new InputStreamReader( new FileInputStream( policyFile ) ), grantEntries, keystoreEntries );
        
        if ( !_runningOnWindows ) //temporary, create alternate file to load for windows
        {
        
        for ( Iterator<KeystoreEntry> i = keystoreEntries.iterator(); i.hasNext();)
        {
            KeystoreEntry node = i.next();
            node.expand( context );
            
            context.setKeystore( node.toKeyStore() );
        }    
        
        GrantEntry grant = grantEntries.get( 0 );      
        grant.expand( context );
 
        Permission perm = grant.getPermissions().elements().nextElement();
        
        
        assertEquals( __PRINCIPAL, perm.getName() );
        }
    }
    
    public void testAliasPropertyExpansion() throws Exception
    {
        
        PolicyContext context = new PolicyContext();       
        PolicyFileScanner loader = new PolicyFileScanner();       
        List<GrantEntry> grantEntries = new ArrayList<GrantEntry>();
        List<KeystoreEntry> keystoreEntries = new ArrayList<KeystoreEntry>();
        
        File policyFile = new File( getWorkingDirectory() + "/src/test/resources/context/jetty-certificate-alias.policy" );              
   
        loader.scanStream( new InputStreamReader( new FileInputStream( policyFile ) ), grantEntries, keystoreEntries );
        
        if ( !_runningOnWindows ) //temporary, create alternate file to load for windows
        {
       
        for ( Iterator<KeystoreEntry> i = keystoreEntries.iterator(); i.hasNext();)
        {
            KeystoreEntry node = i.next();
            node.expand( context );
            
            context.setKeystore( node.toKeyStore() );
        }    
        
        GrantEntry grant = grantEntries.get( 0 );      
        grant.expand( context );
 
        Permission perm = grant.getPermissions().elements().nextElement();
        
        assertEquals( __PRINCIPAL, perm.getName() );
        }
    }
    
    public void testFileSeparatorExpansion() throws Exception
    {
        PolicyContext context = new PolicyContext();  
        context.addProperty( "foo", "bar" );
        
        assertEquals(File.separator, context.evaluate( "${/}" ) );

        assertEquals(File.separator + "bar" + File.separator, context.evaluate( "${/}${foo}${/}" ) );
        

        assertEquals(File.separator + File.separator, context.evaluate( "${/}${/}" ) );
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
