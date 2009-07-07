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

import javax.annotation.Resource;
import javax.annotation.Resources;
import javax.naming.NamingException;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.util.Loader;
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
        Class clazz = null;
        try
        {
            clazz = Loader.loadClass(null,className);
        }
        catch (Exception e)
        {
            Log.warn(e);
            return;
        }
        if (!Util.isServletType(clazz))
        {
            Log.debug("@Resources annotation ignored on on-servlet type class "+clazz.getName());
            return;
        }
        //Handle Resources annotation - add namespace entries
        Resources resources = (Resources)clazz.getAnnotation(Resources.class);
        if (resources == null)
            return;

        Resource[] resArray = resources.value();
        if (resArray==null||resArray.length==0)
            return;

        for (int j=0;j<resArray.length;j++)
        {
            String name = resArray[j].name();
            String mappedName = resArray[j].mappedName();
            Resource.AuthenticationType auth = resArray[j].authenticationType();
            Class type = resArray[j].type();
            boolean shareable = resArray[j].shareable();

            if (name==null || name.trim().equals(""))
            {
               Log.warn ("@Resource annotations on classes must contain a name (Common Annotations Spec Section 2.3)");
               break;
            }
            try
            {
                //TODO don't ignore the shareable, auth etc etc
                if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac, name, mappedName))
                    if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac.getServer(), name, mappedName))
                        throw new IllegalStateException("No resource bound at "+(mappedName==null?name:mappedName));
            }
            catch (NamingException e)
            {
                Log.warn(e);
            }
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
