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

package org.eclipse.jetty.annotations;

import java.util.List;

import org.eclipse.jetty.annotations.AnnotationParser.AbstractHandler;
import org.eclipse.jetty.annotations.AnnotationParser.ClassInfo;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * ClassInheritanceHandler
 *
 * As asm scans for classes, remember the type hierarchy.
 */
public class ClassInheritanceHandler extends AbstractHandler
{
    private static final Logger LOG = Log.getLogger(ClassInheritanceHandler.class);
    
    MultiMap<String> _inheritanceMap;
    
    public ClassInheritanceHandler()
    {
       _inheritanceMap = new MultiMap<>();
    }
    
    public ClassInheritanceHandler(MultiMap<String> map)
    {
        _inheritanceMap = map;
    }

    public void handle(ClassInfo classInfo)
    {
        try
        {
            for (int i=0; classInfo.getInterfaces() != null && i < classInfo.getInterfaces().length;i++)
            {
                _inheritanceMap.add (classInfo.getInterfaces()[i], classInfo.getClassName());
            }
            //To save memory, we don't record classes that only extend Object, as that can be assumed
            if (!"java.lang.Object".equals(classInfo.getSuperName()))
                _inheritanceMap.add(classInfo.getSuperName(), classInfo.getClassName());
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }  
    }
    
    public List<String> getClassNamesExtendingOrImplementing (String className)
    {
        return _inheritanceMap.getValues(className);
    }
    
    public MultiMap<String> getMap ()
    {
        return _inheritanceMap;
    }
}
