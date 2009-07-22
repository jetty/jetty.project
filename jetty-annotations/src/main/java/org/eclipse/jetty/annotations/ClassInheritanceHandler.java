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

import org.eclipse.jetty.annotations.AnnotationParser.ClassHandler;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.log.Log;

/**
 * ClassInheritanceHandler
 *
 * As asm scans for classes, remember the type hierarchy.
 */
public class ClassInheritanceHandler implements ClassHandler
{
    
    MultiMap _inheritanceMap = new MultiMap();
    
    public ClassInheritanceHandler()
    {
    }

    public void handle(String className, int version, int access, String signature, String superName, String[] interfaces)
    {
        try
        {
            for (int i=0; interfaces != null && i<interfaces.length;i++)
            {
                _inheritanceMap.add (interfaces[i], className);
            }
            //To save memory, we don't record classes that only extend Object, as that can be assumed
            if (!"java.lang.Object".equals(superName))
                _inheritanceMap.add(superName, className);
        }
        catch (Exception e)
        {
            Log.warn(e);
        }  
    }
    
    public List getClassNamesExtendingOrImplementing (String className)
    {
        return _inheritanceMap.getValues(className);
    }
    
    public MultiMap getMap ()
    {
        return _inheritanceMap;
    }
}
