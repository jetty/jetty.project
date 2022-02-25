//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quickstart;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.annotations.ServletContainerInitializersStarter;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.servlet.ServletContainerInitializerHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.DefaultsDescriptor;
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

    private String _originAttributeName = null;

    /**
     *
     */
    public QuickStartDescriptorProcessor()
    {
        try
        {
            registerVisitor("context-param", this.getClass().getMethod("visitContextParam", __signature));
            registerVisitor("servlet-mapping", this.getClass().getMethod("visitServletMapping", __signature));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void start(WebAppContext context, Descriptor descriptor)
    {
        _originAttributeName = context.getInitParameter(QuickStartGeneratorConfiguration.ORIGIN);
    }

    @Override
    public void end(WebAppContext context, Descriptor descriptor)
    {
        _originAttributeName = null;
    }

    /**
     * Process a servlet-mapping element
     *
     * @param context the webapp
     * @param descriptor the xml file to process
     * @param node the servlet-mapping element in the xml file to process
     */
    public void visitServletMapping(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String servletName = node.getString("servlet-name", false, true);
        ServletMapping mapping = null;
        ServletMapping[] mappings = context.getServletHandler().getServletMappings();

        if (mappings != null)
        {
            for (ServletMapping m : mappings)
            {
                if (servletName.equals(m.getServletName()))
                {
                    mapping = m;
                    break;
                }
            }
        }

        if (mapping != null && _originAttributeName != null)
        {
            String origin = node.getAttribute(_originAttributeName);
            if (!StringUtil.isBlank(origin) && origin.startsWith(DefaultsDescriptor.class.getSimpleName()))
                mapping.setFromDefaultDescriptor(true);
        }
    }

    /**
     * Process a context-param element
     *
     * @param context the webapp
     * @param descriptor the xml file to process
     * @param node the context-param node in the xml file
     * @throws Exception if some resources cannot be read
     */
    public void visitContextParam(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
        throws Exception
    {
        String name = node.getString("param-name", false, true);
        String value = node.getString("param-value", false, true);
        List<String> values = new ArrayList<>();

        // extract values
        switch (name)
        {
            case QuickStartGeneratorConfiguration.ORIGIN:
            {
                //value already contains what we need
                break;
            }
            case ServletContext.ORDERED_LIBS:
            case AnnotationConfiguration.CONTAINER_INITIALIZERS:
            case MetaInfConfiguration.METAINF_TLDS:
            case MetaInfConfiguration.METAINF_RESOURCES:
            {
                context.removeAttribute(name);

                QuotedStringTokenizer tok = new QuotedStringTokenizer(value, ",");
                while (tok.hasMoreElements())
                {
                    values.add(tok.nextToken().trim());
                }

                break;
            }
            default:
                values.add(value);
        }

        AttributeNormalizer normalizer = new AttributeNormalizer(context.getBaseResource());
        // handle values
        switch (name)
        {
            case QuickStartGeneratorConfiguration.ORIGIN:
            {
                context.setAttribute(QuickStartGeneratorConfiguration.ORIGIN, value);
                break;
            }
            case ServletContext.ORDERED_LIBS:
            {
                List<Object> libs = new ArrayList<>();
                Object o = context.getAttribute(ServletContext.ORDERED_LIBS);
                if (o instanceof Collection<?>)
                    libs.addAll((Collection<?>)o);
                libs.addAll(values);
                if (libs.size() > 0)
                    context.setAttribute(ServletContext.ORDERED_LIBS, libs);

                break;
            }

            case AnnotationConfiguration.CONTAINER_INITIALIZERS:
            {
                for (String s : values)
                {
                    visitServletContainerInitializerHolder(context, 
                        ServletContainerInitializerHolder.fromString(Thread.currentThread().getContextClassLoader(), s));
                }
                break;
            }

            case MetaInfConfiguration.METAINF_TLDS:
            {
                List<Object> tlds = new ArrayList<>();
                Object o = context.getAttribute(MetaInfConfiguration.METAINF_TLDS);
                if (o instanceof Collection<?>)
                    tlds.addAll((Collection<?>)o);
                for (String i : values)
                {
                    Resource r = Resource.newResource(normalizer.expand(i));
                    if (r.exists())
                        tlds.add(r.getURI().toURL());
                    else
                        throw new IllegalArgumentException("TLD not found: " + r);
                }

                //empty list signals that tlds were prescanned but none found.
                //a missing METAINF_TLDS attribute means that prescanning was not done.
                context.setAttribute(MetaInfConfiguration.METAINF_TLDS, tlds);
                break;
            }

            case MetaInfConfiguration.METAINF_RESOURCES:
            {
                for (String i : values)
                {
                    Resource r = Resource.newResource(normalizer.expand(i));
                    if (r.exists())
                        visitMetaInfResource(context, r);
                    else
                        throw new IllegalArgumentException("Resource not found: " + r);
                }
                break;
            }

            default:
        }
    }

    @Deprecated
    public void visitContainerInitializer(WebAppContext context, ContainerInitializer containerInitializer)
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
    
    /**
     * Ensure the ServletContainerInitializerHolder will be started by adding it to the context.
     * 
     * @param context the context to which to add the ServletContainerInitializerHolder
     * @param sciHolder the ServletContainerInitializerHolder
     */
    public void visitServletContainerInitializerHolder(WebAppContext context, ServletContainerInitializerHolder sciHolder)
    {
        if (sciHolder == null)
            return;
        context.addServletContainerInitializer(sciHolder);
    }

    public void visitMetaInfResource(WebAppContext context, Resource dir)
    {
        Collection<Resource> metaInfResources = (Collection<Resource>)context.getAttribute(MetaInfConfiguration.METAINF_RESOURCES);
        if (metaInfResources == null)
        {
            metaInfResources = new HashSet<Resource>();
            context.setAttribute(MetaInfConfiguration.METAINF_RESOURCES, metaInfResources);
        }
        metaInfResources.add(dir);
        //also add to base resource of webapp
        Resource[] collection = new Resource[metaInfResources.size() + 1];
        int i = 0;
        collection[i++] = context.getBaseResource();
        for (Resource resource : metaInfResources)
        {
            collection[i++] = resource;
        }
        context.setBaseResource(new ResourceCollection(collection));
    }
}
