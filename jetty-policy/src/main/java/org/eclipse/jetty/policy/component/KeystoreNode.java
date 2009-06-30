package org.eclipse.jetty.policy.component;

import java.io.InputStream;
import java.net.URL;
import java.security.KeyStore;

import org.eclipse.jetty.policy.PolicyContext;
import org.eclipse.jetty.policy.PolicyException;

public class KeystoreNode extends AbstractNode
{
    /**
     * The URL part of keystore clause.
     */
    private String url;

    /**
     * The typename part of keystore clause.
     */
    private String type;
    
    // cached value
    private KeyStore keystore;

    public KeyStore toKeyStore() throws PolicyException
    { 
        if ( keystore != null && !isDirty() )
        {
            return keystore;
        }
        
        try 
        {           
            keystore = KeyStore.getInstance( type );
            
            URL keyStoreLocation = new URL ( url );
            InputStream istream = keyStoreLocation.openStream();
            
            keystore.load( istream, null );
        }
        catch ( Exception e )
        {
            throw new PolicyException( e );
        }
        
        return keystore; 
    }
    
    public void expand( PolicyContext context ) throws PolicyException
    {
        url = context.evaluate( url );
        
        setExpanded( true );
    }

    public String getUrl()
    {
        return url;
    }

    public void setUrl( String url )
    {
        this.url = url;
    }

    public String getType()
    {
        return type;
    }

    public void setType( String type )
    {
        this.type = type;
    }    
}
