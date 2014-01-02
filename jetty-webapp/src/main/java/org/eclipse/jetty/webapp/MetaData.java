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

package org.eclipse.jetty.webapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;




/**
 * MetaData
 *
 * All data associated with the configuration and deployment of a web application.
 */
public class MetaData
{
    private static final Logger LOG = Log.getLogger(MetaData.class);
        
    public static final String ORDERED_LIBS = "javax.servlet.context.orderedLibs";

    protected Map<String, OriginInfo> _origins  =new HashMap<String,OriginInfo>();
    protected WebDescriptor _webDefaultsRoot;
    protected WebDescriptor _webXmlRoot;
    protected final List<WebDescriptor> _webOverrideRoots=new ArrayList<WebDescriptor>();
    protected boolean _metaDataComplete;
    protected final List<DiscoveredAnnotation> _annotations = new ArrayList<DiscoveredAnnotation>();
    protected final List<DescriptorProcessor> _descriptorProcessors = new ArrayList<DescriptorProcessor>();
    protected final List<FragmentDescriptor> _webFragmentRoots = new ArrayList<FragmentDescriptor>();
    protected final Map<String,FragmentDescriptor> _webFragmentNameMap = new HashMap<String,FragmentDescriptor>();
    protected final Map<Resource, FragmentDescriptor> _webFragmentResourceMap = new HashMap<Resource, FragmentDescriptor>();
    protected final Map<Resource, List<DiscoveredAnnotation>> _webFragmentAnnotations = new HashMap<Resource, List<DiscoveredAnnotation>>();
    protected final List<Resource> _webInfJars = new ArrayList<Resource>();
    protected final List<Resource> _orderedWebInfJars = new ArrayList<Resource>(); 
    protected final List<Resource> _orderedContainerJars = new ArrayList<Resource>();
    protected Ordering _ordering;//can be set to RelativeOrdering by web-default.xml, web.xml, web-override.xml
    protected boolean allowDuplicateFragmentNames = false;
   
 
    
  

    public static class OriginInfo
    {   
        protected String name;
        protected Origin origin;
        protected Descriptor descriptor;
        
        public OriginInfo (String n, Descriptor d)
        {
            name = n;
            descriptor = d;           
            if (d == null)
                throw new IllegalArgumentException("No descriptor");
            if (d instanceof FragmentDescriptor)
                origin = Origin.WebFragment;
            else if (d instanceof OverrideDescriptor)
                origin =  Origin.WebOverride;
            else if (d instanceof DefaultsDescriptor)
                origin =  Origin.WebDefaults;
            else
                origin = Origin.WebXml;
        }
        
        public OriginInfo (String n)
        {
            name = n;
            origin = Origin.Annotation;
        }
        
        public OriginInfo(String n, Origin o)
        {
            name = n;
            origin = o;
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
    }
   
    public MetaData ()
    {
    }
    
    /**
     * Empty ready for reuse
     */
    public void clear ()
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
        _webFragmentAnnotations.clear();
        _webInfJars.clear();
        _orderedWebInfJars.clear();
        _orderedContainerJars.clear();
        _ordering = null;
        allowDuplicateFragmentNames = false;
    }
    
    public void setDefaults (Resource webDefaults)
    throws Exception
    {
        _webDefaultsRoot =  new DefaultsDescriptor(webDefaults); 
        _webDefaultsRoot.parse();
        if (_webDefaultsRoot.isOrdered())
        {
            if (_ordering == null)
                _ordering = new Ordering.AbsoluteOrdering(this);

            List<String> order = _webDefaultsRoot.getOrdering();
            for (String s:order)
            {
                if (s.equalsIgnoreCase("others"))
                    ((Ordering.AbsoluteOrdering)_ordering).addOthers();
                else 
                    ((Ordering.AbsoluteOrdering)_ordering).add(s);
            }
        }    
    }
    
    public void setWebXml (Resource webXml)
    throws Exception
    {
        _webXmlRoot = new WebDescriptor(webXml);
        _webXmlRoot.parse();
        _metaDataComplete=_webXmlRoot.getMetaDataComplete() == MetaDataComplete.True;
        
        
        
        if (_webXmlRoot.isOrdered())
        {
            if (_ordering == null)
                _ordering = new Ordering.AbsoluteOrdering(this);

            List<String> order = _webXmlRoot.getOrdering();
            for (String s:order)
            {
                if (s.equalsIgnoreCase("others"))
                    ((Ordering.AbsoluteOrdering)_ordering).addOthers();
                else 
                    ((Ordering.AbsoluteOrdering)_ordering).add(s);
            }
        }    
    }
    
