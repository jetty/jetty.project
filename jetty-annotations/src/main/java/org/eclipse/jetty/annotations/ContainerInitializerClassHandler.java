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

import org.eclipse.jetty.annotations.AnnotationParser.ClassHandler;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;

public class ContainerInitializerClassHandler implements ClassHandler
{
    public ContainerInitializer _initializer;
    public Class _handlesTypeClass;
    
    
    public ContainerInitializerClassHandler(ContainerInitializer initializer, Class c)
    {
        super();
        _initializer = initializer;
        _handlesTypeClass = c;
    }


    public void handle(String className, int version, int access, String signature, String superName, String[] interfaces)
    {
        try
        {
            System.err.print("Checking class "+className+" with super "+superName+" interfaces:");
            for (int i=0; interfaces != null && i<interfaces.length;i++)
                System.err.print(interfaces[i]+",");
            System.err.println();
            //Looking at a class - need to check if this class extends or implements the _handlesTypeClass
            if (superName != null && superName.equals(_handlesTypeClass.getName()))
                _initializer.addApplicableClass(Loader.loadClass(null, className));

            for (int i=0; interfaces != null && i<interfaces.length; i++)
            {
                if (interfaces[i].equals(_handlesTypeClass.getName()))
                    _initializer.addApplicableClass(Loader.loadClass(null, className));
            }
        }
        catch (Exception e)
        {
            Log.warn(e);
        }  
    }
}
