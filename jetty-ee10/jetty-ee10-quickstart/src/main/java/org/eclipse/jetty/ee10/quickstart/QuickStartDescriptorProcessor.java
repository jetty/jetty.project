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

package org.eclipse.jetty.ee10.quickstart;

import java.io.Closeable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.servlet.ServletContainerInitializerHolder;
import org.eclipse.jetty.ee10.servlet.ServletMapping;
import org.eclipse.jetty.ee10.webapp.DefaultsDescriptor;
import org.eclipse.jetty.ee10.webapp.Descriptor;
import org.eclipse.jetty.ee10.webapp.IterativeDescriptorProcessor;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.AttributeNormalizer;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.eclipse.jetty.xml.XmlParser;

/**
 * QuickStartDescriptorProcessor
 *
 * Handle  extended elements for quickstart-web.xml
 */
public class QuickStartDescriptorProcessor extends IterativeDescriptorProcessor implements Closeable
{
    private String _originAttributeName = null;
    // possibly mounted resources that need to be cleaned up eventually
    private ResourceFactory.Closeable _resourceFactory = ResourceFactory.closeable();

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

    @Override
    public void close()
    {
        IO.close(_resourceFactory);
        _resourceFactory = null;
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
            case QuickStartGeneratorConfiguration.ORIGIN ->
            {
                //value already contains what we need
            }
            case ServletContext.ORDERED_LIBS, AnnotationConfiguration.CONTAINER_INITIALIZERS, MetaInfConfiguration.METAINF_TLDS, MetaInfConfiguration.METAINF_RESOURCES ->
            {
                context.removeAttribute(name);

                QuotedStringTokenizer tok = new QuotedStringTokenizer(value, ",");
                while (tok.hasMoreElements())
                {
                    values.add(tok.nextToken().trim());
                }
            }
            default -> values.add(value);
        }

        AttributeNormalizer normalizer = new AttributeNormalizer(context.getBaseResource());
        // handle values
        switch (name)
        {
            case QuickStartGeneratorConfiguration.ORIGIN ->
                context.setAttribute(QuickStartGeneratorConfiguration.ORIGIN, value);

            case ServletContext.ORDERED_LIBS ->
            {
                List<Object> libs = new ArrayList<>();
                Object o = context.getAttribute(ServletContext.ORDERED_LIBS);
                if (o instanceof Collection<?>)
                    libs.addAll((Collection<?>)o);
                libs.addAll(values);
                if (libs.size() > 0)
                    context.setAttribute(ServletContext.ORDERED_LIBS, libs);
            }
            case AnnotationConfiguration.CONTAINER_INITIALIZERS ->
            {
                for (String s : values)
                {
                    visitServletContainerInitializerHolder(context,
                        ServletContainerInitializerHolder.fromString(Thread.currentThread().getContextClassLoader(), s));
                }
            }
            case MetaInfConfiguration.METAINF_TLDS ->
            {
                List<Object> tlds = new ArrayList<>();
                Object o = context.getAttribute(MetaInfConfiguration.METAINF_TLDS);
                if (o instanceof Collection<?>)
                    tlds.addAll((Collection<?>)o);

                for (String i : values)
                {
                    String entry = normalizer.expand(i);
                    tlds.add(URIUtil.toURI(entry).toURL());
                }

                //empty list signals that tlds were prescanned but none found.
                //a missing METAINF_TLDS attribute means that prescanning was not done.
                context.setAttribute(MetaInfConfiguration.METAINF_TLDS, tlds);
            }
            case MetaInfConfiguration.METAINF_RESOURCES ->
            {
                List<URI> uris = values.stream()
                    .map(normalizer::expand)
                    .map(URIUtil::toURI)
                    .toList();

                for (URI uri : uris)
                {
                    Resource r = _resourceFactory.newResource(uri);
                    if (Resources.missing(r))
                        throw new IllegalArgumentException("Resource not found: " + r);
                    visitMetaInfResource(context, r);
                }
            }
            default ->
            {
            }
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
            metaInfResources = new HashSet<>();
            context.setAttribute(MetaInfConfiguration.METAINF_RESOURCES, metaInfResources);
        }
        metaInfResources.add(dir);

        //also add to base resource of webapp
        List<Resource> collection = new ArrayList<>();
        collection.add(context.getBaseResource());
        collection.addAll(metaInfResources);
        context.setBaseResource(ResourceFactory.combine(collection));
    }
}
