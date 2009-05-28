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


import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.regex.Pattern;

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
    public static final String JAR_RESOURCES = WebInfConfiguration.JAR_RESOURCES;

    public void preConfigure(final WebAppContext context) throws Exception
    {
       
      
        JarScanner scanner = new JarScanner()
        {
            public void processEntry(URI jarUri, JarEntry entry)
            {
                try
                {
                    MetaInfConfiguration.this.processEntry(context,jarUri,entry);
                }
                catch (Exception e)
                {
                    Log.warn("Problem processing jar entry " + entry, e);
                }
            }
        };
        
        List<Resource> jarResources = (List<Resource>)context.getAttribute(JAR_RESOURCES);
        
        //Scan jars for META-INF information
        if (jarResources != null)
        {
            URI[] uris = new URI[jarResources.size()];
            int i=0;
            for (Resource r : jarResources)
            {
                uris[i++] = r.getURI();
            }
            scanner.scan(null, uris, true);
        }
    }
    
    
    public void configure(WebAppContext context) throws Exception
    {
        
    }

    public void deconfigure(WebAppContext context) throws Exception
    {
        
    }

    public void postConfigure(WebAppContext context) throws Exception
    {
        
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
    
    
    protected void processEntry(WebAppContext context, URI jarUri, JarEntry entry)
    {
        String name = entry.getName();

        if (!name.startsWith("META-INF/"))
            return;
        
        try
        {
            if (name.equals("META-INF/web-fragment.xml") && context.isConfigurationDiscovered())
            {
                addResource(context,METAINF_FRAGMENTS,Resource.newResource(jarUri));     
            }
            else if (name.equals("META-INF/resources/") && context.isConfigurationDiscovered())
            {
                addResource(context,METAINF_RESOURCES,Resource.newResource("jar:"+jarUri+"!/META-INF/resources"));
            }
            else
            {
                String lcname = name.toLowerCase();
                if (lcname.endsWith(".tld"))
                {
                    addResource(context,METAINF_TLDS,Resource.newResource("jar:"+jarUri+"!/"+name));
                }
            }
        }
        catch(Exception e)
        {
            context.getServletContext().log(jarUri+"!/"+name,e);
        }
    }
}
