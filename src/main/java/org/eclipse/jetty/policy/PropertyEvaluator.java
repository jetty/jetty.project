package org.eclipse.jetty.policy;

//========================================================================
//Copyright (c) 2003-2009 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at 
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses. 
//========================================================================


import java.io.File;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

/*
 * Property evaluator to provide common reference object for property access and evaluation
 * 
 * The origin of this class was in the Main.class of the jetty-start package where is where 
 * it picks up the convention of $() properties and ${} system properties
 * 
 * Not exactly sun convention but a jetty convention none the less 
 */
public class PropertyEvaluator extends HashMap<String,String>
{
    private static final long serialVersionUID = -7745629868268683553L;
    
    public PropertyEvaluator( Map<String,String> properties )
    {
        putAll( properties );
        put("/", File.separator ); // '/' is a special case when evaluated itself, resolves to File.separator as per policy parsing convention
    }
    
    /**
     * returns the value of it exists in this map, otherwise consults the System properties
     * 
     * @param name
     * @return
     */
    public String getSystemProperty(String name)
    {       
        if (containsKey(name))
        {
            return get(name);
        }
        
        return System.getProperty(name);
    }
    
    public String getProperty(String name)
    {
        return get(name);
    }
    
    /* ------------------------------------------------------------ */
    public String evaluate(String s)
    {
        
        int i1=0;
        int i2=0;
        /*
        while (s!=null)
        {
            i1=s.indexOf("$(",i2);
            if (i1<0)
                break;
            i2=s.indexOf(")",i1+2);
            if (i2<0)
                break;
            String name=s.substring(i1+2,i2);
            String property=getSystemProperty(name);
            s=s.substring(0,i1)+property+s.substring(i2+1);
        }
        
        i1=0;
        i2=0;
        */
        while (s!=null)
        {
            i1=s.indexOf("${",i2);
            if (i1<0)
                break;
            i2=s.indexOf("}",i1+2);
            if (i2<0)
                break;
            String name=s.substring(i1+2,i2);
            String property=getSystemProperty(name);
            s=s.substring(0,i1)+property+s.substring(i2+1);
        }
        
        return s;
    }
    
   
    
}
