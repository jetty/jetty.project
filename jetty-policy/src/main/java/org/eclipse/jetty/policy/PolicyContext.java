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
import java.security.KeyStore;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

public class PolicyContext
{
    private Map<String, String> properties = new HashMap<String, String>();
    
    private Principal[] principals;
    private KeyStore keystore;
    
    public PolicyContext()
    {
        // special property case for resolving ${/} to native separator
        properties.put( "/", File.separator );
    }
    
    public void addProperty( String name, String value )
    {
        this.properties.put( name, value );
    }
    
    public void setProperties( Map<String,String> properties )
    {
        this.properties.putAll( properties );
    }

    public KeyStore getKeystore()
    {
        return keystore;
    }

    public void setKeystore( KeyStore keystore )
    {
        this.keystore = keystore;
    }  

    public Principal[] getPrincipals()
    {
        return principals;
    }

    public void setPrincipals( Principal[] principals )
    {
        this.principals = principals;
    }

    public String evaluate(String s) throws PolicyException
    {       
        s = processProtocols( s );
        
        int i1=0;
        int i2=0;

        while (s!=null)
        {
            //System.out.println("Reviewing: " + s );
            //i1=s.indexOf("${",i2);
            i1=s.indexOf("${");
            //System.out.println("i1:" + i1);
            if (i1<0)
            {
                break;
            }
            
            i2=s.indexOf("}",i1+2);
            //System.out.println("i2:" + i2);
            if (i2<0)
            {
                break;
            }
     
            String property=getProperty(s.substring(i1+2,i2));
       
            s=s.substring(0,i1)+property+s.substring(i2+1);
            
            //System.out.println("expanded to: " + s);
        }
        
        return s;
    }
    
    private String processProtocols( String s ) throws PolicyException
    {
        int i1=0;
        int i2=0;

        while (s!=null)
        {
            i1=s.indexOf("${{");
            if (i1<0)
            {
                break;
            }
            
            i2=s.indexOf("}}",i1+2);
            if (i2<0)
            {
                break;
            }
     
            String property;
            String target = s.substring(i1+3,i2);
            
            if ( target.indexOf( ":" ) >= 0 )
            {
                String[] resolve = target.split( ":" );
                property = resolve(resolve[0], resolve[1] );
            }
            else
            {
                property = resolve( target, null );
            }
            s=s.substring(0,i1)+property+s.substring(i2+2);
        }
        
        return s;
    }
    
    
    private String getProperty(String name)
    {       
        if (properties.containsKey(name))
        {
            return properties.get(name);
        }
        
        return System.getProperty(name);
    }
    
    private String resolve( String protocol, String data ) throws PolicyException
    {

        if ( "self".equals( protocol ) ) { //$NON-NLS-1$
            // need expanding to list of principals in grant clause
            if ( principals != null && principals.length != 0 )
            {
                StringBuilder sb = new StringBuilder();
                for ( int i = 0; i < principals.length; ++i )
                {
                    sb.append( principals[i].getClass().getName() );
                    sb.append( " \"" );
                    sb.append( principals[i].getName() );
                    sb.append( "\" " );
                }
                return sb.toString();
            }
            else
            {
                throw new PolicyException( "self can not be expanded, missing principals" );
            }
        }
        if ( "alias".equals( protocol ) ) 
        { 
            try
            {
                 Certificate cert = keystore.getCertificate(data);
               
                 if ( cert instanceof X509Certificate )
                 {
                     Principal principal = ((X509Certificate) cert).getSubjectX500Principal(); 
                     StringBuilder sb = new StringBuilder();
                     sb.append( principal.getClass().getName() );
                     sb.append( " \"" );
                     sb.append( principal.getName() );
                     sb.append( "\" " );
                     return sb.toString();
                 }
                 else
                 {
                     throw new PolicyException( "alias can not be expanded, bad cert" );
                 }
            }
            catch ( Exception e )
            {
                throw new PolicyException( "alias can not be expanded: " + data );
            }
        }
        throw new PolicyException( "unknown protocol: " + protocol );
    }    
}
