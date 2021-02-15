//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.annotations.AnnotationParser.AbstractHandler;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * DiscoverableAnnotationHandler
 *
 * Base class for handling the discovery of an annotation.
 */
public abstract class AbstractDiscoverableAnnotationHandler extends AbstractHandler
{
    protected WebAppContext _context;

    public AbstractDiscoverableAnnotationHandler(WebAppContext context)
    {
        _context = context;
    }

    public void addAnnotation(DiscoveredAnnotation a)
    {
        _context.getMetaData().addDiscoveredAnnotation(a);
    }
}
