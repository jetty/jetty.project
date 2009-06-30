package org.eclipse.jetty.policy.component;

import java.net.URL;
import java.security.CodeSource;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.jetty.policy.PolicyContext;
import org.eclipse.jetty.policy.PolicyException;



public class GrantNode extends AbstractNode
{

    /**
     * The signers part of grant clause. This is a comma-separated list of certificate aliases.
     */
    private String signers;

    /**
     * The codebase part of grant clause. This is an URL from which code originates.
     */
    private String codebase;

    /**
     * Collection of PrincipalEntries of grant clause.
     */
    private Collection<PrincipalNode> principalNodes;

    /**
     * Collection of PermissionEntries of grant clause.
     */
    private Collection<PermissionNode> permissionNodes;

    // cached permissions
    private PermissionCollection permissions;
    private Certificate[] signerArray;
    private CodeSource codesource;
    private Principal[] principals;
    
    /**
     * Adds specified element to the <code>principals</code> collection. If collection does not exist yet, creates a
     * new one.
     */
    public void addPrincipal( PrincipalNode pe )
    {
        if ( principalNodes == null )
        {
            principalNodes = new HashSet<PrincipalNode>();
        }
        principalNodes.add( pe );
    }

    public void expand( PolicyContext context ) throws PolicyException
    {
        signerArray = resolveToCertificates( context.getKeystore(), signers );  // TODO alter to support self:: etc
        codebase = context.getEvaluator().evaluate( codebase );
        
        if ( principalNodes != null )
        {
        Set<Principal> principalSet = new HashSet<Principal>(); // TODO address this not being accurate ( missing prinicipals in codestore) 
        for ( Iterator<PrincipalNode> i = principalNodes.iterator(); i.hasNext(); )
        {
            PrincipalNode node = i.next();
            node.expand( context );
            principalSet.add( node.toPrincipal( context ) );
        }
        principals = principalSet.toArray( new Principal[principalSet.size()] );
        }
        
        permissions = new Permissions();
        for ( Iterator<PermissionNode> i = permissionNodes.iterator(); i.hasNext(); )
        {
            PermissionNode node = i.next();
            node.expand( context );
            permissions.add( node.toPermission() );
        }
        
        setExpanded( true );
    }    
    
    public PermissionCollection getPermissions() throws PolicyException
    {
        return permissions;
    }    
    
    public Principal[] getPrincipals() throws PolicyException
    {
        return principals;
    }
    
    public CodeSource getCodeSource() throws PolicyException
    {
        if ( !isExpanded() )
        {
            throw new PolicyException("GrantNode needs to be expanded.");
        }
        
        try
        {
            if ( codesource == null && codebase != null )
            {
                URL url = new URL( codebase );
                codesource = new CodeSource( url, signerArray );
            }

            return codesource;
        }
        catch ( Exception e )
        {
            throw new PolicyException( e );
        }
    }
    
    /**
     * resolve signers into an array of certificates using a given keystore
     * 
     * @param keyStore
     * @param signers
     * @return
     * @throws Exception
     */
    private Certificate[] resolveToCertificates( KeyStore keyStore, String signers ) throws PolicyException
    {               
        if ( keyStore == null )
        {
            Certificate[] certs = null;
            return certs;
        }
                
        Set<Certificate> certificateSet = new HashSet<Certificate>();       
        StringTokenizer strTok = new StringTokenizer( signers, ",");
        
        for ( int i = 0; strTok.hasMoreTokens(); ++i )
        {
            try
            {               
                Certificate certificate = keyStore.getCertificate( strTok.nextToken().trim() );
                
                if ( certificate != null )
                {
                    certificateSet.add( certificate );
                }               
            }
            catch ( KeyStoreException kse )
            {
                throw new PolicyException( kse );
            }
        }
        
        return certificateSet.toArray( new Certificate[certificateSet.size()] );
    }
    

    public void setSigners( String signers )
    {
        this.signers = signers;
    }

    public void setCodebase( String codebase )
    {
        this.codebase = codebase;
    }

    public void setPrincipals( Collection<PrincipalNode> principals )
    {
        this.principalNodes = principals;
    }

    public void setPermissions( Collection<PermissionNode> permissions )
    {
        this.permissionNodes = permissions;
    }

    
    
}
