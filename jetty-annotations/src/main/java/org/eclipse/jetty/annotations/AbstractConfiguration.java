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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.Descriptor;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.FragmentDescriptor;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.Descriptor.MetaDataComplete;


public abstract class AbstractConfiguration implements Configuration
{
    public static final String CONTAINER_JAR_RESOURCES = WebInfConfiguration.CONTAINER_JAR_RESOURCES;
    public static final String WEB_INF_JAR_RESOURCES = WebInfConfiguration.WEB_INF_JAR_RESOURCES;
    public static final String WEB_INF_ORDERED_JAR_RESOURCES = WebInfConfiguration.WEB_INF_ORDERED_JAR_RESOURCES;
    public static final String METADATA_COMPLETE = MetaData.METADATA_COMPLETE;
    public static final String WEBXML_CLASSNAMES = MetaData.WEBXML_CLASSNAMES;
    public static final String DISCOVERED_ANNOTATIONS = "org.eclipse.jetty.discoveredAnnotations";
    
    public void parseContainerPath (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {
        //if no pattern for the container path is defined, then by default scan NOTHING
        Log.debug("Scanning container jars");
        List<DiscoveredAnnotation> discoveredAnnotations = new ArrayList<DiscoveredAnnotation>();
        context.setAttribute(DISCOVERED_ANNOTATIONS, discoveredAnnotations);
        
       
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
        MetaData metaData = (MetaData)context.getAttribute(MetaData.METADATA);        
        if (metaData == null)
            throw new IllegalStateException ("No metadata");
        
        metaData.addDiscoveredAnnotations((List<DiscoveredAnnotation>)context.getAttribute(DISCOVERED_ANNOTATIONS));    
        context.removeAttribute(DISCOVERED_ANNOTATIONS);
    }
    
    
    public void parseWebInfLib (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {  
        MetaData metaData = (MetaData)context.getAttribute(MetaData.METADATA); 
        if (metaData == null)
           throw new IllegalStateException ("No metadata");
        
        List<FragmentDescriptor> frags = metaData.getFragments();
        
        //email from Rajiv Mordani jsrs 315 7 April 2010
        //jars that do not have a web-fragment.xml are still considered fragments
        //they have to participate in the ordering
        ArrayList<URI> webInfUris = new ArrayList<URI>();
        
        List<Resource> jars = (List<Resource>)context.getAttribute(WEB_INF_ORDERED_JAR_RESOURCES);
        
        //No ordering just use the jars in any order
        if (jars == null || jars.isEmpty())
            jars = (List<Resource>)context.getAttribute(WEB_INF_JAR_RESOURCES);
        
        List<DiscoveredAnnotation> discoveredAnnotations = new ArrayList<DiscoveredAnnotation>();
        context.setAttribute(DISCOVERED_ANNOTATIONS, discoveredAnnotations);
        
        for (Resource r : jars)
        {          
            discoveredAnnotations.clear(); //start fresh for each jar
            URI uri  = r.getURI();
            FragmentDescriptor f = getFragmentFromJar(r, frags);
           
            //if a jar has no web-fragment.xml we scan it (because it is not exluded by the ordering)
            //or if it has a fragment we scan it if it is not metadata complete
            if (f == null || !isMetaDataComplete(f))
            {
                parser.parse(uri, 
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
                
                metaData.addDiscoveredAnnotations(r, discoveredAnnotations);
            }
        }
        context.removeAttribute(DISCOVERED_ANNOTATIONS);
    }
     
    public void parseWebInfClasses (final WebAppContext context, final AnnotationParser parser)
    throws Exception
    {
        Log.debug("Scanning classes in WEB-INF/classes");
        if (context.getWebInf() != null)
        {
            Resource classesDir = context.getWebInf().addPath("classes/");
            if (classesDir.exists())
            {
                MetaData metaData = (MetaData)context.getAttribute(MetaData.METADATA); 
                if (metaData == null)
                   throw new IllegalStateException ("No metadata");
                
                List<DiscoveredAnnotation> discoveredAnnotations = new ArrayList<DiscoveredAnnotation>();
                context.setAttribute(DISCOVERED_ANNOTATIONS, discoveredAnnotations);
                
                parser.parse(classesDir, 
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
                
                //TODO - where to set the annotations discovered from WEB-INF/classes?
                metaData.addDiscoveredAnnotations (discoveredAnnotations);
                context.removeAttribute(DISCOVERED_ANNOTATIONS);
            }
        }
    }
    
 
    
    public FragmentDescriptor getFragmentFromJar (Resource jar,  List<FragmentDescriptor> frags)
    throws Exception
    {
        //check if the jar has a web-fragment.xml
        FragmentDescriptor d = null;
        for (FragmentDescriptor frag: frags)
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
}
