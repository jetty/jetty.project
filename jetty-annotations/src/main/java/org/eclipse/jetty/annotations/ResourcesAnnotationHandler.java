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

import javax.naming.NamingException;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.ListValue;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

public class ResourcesAnnotationHandler implements AnnotationHandler
{

    protected WebAppContext _wac;

    public ResourcesAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
    }
    
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<Value> values)
    {
        if (values != null && values.size() == 1)
        {
            List<ListValue> list = (List<ListValue>)(values.get(0).getValue());
            for (ListValue resource : list)
            {
                List<Value> resourceValues = resource.getList();
                String name = null;
                String mappedName = null;
                for (Value v:resourceValues)
                {
                    if ("name".equals(v.getName()))
                        name = (String)v.getValue();
                    else if ("mappedName".equals(v.getName()))
                        mappedName = (String)v.getValue();
                }
                if (name == null)
                    Log.warn ("Skipping Resources(Resource) annotation with no name on class "+className);
                else
                {
                    try
                    {
                        //TODO don't ignore the shareable, auth etc etc
                        if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac, name, mappedName))
                            if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac.getServer(), name, mappedName))
                               Log.warn("Skipping Resources(Resource) annotation on "+className+" for name "+name+": No resource bound at "+(mappedName==null?name:mappedName));
                    }
                    catch (NamingException e)
                    {
                        Log.warn(e);
                    }
                } 
            }
        }
        else
        {
            Log.warn("Skipping empty or incorrect Resources annotation on "+className);
        }
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
        Log.warn ("@Resources not applicable for fields: "+className+"."+fieldName);
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        Log.warn ("@Resources not applicable for methods: "+className+"."+methodName);
    }

}
