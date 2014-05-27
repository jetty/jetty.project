//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.quickstart;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import javax.servlet.ServletContext;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.annotations.ServletContainerInitializersStarter;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.Descriptor;
import org.eclipse.jetty.webapp.IterativeDescriptorProcessor;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlParser;

/**
 * QuickStartDescriptorProcessor
 * 
 * Handle  extended elements for quickstart-web.xml
 */
public class QuickStartDescriptorProcessor extends IterativeDescriptorProcessor
{
    /**
     * 
     */
    public QuickStartDescriptorProcessor()
    {
        try
        {
            registerVisitor("context-param", this.getClass().getMethod("visitContextParam", __signature));
        }    
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @see org.eclipse.jetty.webapp.IterativeDescriptorProcessor#start(org.eclipse.jetty.webapp.WebAppContext, org.eclipse.jetty.webapp.Descriptor)
     */
    @Override
    public void start(WebAppContext context, Descriptor descriptor)
    {
    }

    /**
     * @see org.eclipse.jetty.webapp.IterativeDescriptorProcessor#end(org.eclipse.jetty.webapp.WebAppContext, org.eclipse.jetty.webapp.Descriptor)
     */
    @Override
    public void end(WebAppContext context, Descriptor descriptor)
    { 
    }
    

    /**
     * @param context
     * @param descriptor
     * @param node
     */
    public void visitContextParam (WebAppContext context, Descriptor descriptor, XmlParser.Node node)
            throws Exception
    {
        String name = node.getString("param-name", false, true);
        String value = node.getString("param-value", false, true);
        List<String> values = new ArrayList<>();
        
        // extract values
        switch(name)
        {
            case ServletContext.ORDERED_LIBS:
            case AnnotationConfiguration.CONTAINER_INITIALIZERS:
            case MetaInfConfiguration.METAINF_TLDS:
            case MetaInfConfiguration.METAINF_RESOURCES:

                context.removeAttribute(name);
                
                QuotedStringTokenizer tok = new QuotedStringTokenizer(value,",");
                while(tok.hasMoreElements())
                    values.add(tok.nextToken().trim());
                
                break;
                
            default:
                values.add(value);
        }

        // handle values
        switch(name)
        {
            case ServletContext.ORDERED_LIBS:
            {
                List<Object> libs = new ArrayList<>();
                Object o=context.getAttribute(ServletContext.ORDERED_LIBS);
                if (o instanceof Collection<?>)
                    libs.addAll((Collection<?>)o);
                libs.addAll(values);
                if (libs.size()>0)
                    context.setAttribute(ServletContext.ORDERED_LIBS,libs);
                
                break;
            }
                
            case AnnotationConfiguration.CONTAINER_INITIALIZERS:
            {
                for (String i : values)
                    visitContainerInitializer(context, new ContainerInitializer(Thread.currentThread().getContextClassLoader(), i));
                break;
            }
            
            case MetaInfConfiguration.METAINF_TLDS:
            {
                List<Object> tlds = new ArrayList<>();
                String war=context.getBaseResource().getURI().toString();
                Object o=context.getAttribute(MetaInfConfiguration.METAINF_TLDS);
                if (o instanceof Collection<?>)
                    tlds.addAll((Collection<?>)o);
                for (String i : values)
                {
                    Resource r = Resource.newResource(i.replace("${WAR}/",war));
                    if (r.exists())
                        tlds.add(r.getURL());
                    else
                        throw new IllegalArgumentException("TLD not found: "+r);                    
                }
                
                if (tlds.size()>0)
                    context.setAttribute(MetaInfConfiguration.METAINF_TLDS,tlds);
                break;
            }
            
            case MetaInfConfiguration.METAINF_RESOURCES:
            {
                String war=context.getBaseResource().getURI().toString();
                for (String i : values)
                {
                    Resource r = Resource.newResource(i.replace("${WAR}/",war));
                    if (r.exists())
                        visitMetaInfResource(context,r); 
                    else
                        throw new IllegalArgumentException("Resource not found: "+r);                    
                }
                break;
            }
                
            default:
                
        }
    }
    

    public void visitContainerInitializer (WebAppContext context, ContainerInitializer containerInitializer)
    {
        if (containerInitializer == null)
            return;
        
        //add the ContainerInitializer to the list of container initializers
        List<ContainerInitializer> containerInitializers = (List<ContainerInitializer>)context.getAttribute(AnnotationConfiguration.CONTAINER_INITIALIZERS);
        if (containerInitializers == null)
        {
            containerInitializers = new ArrayList<ContainerInitializer>();
            context.setAttribute(AnnotationConfiguration.CONTAINER_INITIALIZERS, containerInitializers);
        }
        
        containerInitializers.add(containerInitializer);

        //Ensure a bean is set up on the context that will invoke the ContainerInitializers as the context starts
        ServletContainerInitializersStarter starter = (ServletContainerInitializersStarter)context.getAttribute(AnnotationConfiguration.CONTAINER_INITIALIZER_STARTER);
        if (starter == null)
        {
            starter = new ServletContainerInitializersStarter(context);
            context.setAttribute(AnnotationConfiguration.CONTAINER_INITIALIZER_STARTER, starter);
            context.addBean(starter, true);
        }
    }
    
    
    public void visitMetaInfResource (WebAppContext context, Resource dir)
    {
        Collection<Resource> metaInfResources =  (Collection<Resource>)context.getAttribute(MetaInfConfiguration.METAINF_RESOURCES);
        if (metaInfResources == null)
        {
            metaInfResources = new HashSet<Resource>();
            context.setAttribute(MetaInfConfiguration.METAINF_RESOURCES, metaInfResources);
        }
        metaInfResources.add(dir);
        //also add to base resource of webapp
        Resource[] collection=new Resource[metaInfResources.size()+1];
        int i=0;
        collection[i++]=context.getBaseResource();
        for (Resource resource : metaInfResources)
            collection[i++]=resource;
        context.setBaseResource(new ResourceCollection(collection));
    }
}
