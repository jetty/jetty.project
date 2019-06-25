//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings.
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

package org.eclipse.jetty.ant;

import java.io.File;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebXmlConfiguration;

/**
 * This configuration object provides additional way to inject application
 * properties into the configured web application. The list of classpath files,
 * the application base directory and web.xml file could be specified in this
 * way.
 */
public class AntWebXmlConfiguration extends WebXmlConfiguration
{
    private static final Logger LOG = Log.getLogger(WebXmlConfiguration.class);

    /**
     * List of classpath files.
     */
    private List classPathFiles;

    /**
     * Web application root directory.
     */
    private File webAppBaseDir;

    public AntWebXmlConfiguration()
    {
        super();
    }

    public void setClassPathFiles(List classPathFiles)
    {
        this.classPathFiles = classPathFiles;
    }

    public void setWebAppBaseDir(File webAppBaseDir)
    {
        this.webAppBaseDir = webAppBaseDir;
    }
}
