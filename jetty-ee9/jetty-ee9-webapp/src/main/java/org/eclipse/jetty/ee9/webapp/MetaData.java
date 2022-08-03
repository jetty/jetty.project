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

package org.eclipse.jetty.ee9.webapp;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MetaData
 *
 * All data associated with the configuration and deployment of a web application.
 */
public class MetaData
{
    private static final Logger LOG = LoggerFactory.getLogger(MetaData.class);

    public static final String VALIDATE_XML = "org.eclipse.jetty.ee9.webapp.validateXml";
    public static final String ORDERED_LIBS = "jakarta.servlet.context.orderedLibs";

    private final AutoLock _lock = new AutoLock();
    protected Map<String, OriginInfo> _origins = new HashMap<>();
    protected WebDescriptor _webDefaultsRoot;
    protected WebDescriptor _webXmlRoot;
    protected final List<WebDescriptor> _webOverrideRoots = new ArrayList<>();
    protected boolean _metaDataComplete;
    protected final List<DescriptorProcessor> _descriptorProcessors = new ArrayList<>();
    protected final List<FragmentDescriptor> _webFragmentRoots = new ArrayList<>();
    protected final Map<String, FragmentDescriptor> _webFragmentNameMap = new HashMap<>();
    protected final Map<Resource, FragmentDescriptor> _webFragmentResourceMap = new HashMap<>();
    protected final Map<Resource, List<DiscoveredAnnotation>> _annotations = new HashMap<>();
    protected final List<Resource> _webInfClasses = new ArrayList<>();
    protected final List<Resource> _webInfJars = new ArrayList<>();
    protected final List<Resource> _orderedContainerResources = new ArrayList<>();
    protected final List<Resource> _orderedWebInfResources = new ArrayList<>();
    protected Ordering _ordering; //can be set to RelativeOrdering by web-default.xml, web.xml, web-override.xml
    protected boolean _allowDuplicateFragmentNames = false;
    protected boolean _validateXml = false;

    public enum Complete
    {
        NotSet, True, False
    }

    /**
     * Metadata regarding where a deployable element was declared:
     * by annotation or by descriptor.
     */
    public static class OriginInfo
    {
        /**
         * Identifier for the deployable element
         */
        private final String name;

        /**
         * Origin of the deployable element
         */
        private final Origin origin;

        /**
         * Reference to the descriptor, if declared in one
         */
        private final Descriptor descriptor;

        /**
         * Reference to the annotation, if declared by one
         */
        private final Annotation annotation;

        /**
         * The class containing the annotation, if declared by one
         */
        private final Class<?> annotated;

        public OriginInfo(String n, Annotation a, Class<?> ac)
        {
            if (Objects.isNull(n))
                throw new IllegalArgumentException("No name");
            name = n;
            origin = Origin.of(a);
            descriptor = null;
            annotation = a;
            annotated = ac;
        }

        public OriginInfo(String n, Descriptor d)
        {
            if (Objects.isNull(n))
                throw new IllegalArgumentException("No name");
            if (Objects.isNull(d))
                throw new IllegalArgumentException("No descriptor");
            name = n;
            origin = Origin.of(d);
            descriptor = d;
            annotation = null;
            annotated = null;
        }

        public OriginInfo(String n)
        {
            if (Objects.isNull(n))
                throw new IllegalArgumentException("No name");
            name = n;
            origin = Origin.API;
            annotation = null;
            descriptor = null;
            annotated = null;
        }

        public String getName()
        {
            return name;
        }

        public Origin getOriginType()
        {
            return origin;
        }

        public Descriptor getDescriptor()
        {
            return descriptor;
        }

        @Override
        public String toString()
        {
            if (descriptor != null)
                return descriptor.toString();
            if (annotation != null)
                return "@" + annotation.annotationType().getSimpleName() + "(" + annotated.getName() + ")";
            return origin.toString();
        }
    }

    public MetaData()
    {
    }

