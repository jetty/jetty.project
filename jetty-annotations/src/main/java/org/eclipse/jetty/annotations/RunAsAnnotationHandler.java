// ========================================================================
// Copyright (c) 2009 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.plus.annotation.RunAsCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

public class RunAsAnnotationHandler implements AnnotationHandler
{
    protected WebAppContext _wac;

    public RunAsAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
    }
    
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<Value> values)
    {
        RunAsCollection runAsCollection = (RunAsCollection)_wac.getAttribute(RunAsCollection.RUNAS_COLLECTION);
      
        try
        {
            if (values != null && values.size() == 1)
            {
                String role = (String)values.get(0).getValue();
                if (role != null)
                {
                    org.eclipse.jetty.plus.annotation.RunAs ra = new org.eclipse.jetty.plus.annotation.RunAs();
                    ra.setTargetClassName(className);
                    ra.setRoleName(role);
                    runAsCollection.add(ra);
                }
            }
            else
                Log.warn("Bad value for @RunAs annotation on class "+className);
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
      
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
       Log.warn ("@RunAs annotation not applicable for fields: "+className+"."+fieldName);
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        Log.warn("@RunAs annotation ignored on method: "+className+"."+methodName+" "+signature);
    }

}
