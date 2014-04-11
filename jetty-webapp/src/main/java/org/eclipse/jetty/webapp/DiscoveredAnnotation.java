//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.webapp;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 * DiscoveredAnnotation
 *
 * Represents an annotation that has been discovered
 * by scanning source code of WEB-INF/classes and WEB-INF/lib jars.
 * 
 */
public abstract class DiscoveredAnnotation
{
    private static final Logger LOG = Log.getLogger(DiscoveredAnnotation.class);

    protected WebAppContext _context;
    protected String _className;
    protected Class<?> _clazz;
    protected Resource _resource; //resource it was discovered on, can be null (eg from WEB-INF/classes)
    
    public abstract void apply();
    
    public DiscoveredAnnotation (WebAppContext context, String className)
    {
        this(context,className, null);
    } 
    
    
    public DiscoveredAnnotation(WebAppContext context, String className, Resource resource)
    {
        _context = context;
        _className = className;
        _resource = resource;
    }
    
    public Resource getResource ()
    {
        return _resource;
    }
    
    public Class<?> getTargetClass()
    {
        if (_clazz != null)
            return _clazz;
        
        loadClass();
        
        return _clazz;
    }
    
    private void loadClass ()
    {
        if (_clazz != null)
            return;
        
        if (_className == null)
            return;
        
        try
        {
            _clazz = Loader.loadClass(null, _className);
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }  
}