    /**
     * Empty ready for reuse
     */
    public void clear()
    {
        _webDefaultsRoot = null;
        _origins.clear();
        _webXmlRoot = null;
        _webOverrideRoots.clear();
        _metaDataComplete = false;
        _annotations.clear();
        _descriptorProcessors.clear();
        _webFragmentRoots.clear();
        _webFragmentNameMap.clear();
        _webFragmentResourceMap.clear();
        _annotations.clear();
        _webInfJars.clear();
        _orderedWebInfResources.clear();
        _orderedContainerResources.clear();
        _ordering = null;
        _allowDuplicateFragmentNames = false;
    }

    /**
     * Set the web-default.xml.
     *
     * @param descriptor the web-default.xml
     */
    public void setDefaultsDescriptor(DefaultsDescriptor descriptor)
        throws Exception
    {
        _webDefaultsRoot = descriptor;
        _webDefaultsRoot.parse(WebDescriptor.getParser(isValidateXml()));
        if (_webDefaultsRoot.isOrdered())
        {
            Ordering ordering = getOrdering();
            if (ordering == null)
                ordering = new AbsoluteOrdering(this);

            List<String> order = _webDefaultsRoot.getOrdering();
            for (String s : order)
            {
                if (s.equalsIgnoreCase("others"))
                    ((AbsoluteOrdering)ordering).addOthers();
                else
                    ((AbsoluteOrdering)ordering).add(s);
            }

            //(re)set the ordering to cause webinf jar order to be recalculated
            setOrdering(ordering);
        }
    }

    /**
     * @param descriptor the web.xml descriptor
     */
    public void setWebDescriptor(WebDescriptor descriptor)
        throws Exception
    {
        _webXmlRoot = descriptor;
        _webXmlRoot.parse(WebDescriptor.getParser(isValidateXml()));
        _metaDataComplete = WebDescriptor.isMetaDataComplete(_webXmlRoot);

        if (_webXmlRoot.isOrdered())
        {
            Ordering ordering = getOrdering();
            if (ordering == null)
                ordering = new AbsoluteOrdering(this);

            List<String> order = _webXmlRoot.getOrdering();
            for (String s : order)
            {
                if (s.equalsIgnoreCase("others"))
                    ((AbsoluteOrdering)ordering).addOthers();
                else
                    ((AbsoluteOrdering)ordering).add(s);
            }

            //(re)set the ordering to cause webinf jar order to be recalculated
            setOrdering(ordering);
        }
    }

    /**
     * Add a override-web.xml descriptor.
     *
     * @param descriptor the override-web.xml
     */
    public void addOverrideDescriptor(OverrideDescriptor descriptor)
        throws Exception
    {
        descriptor.parse(WebDescriptor.getParser(isValidateXml()));

        switch (descriptor.getMetaDataComplete())
        {
            case True:
                _metaDataComplete = true;
                break;
            case False:
                _metaDataComplete = false;
                break;
            default:
                break;
        }

        if (descriptor.isOrdered())
        {
            Ordering ordering = getOrdering();

            if (ordering == null)
                ordering = new AbsoluteOrdering(this);

            List<String> order = descriptor.getOrdering();
            for (String s : order)
            {
                if (s.equalsIgnoreCase("others"))
                    ((AbsoluteOrdering)ordering).addOthers();
                else
                    ((AbsoluteOrdering)ordering).add(s);
            }

            //set or reset the ordering to cause the webinf jar ordering to be recomputed
            setOrdering(ordering);
        }
        _webOverrideRoots.add(descriptor);
    }

    /**
     * Add a web-fragment.xml, and the jar it is contained in.
     *
     * @param jarResource the jar of the fragment
     * @param descriptor web-fragment.xml
     * @throws Exception if unable to add fragment
     */
    public void addFragmentDescriptor(Resource jarResource, FragmentDescriptor descriptor)
        throws Exception
    {
        if (_metaDataComplete)
            return; //do not process anything else if web.xml/web-override.xml set metadata-complete

        Objects.requireNonNull(jarResource);
        Objects.requireNonNull(descriptor);

        //Metadata-complete is not set, or there is no web.xml
        _webFragmentResourceMap.put(jarResource, descriptor);
        _webFragmentRoots.add(descriptor);
        descriptor.parse(WebDescriptor.getParser(isValidateXml()));

        if (descriptor.getName() != null)
        {
            Descriptor existing = _webFragmentNameMap.get(descriptor.getName());
            if (existing != null && !isAllowDuplicateFragmentNames())
            {
                throw new IllegalStateException("Duplicate fragment name: " + descriptor.getName() + " for " + existing.getResource() + " and " + descriptor.getResource());
            }
            else
                _webFragmentNameMap.put(descriptor.getName(), descriptor);
        }

        //only accept an ordering from the fragment if there is no ordering already established
        if (_ordering == null && descriptor.isOrdered())
        {
            setOrdering(new RelativeOrdering(this));
            return;
        }

        //recompute the ordering with the new fragment name
        orderFragments();
    }

