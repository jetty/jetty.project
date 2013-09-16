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

import java.security.Policy;
import java.util.HashMap;
import java.util.Map;

/**
 * 
 *
 */
public class JettyPolicyConfigurator
{
    String _policyDirectory;
    Map<String, String> _properties = new HashMap<String,String>();
    
    public JettyPolicyConfigurator()
    {
        
    }
    
    public void setPolicyDirectory( String policyDirectory )
    {
        _policyDirectory = policyDirectory;
    }
    
    public void addProperty( String name, String value )
    {
        _properties.put(name,value);
    }
    
    public void initialize()
    {
        JettyPolicy jpolicy = new JettyPolicy(_policyDirectory,_properties);

        jpolicy.refresh();
        Policy.setPolicy(jpolicy);
        System.setSecurityManager(new SecurityManager());
    }

}
