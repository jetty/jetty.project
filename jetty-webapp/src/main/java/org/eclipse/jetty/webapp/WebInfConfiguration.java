// ========================================================================
// Copyright (c) 2003-2009 Mort Bay Consulting Pty. Ltd.
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
package org.eclipse.jetty.webapp;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
/* ------------------------------------------------------------------------------- */
/**
 * Configure class path from a WEB-INF directory found within a contexts resource base.
 * 
 * 
 */
public class WebInfConfiguration implements Configuration
{
    protected WebAppContext _context;

    public WebInfConfiguration()
    {
    }

    /* ------------------------------------------------------------------------------- */
    public void setWebAppContext (WebAppContext context)
    {
        _context = context;
    }

    /* ------------------------------------------------------------------------------- */
    public WebAppContext getWebAppContext()
    {
        return _context;
    }

    /* ------------------------------------------------------------------------------- */
    /** Configure ClassPath.
     * This method is called before the context ClassLoader is created.
     * Paths and libraries should be added to the context using the setClassPath,
     * addClassPath and addClassPaths methods.  The default implementation looks
     * for WEB-INF/classes, WEB-INF/lib/*.zip and WEB-INF/lib/*.jar
     * @throws Exception
     */
    public  void configureClassLoader()
    throws Exception
    {
        //cannot configure if the context is already started
        if (_context.isStarted())
        {
            if (Log.isDebugEnabled()){Log.debug("Cannot configure webapp after it is started");}
            return;
        }

        Resource web_inf=_context.getWebInf();

        // Add WEB-INF classes and lib classpaths
        if (web_inf != null && web_inf.isDirectory() && _context.getClassLoader() instanceof WebAppClassLoader)
        {
            // Look for classes directory
            Resource classes= web_inf.addPath("classes/");
            if (classes.exists())
                ((WebAppClassLoader)_context.getClassLoader()).addClassPath(classes.toString());

            // Look for jars
            Resource lib= web_inf.addPath("lib/");
            if (lib.exists() || lib.isDirectory())
                ((WebAppClassLoader)_context.getClassLoader()).addJars(lib);
        }
        
     }

    /* ------------------------------------------------------------------------------- */
    public void configureDefaults() throws Exception
    {
    }

    /* ------------------------------------------------------------------------------- */
    public void configureWebApp() throws Exception
    {
    }

    /* ------------------------------------------------------------------------------- */
    public void deconfigureWebApp() throws Exception
    {
    }

}
