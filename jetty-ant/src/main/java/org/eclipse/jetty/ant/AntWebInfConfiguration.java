//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.tools.ant.AntClassLoader;
import org.eclipse.jetty.util.PatternMatcher;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;

public class AntWebInfConfiguration extends WebInfConfiguration
{

    
    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {        
        //Make a temp directory for the webapp if one is not already set
        resolveTempDirectory(context);
        
        //Extract webapp if necessary
        unpack (context);

        
        //Apply an initial ordering to the jars which governs which will be scanned for META-INF
        //info and annotations. The ordering is based on inclusion patterns.       
        String tmp = (String)context.getAttribute(WEBINF_JAR_PATTERN);
        Pattern webInfPattern = (tmp==null?null:Pattern.compile(tmp));
        tmp = (String)context.getAttribute(CONTAINER_JAR_PATTERN);
        Pattern containerPattern = (tmp==null?null:Pattern.compile(tmp));

        //Apply ordering to container jars - if no pattern is specified, we won't
        //match any of the container jars
        PatternMatcher containerJarNameMatcher = new PatternMatcher ()
        {
            public void matched(URI uri) throws Exception
            {
                context.getMetaData().addContainerResource(Resource.newResource(uri));
            }      
        };
        ClassLoader loader = context.getClassLoader();
        if (loader != null)
        {
            loader = loader.getParent();
            if (loader != null)
            {
                URI[] containerUris = null; 
           
                if (loader instanceof URLClassLoader)
                {
                    URL[] urls = ((URLClassLoader)loader).getURLs();
                    if (urls != null)
                    {
                        containerUris = new URI[urls.length];
                        int i=0;
                        for (URL u : urls)
                        {
                            try 
                            {
                                containerUris[i] = u.toURI();
                            }
                            catch (URISyntaxException e)
                            {
                                containerUris[i] = new URI(u.toString().replaceAll(" ", "%20"));
                            }  
                            i++;
                        }
                    }
                }
                else if (loader instanceof AntClassLoader)
                {
                    AntClassLoader antLoader = (AntClassLoader)loader;     
                    String[] paths = antLoader.getClasspath().split(new String(new char[]{File.pathSeparatorChar}));
                    if (paths != null)
                    {
                        containerUris = new URI[paths.length];
                        int i=0;
                        for (String p:paths)
                        {
                            File f = new File(p);
                            containerUris[i] = f.toURI();
                            i++;
                        }
                    }
                }

                containerJarNameMatcher.match(containerPattern, containerUris, false);
            }
        }
        
        //Apply ordering to WEB-INF/lib jars
        PatternMatcher webInfJarNameMatcher = new PatternMatcher ()
        {
            @Override
            public void matched(URI uri) throws Exception
            {
                context.getMetaData().addWebInfJar(Resource.newResource(uri));
            }      
        };
        List<Resource> jars = findJars(context);
       
        //Convert to uris for matching
        URI[] uris = null;
        if (jars != null)
        {
            uris = new URI[jars.size()];
            int i=0;
            for (Resource r: jars)
            {
                uris[i++] = r.getURI();
            }
        }
        webInfJarNameMatcher.match(webInfPattern, uris, true); //null is inclusive, no pattern == all jars match 
        
        //No pattern to appy to classes, just add to metadata
        context.getMetaData().setWebInfClassesDirs(findClassDirs(context));
    }
    

    /**
     * Adds classpath files into web application classloader, and
     * sets web.xml and base directory for the configured web application.
     *
     * @see WebXmlConfiguration#configure(WebAppContext)
     */
    public void configure(WebAppContext context) throws Exception
    {
        if (context instanceof AntWebAppContext)
        {
            List<File> classPathFiles = ((AntWebAppContext)context).getClassPathFiles();
            if (classPathFiles != null)
            {
                for (File cpFile:classPathFiles)
                {
                    if (cpFile.exists())
                    {
                        ((WebAppClassLoader) context.getClassLoader()).addClassPath(cpFile.getCanonicalPath());
                    }
                }
            }
        }
        super.configure(context);
    }
}
