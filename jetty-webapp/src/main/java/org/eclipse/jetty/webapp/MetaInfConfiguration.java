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
    public static final String METAINF_TLDS = TagLibConfiguration.TLD_RESOURCES;
    public static final String METAINF_FRAGMENTS = FragmentConfiguration.FRAGMENT_RESOURCES;
    public static final String METAINF_RESOURCES = WebInfConfiguration.RESOURCE_URLS;

    public void preConfigure(final WebAppContext context) throws Exception
    {
        //Find all jars in WEB-INF
        List<URL> urls = findJars(context);
      
        JarScanner fragScanner = new JarScanner()
        {
            public void processEntry(URL jarUrl, JarEntry entry)
            {
                try
                {
                    MetaInfConfiguration.this.processEntry(context,jarUrl,entry);
                }
                catch (Exception e)
                {
                    Log.warn("Problem processing jar entry " + entry, e);
                }
            }
        };
        fragScanner.scan(null, urls.toArray(new URL[urls.size()]), true);
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

    public void addResource (WebAppContext context, String attribute, Resource jar)
    {
        List<Resource> list = (List<Resource>)context.getAttribute(attribute);
        if (list==null)
        {
            list=new ArrayList<Resource>();
            context.setAttribute(attribute,list);
        }
        if (!list.contains(jar))
            list.add(jar);
    }
    
    
    protected void processEntry(WebAppContext context, URL jarUrl, JarEntry entry)
    {
        String name = entry.getName();
        if (!name.startsWith("META-INF/"))
            return;

        try
        {
            if (name.equals("META-INF/web-fragment.xml") && context.isConfigurationDiscovered())
            {
                addResource(context,METAINF_FRAGMENTS,Resource.newResource(jarUrl));     
            }
            else if (name.equals("META-INF/resources/") && context.isConfigurationDiscovered())
            {
                addResource(context,METAINF_RESOURCES,Resource.newResource("jar:"+jarUrl+"!/META-INF/resources"));
            }
            else
            {
                String lcname = name.toLowerCase();
                if (lcname.endsWith(".tld"))
                {
                    addResource(context,METAINF_TLDS,Resource.newResource("jar:"+jarUrl+"!/"+name));
                }
            }
        }
        catch(Exception e)
        {
            context.getServletContext().log(jarUrl+"!/"+name,e);
        }
    }
    
    /**
     * Look for jars in WEB-INF/lib
     * @param context
     * @return
     * @throws Exception
     */
    protected List<URL> findJars (WebAppContext context) 
    throws Exception
    {
        List<URL> urls = new ArrayList<URL>();
        
        Resource web_inf = context.getWebInf();
        Resource web_inf_lib = web_inf.addPath("/lib");
       
        
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
        return urls;
    }
}