    public void addOverride (Resource override)
    throws Exception
    {
        OverrideDescriptor webOverrideRoot = new OverrideDescriptor(override);
        webOverrideRoot.setValidating(false);
        webOverrideRoot.parse();
        
        switch(webOverrideRoot.getMetaDataComplete())
        {
            case True:
                _metaDataComplete=true;
                break;
            case False:
                _metaDataComplete=false;
                break;
            case NotSet:
                break;
        }
        
        if (webOverrideRoot.isOrdered())
        {
            if (_ordering == null)
                _ordering = new Ordering.AbsoluteOrdering(this);

            List<String> order = webOverrideRoot.getOrdering();
            for (String s:order)
            {
                if (s.equalsIgnoreCase("others"))
                    ((Ordering.AbsoluteOrdering)_ordering).addOthers();
                else 
                    ((Ordering.AbsoluteOrdering)_ordering).add(s);
            }
        }   
        _webOverrideRoots.add(webOverrideRoot);
    }
    
    
    /**
     * Add a web-fragment.xml
     * 
     * @param jarResource the jar the fragment is contained in
     * @param xmlResource the resource representing the xml file
     * @throws Exception
     */
    public void addFragment (Resource jarResource, Resource xmlResource)
    throws Exception
    { 
        if (_metaDataComplete)
            return; //do not process anything else if web.xml/web-override.xml set metadata-complete
        
        //Metadata-complete is not set, or there is no web.xml
        FragmentDescriptor descriptor = new FragmentDescriptor(xmlResource);
        _webFragmentResourceMap.put(jarResource, descriptor);
        _webFragmentRoots.add(descriptor);
        
        descriptor.parse();
        
        if (descriptor.getName() != null)
        {
            Descriptor existing = _webFragmentNameMap.get(descriptor.getName());
            if (existing != null && !isAllowDuplicateFragmentNames())
            {
                throw new IllegalStateException("Duplicate fragment name: "+descriptor.getName()+" for "+existing.getResource()+" and "+descriptor.getResource());
            }
            else
                _webFragmentNameMap.put(descriptor.getName(), descriptor);
        }

        //If web.xml has specified an absolute ordering, ignore any relative ordering in the fragment
        if (_ordering != null && _ordering.isAbsolute())
            return;
        
        if (_ordering == null && descriptor.isOrdered())
            _ordering = new Ordering.RelativeOrdering(this);
    }

    /**
     * Annotations not associated with a WEB-INF/lib fragment jar.
     * These are from WEB-INF/classes or the ??container path??
     * @param annotations
     */
    public void addDiscoveredAnnotations(List<DiscoveredAnnotation> annotations)
    {
        if (annotations == null)
            return;
        for (DiscoveredAnnotation a:annotations)
        {
            Resource r = a.getResource();
            if (r == null || !_webInfJars.contains(r))
                _annotations.add(a);
            else 
                addDiscoveredAnnotation(a.getResource(), a);
                
        }
    }
    
    
    public void addDiscoveredAnnotation(Resource resource, DiscoveredAnnotation annotation)
    {
        List<DiscoveredAnnotation> list = _webFragmentAnnotations.get(resource);
        if (list == null)
        {
            list = new ArrayList<DiscoveredAnnotation>();
            _webFragmentAnnotations.put(resource, list);
        }
        list.add(annotation);
    }
    

    public void addDiscoveredAnnotations(Resource resource, List<DiscoveredAnnotation> annotations)
    {
        List<DiscoveredAnnotation> list = _webFragmentAnnotations.get(resource);
        if (list == null)
        {
            list = new ArrayList<DiscoveredAnnotation>();
            _webFragmentAnnotations.put(resource, list);
        }
            
        list.addAll(annotations);
    }
    
    public void addDescriptorProcessor(DescriptorProcessor p)
    {
        _descriptorProcessors.add(p);
    }
    
    public void orderFragments ()
    {
        //if we have already ordered them don't do it again
        if (_orderedWebInfJars.size()==_webInfJars.size())
            return;
        
        if (_ordering != null)
            _orderedWebInfJars.addAll(_ordering.order(_webInfJars));
        else
            _orderedWebInfJars.addAll(_webInfJars);
    }
    
    
    /**
     * Resolve all servlet/filter/listener metadata from all sources: descriptors and annotations.
     * 
     */
    public void resolve (WebAppContext context)
    throws Exception
    {
        LOG.debug("metadata resolve {}",context);
        
        //Ensure origins is fresh
        _origins.clear();
        
        // Set the ordered lib attribute
        if (_ordering != null)
        {
            List<String> orderedLibs = new ArrayList<String>();
            for (Resource webInfJar:_orderedWebInfJars)
            {
                //get just the name of the jar file
                String fullname = webInfJar.getName();
                int i = fullname.indexOf(".jar");          
                int j = fullname.lastIndexOf("/", i);
                orderedLibs.add(fullname.substring(j+1,i+4));
            }
            context.setAttribute(ServletContext.ORDERED_LIBS, orderedLibs);
        }

        // set the webxml version
        if (_webXmlRoot != null)
        {
            context.getServletContext().setEffectiveMajorVersion(_webXmlRoot.getMajorVersion());
            context.getServletContext().setEffectiveMinorVersion(_webXmlRoot.getMinorVersion());
        }

        for (DescriptorProcessor p:_descriptorProcessors)
        {
            p.process(context,getWebDefault());
            p.process(context,getWebXml());
            for (WebDescriptor wd : getOverrideWebs())   
            {
                LOG.debug("process {} {}",context,wd);
                p.process(context,wd);
            }
        }
        
        for (DiscoveredAnnotation a:_annotations)
        {
            LOG.debug("apply {}",a);
            a.apply();
        }
    
        
        List<Resource> resources = getOrderedWebInfJars();
        for (Resource r:resources)
        {
            FragmentDescriptor fd = _webFragmentResourceMap.get(r);
            if (fd != null)
            {
                for (DescriptorProcessor p:_descriptorProcessors)
                {
                    LOG.debug("process {} {}",context,fd);
                    p.process(context,fd);
                }
            }
            
            List<DiscoveredAnnotation> fragAnnotations = _webFragmentAnnotations.get(r);
            if (fragAnnotations != null)
            {
                for (DiscoveredAnnotation a:fragAnnotations)
                {
                    LOG.debug("apply {}",a);
                    a.apply();
                }
            }
        }
        
    }
    
