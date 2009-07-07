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

import java.util.List;

import javax.annotation.security.RolesAllowed;
import org.eclipse.jetty.annotations.AnnotationParser.AnnotationNode;
import org.eclipse.jetty.util.log.Log;

public class RolesAllowedAnnotationHandler extends AbstractSecurityAnnotationHandler
{


    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<AnnotationNode> values)
    {
        //TODO check there is not already a RolesAllowed on this method
        
        org.eclipse.jetty.plus.annotation.RolesAllowed rolesAllowed = new org.eclipse.jetty.plus.annotation.RolesAllowed (className);
        
        //Get the String[] from values
        
        
        //TODO - set it on something
    }

    
    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<AnnotationNode> values)
    {
        Log.warn("RolesAllowed annotation not permitted on field "+fieldName+" - ignoring");
    }

   
    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<AnnotationNode> values)
    {
        if (!isHttpMethod(methodName))
        {
            Log.warn ("RolesAllowed annotation not permitted on method "+methodName+" - ignoring");
            return;
        }
        //TODO check there is not already a RolesAllowed on this method

        org.eclipse.jetty.plus.annotation.RolesAllowed rolesAllowed = new org.eclipse.jetty.plus.annotation.RolesAllowed (className);
        rolesAllowed.setMethodName(methodName);

        //Get the String[] from values

        //TODO - set it on something
    }

}