    /**
     * Annotations such as WebServlet, WebFilter, WebListener that
     * can be discovered by scanning unloaded classes.
     *
     * @param annotations the list of discovered annotations to add
     */
    public void addDiscoveredAnnotations(List<DiscoveredAnnotation> annotations)
    {
        if (annotations == null)
            return;
        for (DiscoveredAnnotation a : annotations)
        {
            addDiscoveredAnnotation(a);
        }
    }

    /**
     * Add an annotation that has been discovered on a class, method or field within a resource
     * eg a jar or dir. The annotation may also have no associated resource, or that resource
     * may be a system or container resource.
     *
     * This method is synchronized as it is anticipated that it may be called by many threads
     * during the annotation scanning phase.
     *
     * @param annotation the discovered annotation
     */
    public void addDiscoveredAnnotation(DiscoveredAnnotation annotation)
    {
        if (annotation == null)
            return;

        try (AutoLock l = _lock.lock())
        {
            //if no resource associated with an annotation map it to empty resource - these
            //annotations will always be processed first
            Resource enclosingResource = null;
            Resource resource = annotation.getResource();
            if (resource != null)
            {
                //check if any of the web-inf classes dirs is a parent
                enclosingResource = getEnclosingResource(_webInfClasses, resource);

                //check if any of the web-inf jars is a parent
                if (enclosingResource == null)
                    enclosingResource = getEnclosingResource(_webInfJars, resource);

                //check if any of the container resources is a parent
                if (enclosingResource == null)
                    enclosingResource = getEnclosingResource(_orderedContainerResources, resource);

                //Couldn't find a parent resource in any of the known resources, map it to null
            }

            List<DiscoveredAnnotation> list = _annotations.computeIfAbsent(enclosingResource, k -> new ArrayList<>());
            list.add(annotation);
        }
    }

    /**
     * Check if the resource is contained within one of the list of resources.
     * In other words, check if the given resource is a sub-resource of one
     * of the list of resources.
     *
     * @param resources the list of resources to check against
     * @param resource the resource for which to find the parent resource
     * @return the resource from the list that contains the given resource.
     */
    private Resource getEnclosingResource(List<Resource> resources, Resource resource)
    {
        Resource enclosingResource = null;
        try
        {
            for (Resource r : resources)
            {
                if (resource.isContainedIn(r))
                {
                    enclosingResource = r;
                    break;
                }
            }
            return enclosingResource;
        }
        catch (Exception e)
        {
            LOG.warn("Not contained within?", e);
            return null;
        }
    }

    public void addDescriptorProcessor(DescriptorProcessor p)
    {
        _descriptorProcessors.add(p);
    }

    public void removeDescriptorProcessor(DescriptorProcessor p)
    {
        _descriptorProcessors.remove(p);
    }

    public void orderFragments()
    {
        _orderedWebInfResources.clear();
        if (getOrdering() != null)
            _orderedWebInfResources.addAll(getOrdering().order(_webInfJars));
    }

