// ========================================================================
// Copyright (c) 2009 Mort Bay Consulting Pty. Ltd.
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


import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;

/**
 * MetaInfConfiguration
 *
 * Scan META-INF of all jars in WEB-INF/lib to find:
 * <ul>
 * <li>tlds
 * <li>web-fragment.xml
 * <li>resources
 * </ul>
 */
public class MetaInfConfiguration implements Configuration
{

    public static final String __tldJars = "org.eclipse.jetty.tlds";
    public static final String __webFragJars = "org.eclipse.jetty.webFragments";
    public static final String __metaResourceJars = "org.eclipse.jetty.metaResources";
    
    

    public void preConfigure(final WebAppContext context) throws Exception
    {
        
        //Find all jars in WEB-INF
        Resource web_inf = context.getWebInf();
        Resource web_inf_lib = web_inf.addPath("/lib");
        List<URL> urls = new ArrayList<URL>();
        
        if (web_inf_lib.exists() && web_inf_lib.isDirectory())
        {
            String[] files=web_inf_lib.list();
            for (int f=0;files!=null && f<files.length;f++)
            {
                try 
                {
                    Resource file = web_inf_lib.addPath(files[f]);
                    String fnlc = file.getName().toLowerCase();
                    int dot = fnlc.lastIndexOf('.');
                    String extension = (dot < 0 ? null : fnlc.substring(dot));
                    if (extension != null && (extension.equals(".jar") || extension.equals(".zip")))
                    {
                        urls.add(file.getURL());
                    }
                }
                catch (Exception ex)
                {
                    Log.warn(Log.EXCEPTION,ex);
                }
            }
        }
        
        final List<URL> tldJars = new ArrayList<URL>();
        final List<URL> webFragJars = new ArrayList<URL>();
        final List<URL> metaResourceJars = new ArrayList<URL>();
        
        JarScanner fragScanner = new JarScanner()
        {
            public void processEntry(URL jarUrl, JarEntry entry)
            {
                try
                {
                    String name = entry.getName().toLowerCase();
                    if (name.startsWith("meta-inf"))
                    {
                        if (name.equals("meta-inf/web-fragment.xml"))
                        {
                            addJar(jarUrl, webFragJars);                       
                        }
                        else if (name.endsWith(".tld"))
                        {
                            addJar(jarUrl, tldJars);
                        }
                        else if (name.equals("meta-inf/resources"))
                        {
                            addJar(jarUrl, metaResourceJars);
                        }
                    }   
                }
                catch (Exception e)
                {
                    Log.warn("Problem processing jar entry " + entry, e);
                }
            }
        };
        fragScanner.scan(null, urls.toArray(new URL[urls.size()]), true);
        
        context.setAttribute(__tldJars, tldJars);
        context.setAttribute(__webFragJars, webFragJars);
        context.setAttribute(__metaResourceJars, metaResourceJars);
    }
    
    
    public void configure(WebAppContext context) throws Exception
    {
        // TODO Auto-generated method stub

    }

    public void deconfigure(WebAppContext context) throws Exception
    {
        // TODO Auto-generated method stub

    }

    public void postConfigure(WebAppContext context) throws Exception
    {
        // TODO Auto-generated method stub

    }

    public void addJar (URL jarUrl, List<URL> list)
    {
        if (!list.contains(jarUrl))
            list.add(jarUrl);
    }

}
