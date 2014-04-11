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


package org.eclipse.jetty.util.preventers;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * AbstractLeakPreventer
 *
 * Abstract base class for code that seeks to avoid pinning of webapp classloaders by using the jetty classloader to
 * proactively call the code that pins them (generally pinned as static data members, or as static
 * data members that are daemon threads (which use the context classloader)).
 * 
 * Instances of subclasses of this class should be set with Server.addBean(), which will
 * ensure that they are called when the Server instance starts up, which will have the jetty
 * classloader in scope.
 *
 */
public abstract class AbstractLeakPreventer extends AbstractLifeCycle
{
    protected static final Logger LOG = Log.getLogger(AbstractLeakPreventer.class);
    
    /* ------------------------------------------------------------ */
    abstract public void prevent(ClassLoader loader);
    
    
    /* ------------------------------------------------------------ */
    @Override
    protected void doStart() throws Exception
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
            prevent(getClass().getClassLoader());
            super.doStart();
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(loader);
        }
    }
}