    /**
     * Resolve all servlet/filter/listener metadata from all sources: descriptors and annotations.
     *
     * @param context the context to resolve servlets / filters / listeners metadata from
     * @throws Exception if unable to resolve metadata
     */
    public void resolve(WebAppContext context)
        throws Exception
    {
        LOG.debug("metadata resolve {}", context);

        // Ensure origins is fresh
        _origins.clear();

        // Set the ordered lib attribute
        List<Resource> orderedWebInfJars = null;
        if (isOrdered())
        {
            orderedWebInfJars = getWebInfResources(true);
            List<String> orderedLibs = new ArrayList<>();
            for (Resource jar: orderedWebInfJars)
            {
                URI uri = URIUtil.unwrapContainer(jar.getURI());
                orderedLibs.add(uri.getPath());
            }
            context.setAttribute(ServletContext.ORDERED_LIBS, Collections.unmodifiableList(orderedLibs));
        }

        // set the webxml version
        if (_webXmlRoot != null)
        {
            context.getServletContext().setEffectiveMajorVersion(_webXmlRoot.getMajorVersion());
            context.getServletContext().setEffectiveMinorVersion(_webXmlRoot.getMinorVersion());
        }

        //process web-defaults.xml, web.xml and override-web.xmls
        for (DescriptorProcessor p : _descriptorProcessors)
        {
            p.process(context, getDefaultsDescriptor());
            p.process(context, getWebDescriptor());
            for (WebDescriptor wd : getOverrideDescriptors())
            {
                LOG.debug("process {} {} {}", context, p, wd);
                p.process(context, wd);
            }
        }

        List<Resource> resources = new ArrayList<>();
        resources.add(null); //always apply annotations with no resource first
        resources.addAll(_orderedContainerResources); //next all annotations from container path
        resources.addAll(_webInfClasses); //next everything from web-inf classes
        resources.addAll(getWebInfResources(isOrdered())); //finally annotations (in order) from webinf path 

        for (Resource r : resources)
        {
            //Process the web-fragment.xml before applying annotations from a fragment.
            //Note that some fragments, or resources that aren't fragments won't have
            //a descriptor.
            FragmentDescriptor fd = _webFragmentResourceMap.get(r);
            if (fd != null)
            {
                for (DescriptorProcessor p : _descriptorProcessors)
                {
                    LOG.debug("process {} {}", context, fd);
                    p.process(context, fd);
                }
            }

            //Then apply the annotations - note that if metadata is complete
            //either overall or for a fragment, those annotations won't have
            //been discovered.
            List<DiscoveredAnnotation> annotations = _annotations.get(r);
            if (annotations != null)
            {
                for (DiscoveredAnnotation a : annotations)
                {
                    LOG.debug("apply {}", a);
                    a.apply();
                }
            }
        }
    }

    /**
     * A webapp is distributable if web.xml is metadata-complete and
     * distributable=true, or if metadata-complete is false, but all
     * web-fragments.xml are distributable=true.
     *
     * @return true if the webapp is distributable, false otherwise
     */
    public boolean isDistributable()
    {
        boolean distributable = (
            (_webDefaultsRoot != null && _webDefaultsRoot.isDistributable()) ||
                (_webXmlRoot != null && _webXmlRoot.isDistributable()));

        for (WebDescriptor d : _webOverrideRoots)
        {
            distributable &= d.isDistributable();
        }

        if (isOrdered())
        {
            List<Resource> orderedResources = getWebInfResources(true);
            for (Resource r : orderedResources)
            {
                FragmentDescriptor d = _webFragmentResourceMap.get(r);
                if (d != null)
                    distributable = distributable && d.isDistributable();
            }
        }
        return distributable;
    }

    public WebDescriptor getWebDescriptor()
    {
        return _webXmlRoot;
    }

    public List<WebDescriptor> getOverrideDescriptors()
    {
        return _webOverrideRoots;
    }

    public WebDescriptor getDefaultsDescriptor()
    {
        return _webDefaultsRoot;
    }

    public boolean isOrdered()
    {
        return getOrdering() != null;
    }

    public Ordering getOrdering()
    {
        return _ordering;
    }

    public void setOrdering(Ordering o)
    {
        _ordering = o;
        orderFragments();
    }

    /**
     * @param name the name specified in a web-fragment.xml
     * @return the web-fragment.xml that defines that name or null
     */
    public FragmentDescriptor getFragmentDescriptor(String name)
    {
        return _webFragmentNameMap.get(name);
    }

    /**
     * @param descriptorResource the web-fragment.xml location as a Resource
     * @return the FrgmentDescriptor for the web-fragment.xml, or null if none exists
     */
    public FragmentDescriptor getFragmentDescriptor(Resource descriptorResource)
    {
        return _webFragmentRoots.stream().filter(d -> d.getResource().equals(descriptorResource)).findFirst().orElse(null);
    }

