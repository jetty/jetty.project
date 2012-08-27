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
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Iterator;
import java.util.List;

import org.apache.tools.ant.AntClassLoader;
import org.eclipse.jetty.ant.utils.TaskLog;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Descriptor;
import org.eclipse.jetty.webapp.StandardDescriptorProcessor;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlParser.Node;


/**
 * This configuration object provides additional way to inject application
 * properties into the configured web application. The list of classpath files,
 * the application base directory and web.xml file could be specified in this
 * way.
 *
 * @author Jakub Pawlowicz
 * @author Athena Yao
 */
public class AntWebXmlConfiguration extends WebXmlConfiguration
{
    private static final Logger LOG = Log.getLogger(WebXmlConfiguration.class);

    
    
    /** List of classpath files. */
    private List classPathFiles;

    /** Web application root directory. */
    private File webAppBaseDir;

    /** Web application web.xml file. */
    private File webXmlFile;

    private File webDefaultXmlFile;

    public AntWebXmlConfiguration() throws ClassNotFoundException
    {
    }

    public File getWebDefaultXmlFile()
    {
        return this.webDefaultXmlFile;
    }

    public void setWebDefaultXmlFile(File webDefaultXmlfile)
    {
        this.webDefaultXmlFile = webDefaultXmlfile;
    }

    public void setClassPathFiles(List classPathFiles)
    {
        this.classPathFiles = classPathFiles;
    }

    public void setWebAppBaseDir(File webAppBaseDir)
    {
        this.webAppBaseDir = webAppBaseDir;
    }

    public void setWebXmlFile(File webXmlFile)
    {
        this.webXmlFile = webXmlFile;

        if (webXmlFile.exists())
        {
            TaskLog.log("web.xml file = " + webXmlFile);
        }
    }

    /**
     * Adds classpath files into web application classloader, and
     * sets web.xml and base directory for the configured web application.
     *
     * @see WebXmlConfiguration#configure(WebAppContext)
     */
    public void configure(WebAppContext context) throws Exception
    {
        if (context.isStarted())
        {
            TaskLog.log("Cannot configure webapp after it is started");
            return;
        }
       

        if (webXmlFile.exists())
        {
            context.setDescriptor(webXmlFile.getCanonicalPath());
        }
        
        super.configure(context);

        Iterator filesIterator = classPathFiles.iterator();

        while (filesIterator.hasNext())
        {
            File classPathFile = (File) filesIterator.next();
            if (classPathFile.exists())
            {
                ((WebAppClassLoader) context.getClassLoader())
                        .addClassPath(classPathFile.getCanonicalPath());
            }
        }
    }    
}
