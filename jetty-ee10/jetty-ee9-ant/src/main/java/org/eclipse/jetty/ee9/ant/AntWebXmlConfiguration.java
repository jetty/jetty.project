//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings and others.
//  ------------------------------------------------------------------------
//  This program and the accompanying materials are made available under the
//  terms of the Eclipse Public License v. 2.0 which is available at
//  https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
//  which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
//  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//  ========================================================================
//

package org.eclipse.jetty.ee9.ant;

import java.io.File;
import java.util.List;

import org.eclipse.jetty.ee9.webapp.Configuration;
import org.eclipse.jetty.ee9.webapp.WebXmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This configuration object provides additional way to inject application
 * properties into the configured web application. The list of classpath files,
 * the application base directory and web.xml file could be specified in this
 * way.
 */
public class AntWebXmlConfiguration extends WebXmlConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(WebXmlConfiguration.class);

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

    @Override
    public Class<? extends Configuration> replaces()
    {
        return WebXmlConfiguration.class;
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
