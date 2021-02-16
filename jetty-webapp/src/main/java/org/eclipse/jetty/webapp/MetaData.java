//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.webapp;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletContext;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.EmptyResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * MetaData
 *
 * All data associated with the configuration and deployment of a web application.
 */
public class MetaData
{
    private static final Logger LOG = Log.getLogger(MetaData.class);

    public static final String VALIDATE_XML = "org.eclipse.jetty.webapp.validateXml";
    public static final String ORDERED_LIBS = "javax.servlet.context.orderedLibs";
    public static final Resource NON_FRAG_RESOURCE = EmptyResource.INSTANCE;

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

    public static class OriginInfo
    {
        private final String name;
        private final Origin origin;
        private final Descriptor descriptor;
        private final Annotation annotation;
        private final Class<?> annotated;

        public OriginInfo(String n, Annotation a, Class<?> ac)
        {
            name = n;
            origin = Origin.Annotation;
            descriptor = null;
            annotation = a;
            annotated = ac;
        }

        public OriginInfo(String n, Descriptor d)
        {
            name = n;
            descriptor = d;
            annotation = null;
            annotated = null;
            if (d == null)
                throw new IllegalArgumentException("No descriptor");
            if (d instanceof FragmentDescriptor)
                origin = Origin.WebFragment;
            else if (d instanceof OverrideDescriptor)
                origin = Origin.WebOverride;
            else if (d instanceof DefaultsDescriptor)
                origin = Origin.WebDefaults;
            else
                origin = Origin.WebXml;
        }

