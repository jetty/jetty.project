// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================


package org.eclipse.jetty.annotations;

import java.util.HashSet;
import java.util.List;
import java.util.Set;


import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.ListValue;
import org.eclipse.jetty.annotations.AnnotationParser.SimpleValue;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

public class DeclareRolesAnnotationHandler implements AnnotationHandler
{
    protected WebAppContext _wac;
    
    public DeclareRolesAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
    }

    /** 
     * A DeclareRoles annotation is equivalent to a <security-role> in web.xml.
     * 
     * @see org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler#handleClass(java.lang.String, int, int, java.lang.String, java.lang.String, java.lang.String[], java.lang.String, java.util.List)
     */
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<Value> values)
    {
        //Add the role names to the list of roles for the webapp
        Set<String> roles = new HashSet<String>();
        
        try
        {
            Set<String> existing = ((ConstraintSecurityHandler)_wac.getSecurityHandler()).getRoles();
            roles.addAll(existing);
            
            if (values != null && values.size() == 1)
            {
                Value v = values.get(0);
                if (v instanceof SimpleValue)
                {
                    roles.add((String)((SimpleValue)v).getValue());
                }
                else if (v instanceof ListValue)
                {
                    for (Value vv:((ListValue)v).getList())
                    { 
                        roles.add((String)((SimpleValue)vv).getValue());
                    }
                }
            }
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
        Log.warn ("@DeclareRoles annotation not applicable for field: "+className+"."+fieldName);
    }

    public void handleMethod(String className, String methodName, int access, String desc, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        Log.warn ("@DeclareRoles annotation not applicable for method: "+className+"."+methodName);
    }

}
