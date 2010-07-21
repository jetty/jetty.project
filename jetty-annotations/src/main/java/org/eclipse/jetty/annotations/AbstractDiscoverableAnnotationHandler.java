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

import org.eclipse.jetty.annotations.AnnotationParser.DiscoverableAnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * DiscoverableAnnotationHandler
 *
 *
 */
public abstract class AbstractDiscoverableAnnotationHandler implements DiscoverableAnnotationHandler
{
    protected WebAppContext _context;
    protected List<DiscoveredAnnotation> _annotations = new ArrayList<DiscoveredAnnotation>();
    
    public AbstractDiscoverableAnnotationHandler(WebAppContext context)
    {
        _context = context;
    }

    
    public List<DiscoveredAnnotation> getAnnotationList ()
    {
        return _annotations;
    }
    
    public void resetList()
    {
        _annotations.clear();
    }
    
    
    public void addAnnotation (DiscoveredAnnotation a)
    {
        _annotations.add(a);
    }

}