        public OriginInfo(String n)
        {
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

    public void setDefaults(Resource webDefaults)
        throws Exception
    {
        _webDefaultsRoot = new DefaultsDescriptor(webDefaults);
        _webDefaultsRoot.setValidating(isValidateXml());
        _webDefaultsRoot.parse();
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

    public void setWebXml(Resource webXml)
        throws Exception
    {
        _webXmlRoot = new WebDescriptor(webXml);
        _webXmlRoot.setValidating(isValidateXml());
        _webXmlRoot.parse();
        _metaDataComplete = _webXmlRoot.getMetaDataComplete() == MetaDataComplete.True;

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

    public void addOverride(Resource override)
        throws Exception
    {
        OverrideDescriptor webOverrideRoot = new OverrideDescriptor(override);
        webOverrideRoot.setValidating(false);
        webOverrideRoot.parse();

        switch (webOverrideRoot.getMetaDataComplete())
        {
            case True:
                _metaDataComplete = true;
                break;
            case False:
                _metaDataComplete = false;
                break;
            case NotSet:
                break;
        }

        if (webOverrideRoot.isOrdered())
        {
            Ordering ordering = getOrdering();

            if (ordering == null)
                ordering = new AbsoluteOrdering(this);

            List<String> order = webOverrideRoot.getOrdering();
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
        _webOverrideRoots.add(webOverrideRoot);
    }

    /**
     * Add a web-fragment.xml
     *
     * @param jarResource the jar the fragment is contained in
     * @param xmlResource the resource representing the xml file
     * @throws Exception if unable to add fragment
     */
    public void addFragment(Resource jarResource, Resource xmlResource)
        throws Exception
    {
        if (_metaDataComplete)
            return; //do not process anything else if web.xml/web-override.xml set metadata-complete

        //Metadata-complete is not set, or there is no web.xml
        FragmentDescriptor descriptor = new FragmentDescriptor(xmlResource);
        _webFragmentResourceMap.put(jarResource, descriptor);
        _webFragmentRoots.add(descriptor);

        descriptor.setValidating(isValidateXml());
        descriptor.parse();

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
     * Annotations not associated with a WEB-INF/lib fragment jar.
     * These are from WEB-INF/classes or the ??container path??
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
     * eg a jar or dir.
     *
     * This method is synchronized as it is anticipated that it may be called by many threads
     * during the annotation scanning phase.
     *
     * @param annotation the discovered annotation
     */
    public synchronized void addDiscoveredAnnotation(DiscoveredAnnotation annotation)
    {
        if (annotation == null)
            return;

        //if no resource associated with an annotation or the resource is not one of the WEB-INF/lib jars,
        //map it to empty resource
        Resource resource = annotation.getResource();
        if (resource == null || !_webInfJars.contains(resource))
            resource = EmptyResource.INSTANCE;

        List<DiscoveredAnnotation> list =
            _annotations.computeIfAbsent(resource, k -> new ArrayList<>());
        list.add(annotation);
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

        //Ensure origins is fresh
        _origins.clear();

        // Set the ordered lib attribute
        List<Resource> orderedWebInfJars = null;
        if (getOrdering() != null)
        {
            orderedWebInfJars = getOrderedWebInfJars();
            List<String> orderedLibs = new ArrayList<>();
            for (Resource webInfJar : orderedWebInfJars)
            {
                //get just the name of the jar file
                String fullname = webInfJar.getName();
                int i = fullname.indexOf(".jar");
                int j = fullname.lastIndexOf("/", i);
                orderedLibs.add(fullname.substring(j + 1, i + 4));
            }
            context.setAttribute(ServletContext.ORDERED_LIBS, orderedLibs);
        }

        // set the webxml version
        if (_webXmlRoot != null)
        {
            context.getServletContext().setEffectiveMajorVersion(_webXmlRoot.getMajorVersion());
            context.getServletContext().setEffectiveMinorVersion(_webXmlRoot.getMinorVersion());
        }

        for (DescriptorProcessor p : _descriptorProcessors)
        {
            p.process(context, getWebDefault());
            p.process(context, getWebXml());
            for (WebDescriptor wd : getOverrideWebs())
            {
                LOG.debug("process {} {}", context, wd);
                p.process(context, wd);
            }
        }

        //get an apply the annotations that are not associated with a fragment (and hence for
        //which no ordering applies
        List<DiscoveredAnnotation> nonFragAnnotations = _annotations.get(NON_FRAG_RESOURCE);
        if (nonFragAnnotations != null)
        {
            for (DiscoveredAnnotation a : nonFragAnnotations)
            {
                LOG.debug("apply {}", a);
                a.apply();
            }
        }

        //apply the annotations that are associated with a fragment, according to the 
        //established ordering
        List<Resource> resources = null;

        if (getOrdering() != null)
            resources = orderedWebInfJars;
        else
            resources = getWebInfJars();

        for (Resource r : resources)
        {
            FragmentDescriptor fd = _webFragmentResourceMap.get(r);
            if (fd != null)
            {
                for (DescriptorProcessor p : _descriptorProcessors)
                {
                    LOG.debug("process {} {}", context, fd);
                    p.process(context, fd);
                }
            }

            List<DiscoveredAnnotation> fragAnnotations = _annotations.get(r);
            if (fragAnnotations != null)
            {
                for (DiscoveredAnnotation a : fragAnnotations)
                {
                    LOG.debug("apply {}", a);
                    a.apply();
                }
            }
        }
    }

    public boolean isDistributable()
    {
        boolean distributable = (
            (_webDefaultsRoot != null && _webDefaultsRoot.isDistributable()) ||
                (_webXmlRoot != null && _webXmlRoot.isDistributable()));

        for (WebDescriptor d : _webOverrideRoots)
        {
            distributable &= d.isDistributable();
        }

        if (getOrdering() != null)
        {
            List<Resource> orderedResources = getOrderedWebInfJars();
            for (Resource r : orderedResources)
            {
                FragmentDescriptor d = _webFragmentResourceMap.get(r);
                if (d != null)
                    distributable = distributable && d.isDistributable();
            }
        }
        return distributable;
    }

    public WebDescriptor getWebXml()
    {
        return _webXmlRoot;
    }

    public List<WebDescriptor> getOverrideWebs()
    {
        return _webOverrideRoots;
    }

    public WebDescriptor getWebDefault()
    {
        return _webDefaultsRoot;
    }

    public List<FragmentDescriptor> getFragments()
    {
        return _webFragmentRoots;
    }

    public List<Resource> getOrderedWebInfJars()
    {
        return _orderedWebInfResources;
    }

    public List<FragmentDescriptor> getOrderedFragments()
    {
        List<FragmentDescriptor> list = new ArrayList<FragmentDescriptor>();
        if (getOrdering() == null)
            return list;

        for (Resource r : getOrderedWebInfJars())
        {
            FragmentDescriptor fd = _webFragmentResourceMap.get(r);
            if (fd != null)
                list.add(fd);
        }
        return list;
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

    public FragmentDescriptor getFragment(Resource jar)
    {
        return _webFragmentResourceMap.get(jar);
    }

    public FragmentDescriptor getFragment(String name)
    {
        return _webFragmentNameMap.get(name);
    }

    public Resource getJarForFragment(String name)
    {
        FragmentDescriptor f = getFragment(name);
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

    public Map<String, FragmentDescriptor> getNamedFragments()
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

    public void addWebInfJar(Resource newResource)
    {
        _webInfJars.add(newResource);
    }

    public List<Resource> getWebInfJars()
    {
        return Collections.unmodifiableList(_webInfJars);
    }

    public List<Resource> getContainerResources()
    {
        return _orderedContainerResources;
    }

    public void addContainerResource(Resource jar)
    {
        _orderedContainerResources.add(jar);
    }

    public void setWebInfClassesDirs(List<Resource> dirs)
    {
        _webInfClasses.addAll(dirs);
    }

    public List<Resource> getWebInfClassesDirs()
    {
        return _webInfClasses;
    }

    public boolean isAllowDuplicateFragmentNames()
    {
        return _allowDuplicateFragmentNames;
    }

    public void setAllowDuplicateFragmentNames(boolean allowDuplicateFragmentNames)
    {
        this._allowDuplicateFragmentNames = allowDuplicateFragmentNames;
    }

    /**
     * @return the validateXml
     */
    public boolean isValidateXml()
    {
        return _validateXml;
    }

    /**
     * @param validateXml the validateXml to set
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
