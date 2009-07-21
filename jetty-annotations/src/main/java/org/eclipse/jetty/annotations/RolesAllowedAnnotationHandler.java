// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.annotations.AnnotationParser.ListValue;
import org.eclipse.jetty.plus.annotation.AbstractAccessControl;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * RolesAllowedAnnotationHandler
 *
 * Process a RolesAllowed annotation.
 * 
 * This annotation translates to a security-constraint with an auth-constraint
 * naming each role in the list of values of the annotation. This auth-constraint
 * is applied to a web-resource-collection naming the url-pattern(s) mapped to
 * Servlet on which this annotation appears.
 * 
 * Sec. 13.4.0
 *  &quot;When a security-constraint in the portable deployment descriptor 
 *  includes a url-pattern that matches a request URL, the security annotations 
 *  described in this section MUST have no effect on the access policy enforced 
 *  by the container on the request URL.&quot;
 * n
 */
public class RolesAllowedAnnotationHandler extends AbstractSecurityAnnotationHandler
{
    public RolesAllowedAnnotationHandler(WebAppContext context)
    {
        super(context);
    }


    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<Value> values)
    {
        if (exists(className, AUTH_TYPES))
        {
            Log.warn("Multiple conflicting security annotations for "+className);
            return;
        }


        if (values != null && values.size() == 1)
        {
            org.eclipse.jetty.plus.annotation.RolesAllowed rolesAllowed = new org.eclipse.jetty.plus.annotation.RolesAllowed (className);
            Value v = values.get(0);
            ArrayList<String> roles = new ArrayList<String>();
            if (v instanceof ListValue)
            {
                for (Value n : ((ListValue)v).getList())
                    roles.add((String)n.getValue());
            }
            rolesAllowed.setRoles(roles.toArray(new String[roles.size()]));
            add (rolesAllowed);
        }
    }

    
    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
        Log.warn("RolesAllowed annotation not permitted on field "+fieldName+" - ignoring");
    }

   
    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        if (!isHttpMethod(methodName))
        {
            Log.warn ("RolesAllowed annotation not permitted on method "+methodName+" - ignoring");
            return;
        }
        
        //check there is not already other confliciting security access annotations on this method
        if (exists(className, methodName, AUTH_TYPES))
        {
            Log.warn ("Multiple conflicting security access annotations for "+className+"."+methodName);
            return;
        }
       
      
        if (values != null && values.size() == 1)
        {
            org.eclipse.jetty.plus.annotation.RolesAllowed rolesAllowed = new org.eclipse.jetty.plus.annotation.RolesAllowed (className);
            rolesAllowed.setMethodName(methodName);
            Value v = values.get(0);
            ArrayList<String> roles = new ArrayList<String>();
            if (v instanceof ListValue)
            {
                for (Value n : ((ListValue)v).getList())
                    roles.add((String)n.getValue());
            }
            rolesAllowed.setRoles(roles.toArray(new String[roles.size()]));
            add(rolesAllowed);
        }
    }

}
