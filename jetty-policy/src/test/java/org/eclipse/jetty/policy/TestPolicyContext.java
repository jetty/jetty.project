package org.eclipse.jetty.policy;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.policy.component.GrantNode;
import org.eclipse.jetty.policy.component.KeystoreNode;
import org.eclipse.jetty.policy.loader.PolicyFileScanner;

import junit.framework.TestCase;

public class TestPolicyContext
    extends TestCase
{
    public static final String __PRINCIPAL = "javax.security.auth.x500.X500Principal \"CN=Jetty Policy,OU=Artifact,O=Jetty Project,L=Earth,ST=Internet,C=US\"";
    
    
    
    
    @Override
    protected void setUp()
        throws Exception
    {
        
        System.setProperty( "basedir", getWorkingDirectory() );
        
        super.setUp();
    }

    public void testSelfPropertyExpansion() throws Exception
    {
        
        PolicyContext context = new PolicyContext();       
        PolicyFileScanner loader = new PolicyFileScanner();       
        List<GrantNode> grantEntries = new ArrayList<GrantNode>();
        List<KeystoreNode> keystoreEntries = new ArrayList<KeystoreNode>();
        
        File policyFile = new File( getWorkingDirectory() + "/src/test/resources/context/jetty-certificate.policy" );              
   
        loader.scanStream( new InputStreamReader( new FileInputStream( policyFile ) ), grantEntries, keystoreEntries );
        
        for ( Iterator<KeystoreNode> i = keystoreEntries.iterator(); i.hasNext();)
        {
            KeystoreNode node = i.next();
            node.expand( context );
            
            context.setKeystore( node.toKeyStore() );
        }    
        
        GrantNode grant = grantEntries.get( 0 );      
        grant.expand( context );
 
        Permission perm = grant.getPermissions().elements().nextElement();
        
        assertEquals( __PRINCIPAL, perm.getName() );
    }
    
    public void testAliasPropertyExpansion() throws Exception
    {
        
        PolicyContext context = new PolicyContext();       
        PolicyFileScanner loader = new PolicyFileScanner();       
        List<GrantNode> grantEntries = new ArrayList<GrantNode>();
        List<KeystoreNode> keystoreEntries = new ArrayList<KeystoreNode>();
        
        File policyFile = new File( getWorkingDirectory() + "/src/test/resources/context/jetty-certificate-alias.policy" );              
   
        loader.scanStream( new InputStreamReader( new FileInputStream( policyFile ) ), grantEntries, keystoreEntries );
        
        for ( Iterator<KeystoreNode> i = keystoreEntries.iterator(); i.hasNext();)
        {
            KeystoreNode node = i.next();
            node.expand( context );
            
            context.setKeystore( node.toKeyStore() );
        }    
        
        GrantNode grant = grantEntries.get( 0 );      
        grant.expand( context );
 
        Permission perm = grant.getPermissions().elements().nextElement();
        
        assertEquals( __PRINCIPAL, perm.getName() );
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