    /**
     * @param name the name specified in a web-fragment.xml
     * @return the jar that contains the web-fragment.xml with the given name or null
     */
    public Resource getJarForFragmentName(String name)
    {
        FragmentDescriptor f = getFragmentDescriptor(name);
        if (f == null)
            return null;

        Resource jar = null;

        for (Map.Entry<Resource, FragmentDescriptor> entry : _webFragmentResourceMap.entrySet())
        {
            if (entry.getValue().equals(f))
                jar = entry.getKey();
        }
        return jar;
    }

    /**
     * Get the web-fragment.xml related to a jar
     *
     * @param jar the jar to check for a mapping to web-fragment.xml
     * @return the FragmentDescriptor or null if no web-fragment.xml is associated with the jar
     */
    public FragmentDescriptor getFragmentDescriptorForJar(Resource jar)
    {
        return _webFragmentResourceMap.get(jar);
    }

    /**
     * @return a map of name to FragmentDescriptor, for those FragmentDescriptors that
     * define a name element.
     */
    public Map<String, FragmentDescriptor> getNamedFragmentDescriptors()
    {
        return Collections.unmodifiableMap(_webFragmentNameMap);
    }

    public Origin getOrigin(String name)
    {
        OriginInfo x = _origins.get(name);
        if (x == null)
            return Origin.NotSet;

        return x.getOriginType();
    }

    public OriginInfo getOriginInfo(String name)
    {
        OriginInfo x = _origins.get(name);
        if (x == null)
            return null;

        return x;
    }

    public Descriptor getOriginDescriptor(String name)
    {
        OriginInfo o = _origins.get(name);
        if (o == null)
            return null;
        return o.getDescriptor();
    }

    public void setOrigin(String name, Descriptor d)
    {
        if (name == null)
            return;

        OriginInfo x = new OriginInfo(name, d);
        _origins.put(name, x);
    }

    public void setOrigin(String name, Annotation annotation, Class<?> annotated)
    {
        if (name == null)
            return;

        OriginInfo x = new OriginInfo(name, annotation, annotated);
        _origins.put(name, x);
    }

    public void setOriginAPI(String name)
    {
        if (name == null)
            return;

        OriginInfo x = new OriginInfo(name);
        _origins.put(name, x);
    }

    public boolean isMetaDataComplete()
    {
        return _metaDataComplete;
    }

    public void addWebInfResource(Resource resource)
    {
        Objects.requireNonNull(resource);
        if (!resource.exists())
            throw new IllegalArgumentException("Resource does not exist: " + resource);
        URI uri = resource.getURI();
        if (FileID.isArchive(uri) && !uri.toASCIIString().startsWith("jar:file:"))
            throw new IllegalArgumentException("Resource is an archive, referenced via raw `file:` scheme, needs to be a mounted `jar:file:` archive: " + resource);

        _webInfJars.add(resource);
    }

    public List<Resource> getWebInfResources(boolean withOrdering)
    {
        if (!withOrdering)
            return Collections.unmodifiableList(_webInfJars);
        else
            return Collections.unmodifiableList(_orderedWebInfResources);
    }

    public List<Resource> getContainerResources()
    {
        return Collections.unmodifiableList(_orderedContainerResources);
    }

    public void addContainerResource(Resource jar)
    {
        _orderedContainerResources.add(jar);
    }

    public void setWebInfClassesResources(List<Resource> dirs)
    {
        _webInfClasses.addAll(dirs);
    }

    public List<Resource> getWebInfClassesResources()
    {
        return Collections.unmodifiableList(_webInfClasses);
    }

    public boolean isAllowDuplicateFragmentNames()
    {
        return _allowDuplicateFragmentNames;
    }

    public void setAllowDuplicateFragmentNames(boolean allowDuplicateFragmentNames)
    {
        _allowDuplicateFragmentNames = allowDuplicateFragmentNames;
    }

    /**
     * @return true if the parser validates, false otherwise
     */
    public boolean isValidateXml()
    {
        return _validateXml;
    }

    /**
     * @param validateXml if true xml syntax is validated by the parser, false otherwise
     */
    public void setValidateXml(boolean validateXml)
    {
        _validateXml = validateXml;
    }

    public Map<String, OriginInfo> getOrigins()
    {
        return Collections.unmodifiableMap(_origins);
    }
}