    public boolean isDistributable ()
    {
        boolean distributable = (
                (_webDefaultsRoot != null && _webDefaultsRoot.isDistributable()) 
                || (_webXmlRoot != null && _webXmlRoot.isDistributable()));
        
        for (WebDescriptor d : _webOverrideRoots)
            distributable&=d.isDistributable();
        
        List<Resource> orderedResources = getOrderedWebInfJars();
        for (Resource r: orderedResources)
        {  
            FragmentDescriptor d = _webFragmentResourceMap.get(r);
            if (d!=null)
                distributable = distributable && d.isDistributable();
        }
        return distributable;
    }
   
    
    public WebDescriptor getWebXml ()
    {
        return _webXmlRoot;
    }
    
    public List<WebDescriptor> getOverrideWebs ()
    {
        return _webOverrideRoots;
    }
    
    public WebDescriptor getWebDefault ()
    {
        return _webDefaultsRoot;
    }
    
    public List<FragmentDescriptor> getFragments ()
    {
        return _webFragmentRoots;
    }
    
    public List<Resource> getOrderedWebInfJars()
    {
        return _orderedWebInfJars == null? new ArrayList<Resource>(): _orderedWebInfJars;
    }
    
    public List<FragmentDescriptor> getOrderedFragments ()
    {
        List<FragmentDescriptor> list = new ArrayList<FragmentDescriptor>();
        if (_orderedWebInfJars == null)
            return list;

        for (Resource r:_orderedWebInfJars)
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
    
    public void setOrdering (Ordering o)
    {
        _ordering = o;
    }
    
    public FragmentDescriptor getFragment (Resource jar)
    {
        return _webFragmentResourceMap.get(jar);
    }
    
    public FragmentDescriptor getFragment(String name)
    {
        return _webFragmentNameMap.get(name);
    }
    
    public Resource getJarForFragment (String name)
    {
        FragmentDescriptor f = getFragment(name);
        if (f == null)
            return null;
        
        Resource jar = null;
        for (Resource r: _webFragmentResourceMap.keySet())
        {
            if (_webFragmentResourceMap.get(r).equals(f))
                jar = r;
        }
        return jar;
    }
    
    public Map<String,FragmentDescriptor> getNamedFragments ()
    {
        return Collections.unmodifiableMap(_webFragmentNameMap);
    }
    
    
    public Origin getOrigin (String name)
    {
        OriginInfo x =  _origins.get(name);
        if (x == null)
            return Origin.NotSet;
        
        return x.getOriginType();
    }
  
 
    public Descriptor getOriginDescriptor (String name)
    {
        OriginInfo o = _origins.get(name);
        if (o == null)
            return null;
        return o.getDescriptor();
    }
    
    public void setOrigin (String name, Descriptor d)
    {
        OriginInfo x = new OriginInfo (name, d);
        _origins.put(name, x);
    }
    
    public void setOrigin (String name)
    {
        if (name == null)
            return;
       
        OriginInfo x = new OriginInfo (name, Origin.Annotation);
        _origins.put(name, x);
    }
    
    public void setOrigin(String name, Origin origin)
    {
        if (name == null)
            return;
       
        OriginInfo x = new OriginInfo (name, origin);
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
    
    public List<Resource> getOrderedContainerJars()
    {
        return _orderedContainerJars;
    }
    
    public void addContainerJar(Resource jar)
    {
        _orderedContainerJars.add(jar);
    }
    public boolean isAllowDuplicateFragmentNames()
    {
        return allowDuplicateFragmentNames;
    }

    public void setAllowDuplicateFragmentNames(boolean allowDuplicateFragmentNames)
    {
        this.allowDuplicateFragmentNames = allowDuplicateFragmentNames;
    }
}
