package org.eclipse.jetty.policy.component;

import java.security.KeyStoreException;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import org.eclipse.jetty.policy.PolicyContext;
import org.eclipse.jetty.policy.PolicyException;

public class PrincipalNode extends AbstractNode
{
    /**
     * Wildcard value denotes any class and/or any name. Must be asterisk, for proper general expansion and
     * PrivateCredentialsPermission wildcarding
     */
    public static final String WILDCARD = "*"; //$NON-NLS-1$

    /**
     * The classname part of principal clause.
     */
    private String klass;

    /**
     * The name part of principal clause.
     */
    private String name;
    
    /**
     * cached principal if already computed
     */
    private Principal principal;
    
    public Principal toPrincipal( PolicyContext context ) throws PolicyException
    {
        if ( principal != null && !isDirty() )
        {
            return principal;
        }
        
        // if there is no keystore, there is no way to obtain a principal object // TODO validate we need this check
        if ( context.getKeystore() == null )
        {
            return null;
        }

        try
        {
            Certificate certificate = context.getKeystore().getCertificate( name );

            if ( certificate instanceof X509Certificate )
            {
                principal = ( (X509Certificate) certificate ).getSubjectX500Principal();
                return principal;
            }
            else
            {
                throw new PolicyException( "Unknown Certificate, unable to obtain Principal: " + certificate.getType() );
            }
        }
        catch ( KeyStoreException kse )
        {
            throw new PolicyException( kse );
        }
    }

    public void expand( PolicyContext context )
        throws PolicyException
    {
        name = context.evaluate( name );
        
        setExpanded(true);
    }

    public String getKlass()
    {
        return klass;
    }

    public void setKlass( String klass )
    {
        this.klass = klass;
    }

    public String getName()
    {
        return name;
    }

    public void setName( String name )
    {
        this.name = name;
    }
    
    
}
