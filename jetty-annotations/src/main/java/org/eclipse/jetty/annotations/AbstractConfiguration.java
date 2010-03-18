// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.annotations;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletContext;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlProcessor;
import org.eclipse.jetty.webapp.Descriptor;
import org.eclipse.jetty.webapp.Fragment;
import org.eclipse.jetty.webapp.Descriptor.MetaDataComplete;


public abstract class AbstractConfiguration implements Configuration
{
    public static final String CONTAINER_JAR_RESOURCES = WebInfConfiguration.CONTAINER_JAR_RESOURCES;
    public static final String WEB_INF_JAR_RESOURCES = WebInfConfiguration.WEB_INF_JAR_RESOURCES;
    public static final String METADATA_COMPLETE = WebXmlProcessor.METADATA_COMPLETE;
    public static final String WEBXML_CLASSNAMES = WebXmlProcessor.WEBXML_CLASSNAMES;

    public void parseContainerPath (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {
        //if no pattern for the container path is defined, then by default scan NOTHING
        Log.debug("Scanning container jars");
           
        //Convert from Resource to URI
        ArrayList<URI> containerUris = new ArrayList<URI>();
        List<Resource> jarResources = (List<Resource>)context.getAttribute(CONTAINER_JAR_RESOURCES);
        for (Resource r : jarResources)
        {
            URI uri = r.getURI();
                containerUris.add(uri);          
        }
        
        parser.parse (containerUris.toArray(new URI[containerUris.size()]),
                new ClassNameResolver ()
                {
                    public boolean isExcluded (String name)
                    {
                        if (context.isSystemClass(name)) return false;
                        if (context.isServerClass(name)) return true;
                        return false;
                    }

                    public boolean shouldOverride (String name)
                    { 
                        //looking at system classpath
                        if (context.isParentLoaderPriority())
                            return true;
                        return false;
                    }
                });
    }
    
    
    public void parseWebInfLib (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {  
        WebXmlProcessor webXmlProcessor = (WebXmlProcessor)context.getAttribute(WebXmlProcessor.WEB_PROCESSOR); 
        if (webXmlProcessor == null)
           throw new IllegalStateException ("No processor for web xml");
        
        List<Fragment> frags = webXmlProcessor.getFragments();
        
        // + get all WEB-INF/lib jars
        // + those that are not in ORDERED_LIBS are ignored (they are excluded by ordering)
        // + those that have web-fragment.xml and metadata-complete are ignored
        
        ArrayList<URI> webInfUris = new ArrayList<URI>();
        List<Resource> jarResources = (List<Resource>)context.getAttribute(WEB_INF_JAR_RESOURCES);
        List<String> orderedJars = (List<String>)context.getAttribute(ServletContext.ORDERED_LIBS);
        for (Resource r : jarResources)
        {          
            URI uri  = r.getURI();
            Fragment f = getFragmentFromJar(r, frags);
           
            //check if the jar has a web-fragment.xml
            if (f == null) //no web-fragment.xml, so scan it 
                webInfUris.add(uri);
            else
            {
                //check if web-fragment is metadata-complete and if it has been excluded from ordering
                if (!isMetaDataComplete(f) && !isExcluded(r, orderedJars))
                    webInfUris.add(uri);
            }
        }
 
       parser.parse(webInfUris.toArray(new URI[webInfUris.size()]), 
                new ClassNameResolver()
                {
                    public boolean isExcluded (String name)
                    {    
                        if (context.isSystemClass(name)) return true;
                        if (context.isServerClass(name)) return false;
                        return false;
                    }

                    public boolean shouldOverride (String name)
                    {
                        //looking at webapp classpath, found already-parsed class of same name - did it come from system or duplicate in webapp?
                        if (context.isParentLoaderPriority())
                            return false;
                        return true;
                    }
                });  
    }
     
    public void parseWebInfClasses (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {
        Log.debug("Scanning classes in WEB-INF/classes");
        parser.parse(context.getWebInf().addPath("classes/"), 
                    new ClassNameResolver()
        {
            public boolean isExcluded (String name)
            {
                if (context.isSystemClass(name)) return true;
                if (context.isServerClass(name)) return false;
                return false;
            }

            public boolean shouldOverride (String name)
            {
                //looking at webapp classpath, found already-parsed class of same name - did it come from system or duplicate in webapp?
                if (context.isParentLoaderPriority())
                    return false;
                return true;
            }
        });
    }
    
    public void parse25Classes (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {
        //only parse servlets, filters and listeners from web.xml
        if (Log.isDebugEnabled()) Log.debug("Scanning only classes from web.xml");
        ArrayList<String> classNames = (ArrayList<String>)context.getAttribute(WEBXML_CLASSNAMES);
        for (String s : classNames)
        {
            Class clazz = Loader.loadClass(null, s);
            parser.parse(clazz,  new ClassNameResolver()
            {
                public boolean isExcluded (String name)
                {
                    if (context.isSystemClass(name)) return true;
                    if (context.isServerClass(name)) return false;
                    return false;
                }

                public boolean shouldOverride (String name)
                {
                    //looking at webapp classpath, found already-parsed class of same name - did it come from system or duplicate in webapp?
                    if (context.isParentLoaderPriority())
                        return false;
                    return true;
                }
            }, true);
        }
        
    }
    
    public Fragment getFragmentFromJar (Resource jar,  List<Fragment> frags)
    throws Exception
    {
        //check if the jar has a web-fragment.xml
        Fragment d = null;
        for (Fragment frag: frags)
        {
            Resource fragResource = frag.getResource(); //eg jar:file:///a/b/c/foo.jar!/META-INF/web-fragment.xml
            if (Resource.isContainedIn(fragResource,jar))
            {
                d = frag;
                break;
            }
        }
        return d;
    }
    
    
    public boolean isMetaDataComplete (Descriptor d)
    {
        return (d!=null && d.getMetaDataComplete() == MetaDataComplete.True);
    }
    
    public boolean isExcluded (Resource jar, List<String> orderedJars)
    {       
        if (jar == null)
            return false;
        
        //no ordering, jar cannot be excluded
        if (orderedJars == null)
            return false;
        
        //ordering that excludes all jars
        if (orderedJars.isEmpty())
            return true;
        
        //ordering applied, check jar is in it
        String fullname = jar.getName();
        int i = fullname.indexOf(".jar");          
        int j = fullname.lastIndexOf("/", i);
        String name = fullname.substring(j+1,i+4);
        if (orderedJars.contains(name))
            return false;
  
        return true;
    }
}
