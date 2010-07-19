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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;

import org.eclipse.jetty.util.resource.Resource;





/**
 * MetaData
 *
 *
 */
public class MetaData
{        
    public static final String WEBXML_MAJOR_VERSION = "org.eclipse.jetty.webXmlMajorVersion";
    public static final String WEBXML_MINOR_VERSION = "org.eclipse.jetty.webXmlMinorVersion";
    public static final String WEBXML_CLASSNAMES = "org.eclipse.jetty.webXmlClassNames";
    public static final String ORDERED_LIBS = "javax.servlet.context.orderedLibs";

    public enum Origin {NotSet, WebXml, WebDefaults, WebOverride, WebFragment, Annotation};
    
    protected WebAppContext _context;
    protected Map<String, OriginInfo> _origins = new HashMap<String,OriginInfo>();
    protected Descriptor _webDefaultsRoot;
    protected Descriptor _webXmlRoot;
    protected Descriptor _webOverrideRoot;
    protected boolean _metaDataComplete;
    protected List<DiscoveredAnnotation> _annotations = new ArrayList<DiscoveredAnnotation>();
    protected List<DescriptorProcessor> _descriptorProcessors = new ArrayList<DescriptorProcessor>();
    protected List<FragmentDescriptor> _webFragmentRoots = new ArrayList<FragmentDescriptor>();
    protected Map<String,FragmentDescriptor> _webFragmentNameMap = new HashMap<String,FragmentDescriptor>();
    protected Map<Resource, FragmentDescriptor> _webFragmentResourceMap = new HashMap<Resource, FragmentDescriptor>();
    protected Map<Resource, List<DiscoveredAnnotation>> _webFragmentAnnotations = new HashMap<Resource, List<DiscoveredAnnotation>>();
    protected List<Resource> _orderedResources;
    protected Ordering _ordering;//can be set to RelativeOrdering by web-default.xml, web.xml, web-override.xml
    protected StandardDescriptorProcessor _standardDescriptorProcessor;
 
    
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
            if (d instanceof OverrideDescriptor)
                origin =  Origin.WebOverride;
            if (d instanceof DefaultsDescriptor)
                origin =  Origin.WebDefaults;
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
   
    /**
     * Ordering
     *
     *
     */
    public interface Ordering
    {
        public List<Resource> order(List<Resource> fragments);
        public boolean isAbsolute ();
        public boolean hasOther();
    }
    
    /**
     * AbsoluteOrdering
     *
     * An <absolute-order> element in web.xml
     */
    public class AbsoluteOrdering implements Ordering
    {
        public static final String OTHER = "@@-OTHER-@@";
        protected List<String> _order = new ArrayList<String>();
        protected boolean _hasOther = false;
 
        /** 
         * Order the list of jars in WEB-INF/lib according to the ordering declarations in the descriptors
         * @see org.eclipse.jetty.webapp.MetaData.Ordering#order(java.util.List)
         */
        @Override
        public List<Resource> order(List<Resource> jars)
        {           
            List<Resource> orderedList = new ArrayList<Resource>();
            List<Resource> tmp = new ArrayList<Resource>(jars);
          
            //1. put everything into the list of named others, and take the named ones out of there,
            //assuming we will want to use the <other> clause
            Map<String,FragmentDescriptor> others = new HashMap<String,FragmentDescriptor>(getNamedFragments());
            
            //2. for each name, take out of the list of others, add to tail of list
            int index = -1;
            for (String item:_order)
            {
                if (!item.equals(OTHER))
                {
                    FragmentDescriptor f = others.remove(item);
                    if (f != null)
                    {
                        Resource jar = getJarForFragment(item);
                        orderedList.add(jar); //take from others and put into final list in order, ignoring duplicate names
                        //remove resource from list for resource matching name of descriptor
                        tmp.remove(jar);
                    }
                }
                else
                    index = orderedList.size(); //remember the index at which we want to add in all the others
            }
            
            //3. if <other> was specified, insert rest of the fragments 
            if (_hasOther)
            {
                orderedList.addAll((index < 0? 0: index), tmp);
            }
            
            return orderedList;
        }
        
        @Override
        public boolean isAbsolute()
        {
            return true;
        }
        
        public void add (String name)
        {
            _order.add(name); 
        }
        
        public void addOthers ()
        {
            if (_hasOther)
                throw new IllegalStateException ("Duplicate <other> element in absolute ordering");
            
            _hasOther = true;
            _order.add(OTHER);
        }
        
        @Override
        public boolean hasOther ()
        {
            return _hasOther;
        }
    }
    
    
    /**
     * RelativeOrdering
     *
     * A set of <order> elements in web-fragment.xmls.
     */
    public class RelativeOrdering implements Ordering
    {
        protected LinkedList<Resource> _beforeOthers = new LinkedList<Resource>();
        protected LinkedList<Resource> _afterOthers = new LinkedList<Resource>();
        protected LinkedList<Resource> _noOthers = new LinkedList<Resource>();
        
        /** 
         * Order the list of jars according to the ordering declared
         * in the various web-fragment.xml files.
         * @see org.eclipse.jetty.webapp.MetaData.Ordering#order(java.util.List)
         */
        @Override
        public List<Resource> order(List<Resource> jars)
        {         
            //for each jar, put it into the ordering according to the fragment ordering
            for (Resource jar:jars)
            {
                //check if the jar has a fragment descriptor
                FragmentDescriptor descriptor = _webFragmentResourceMap.get(jar);
                if (descriptor != null)
                {
                    switch (descriptor.getOtherType())
                    {
                        case None:
                        {
                            ((RelativeOrdering)_ordering).addNoOthers(jar);
                            break;
                        }
                        case Before:
                        { 
                            ((RelativeOrdering)_ordering).addBeforeOthers(jar);
                            break;
                        }
                        case After:
                        {
                            ((RelativeOrdering)_ordering).addAfterOthers(jar);
                            break;
                        }
                    } 
                }
                else
                {
                    //jar fragment has no descriptor, but there is a relative ordering in place, so it must be part of the others
                    ((RelativeOrdering)_ordering).addNoOthers(jar);
                }
            }            
                
            //now apply the ordering
            List<Resource> orderedList = new ArrayList<Resource>(); 
            int maxIterations = 2;
            boolean done = false;
            do
            {
                //1. order the before-others according to any explicit before/after relationships 
                boolean changesBefore = orderList(_beforeOthers);

                //2. order the after-others according to any explicit before/after relationships
                boolean changesAfter = orderList(_afterOthers);

                //3. order the no-others according to their explicit before/after relationships
                boolean changesNone = orderList(_noOthers);
                
                //we're finished on a clean pass through with no ordering changes
                done = (!changesBefore && !changesAfter && !changesNone);
            }
            while (!done && (--maxIterations >0));
            
            //4. merge before-others + no-others +after-others
            if (!done)
                throw new IllegalStateException("Circular references for fragments");
            
            for (Resource r: _beforeOthers)
                orderedList.add(r);
            for (Resource r: _noOthers)
                orderedList.add(r);
            for(Resource r: _afterOthers)
                orderedList.add(r);
            
            return orderedList;
        }
        
        @Override
        public boolean isAbsolute ()
        {
            return false;
        }
        
        @Override
        public boolean hasOther ()
        {
            return !_beforeOthers.isEmpty() || !_afterOthers.isEmpty();
        }
        
        public void addBeforeOthers (Resource r)
        {
            _beforeOthers.addLast(r);
        }
        
        public void addAfterOthers (Resource r)
        {
            _afterOthers.addLast(r);
        }
        
        public void addNoOthers (Resource r)
        {
            _noOthers.addLast(r);
        }
        
       protected boolean orderList (LinkedList<Resource> list)
       {
           //Take a copy of the list so we can iterate over it and at the same time do random insertions
           boolean changes = false;
           List<Resource> iterable = new ArrayList<Resource>(list);
           Iterator<Resource> itor = iterable.iterator();
           
           while (itor.hasNext())
           {
               Resource r = itor.next();
               FragmentDescriptor f = getFragment(r);
               if (f == null)
               {
                   //no fragment for this resource so cannot have any ordering directives
                   continue;
               }
                
               //Handle any explicit <before> relationships for the fragment we're considering
               List<String> befores = f.getBefores();
               if (befores != null && !befores.isEmpty())
               {
                   for (String b: befores)
                   {
                       //Fragment we're considering must be before b
                       //Check that we are already before it, if not, move us so that we are.
                       //If the name does not exist in our list, then get it out of the no-other list
                       if (!isBefore(list, f.getName(), b))
                       {
                           //b is not already before name, move it so that it is
                           int idx1 = getIndexOf(list, f.getName());
                           int idx2 = getIndexOf(list, b);

                           //if b is not in the same list
                           if (idx2 < 0)
                           {
                               changes = true;
                               // must be in the noOthers list or it would have been an error
                               Resource bResource = getJarForFragment(b);
                               if (bResource != null)
                               {
                                   //If its in the no-others list, insert into this list so that we are before it
                                   if (_noOthers.remove(bResource))
                                   {
                                       insert(list, idx1+1, b);
                                      
                                   }
                               }
                           }
                           else
                           {
                               //b is in the same list but b is before name, so swap it around
                               list.remove(idx1);
                               insert(list, idx2, f.getName());
                               changes = true;
                           }
                       }
                   }
               }

               //Handle any explicit <after> relationships
               List<String> afters = f.getAfters();
               if (afters != null && !afters.isEmpty())
               {
                   for (String a: afters)
                   {
                       //Check that fragment we're considering is after a, moving it if possible if its not
                       if (!isAfter(list, f.getName(), a))
                       {
                           //name is not after a, move it
                           int idx1 = getIndexOf(list, f.getName());
                           int idx2 = getIndexOf(list, a);
                           
                           //if a is not in the same list as name
                           if (idx2 < 0)
                           {
                               changes = true;
                               //take it out of the noOthers list and put it in the right place in this list
                               Resource aResource = getJarForFragment(a);
                               if (aResource != null)
                               {
                                   if (_noOthers.remove(aResource))
                                   {
                                       insert(list,idx1, aResource);       
                                   }
                               }
                           }
                           else
                           {
                               //a is in the same list as name, but in the wrong place, so move it
                               list.remove(idx2);
                               insert(list,idx1, a);
                               changes = true;
                           }
                       }
                       //Name we're considering must be after this name
                       //Check we're already after it, if not, move us so that we are.
                       //If the name does not exist in our list, then get it out of the no-other list
                   }
               }
           }
 
           return changes;
       }
       
       /**
        * Is fragment with name a before fragment with name b?
        * @param list
        * @param fragNameA
        * @param fragNameB
        * @return
        */
       protected boolean isBefore (List<Resource> list, String fragNameA, String fragNameB)
       {
           //check if a and b are already in the same list, and b is already
           //before a 
           int idxa = getIndexOf(list, fragNameA);
           int idxb = getIndexOf(list, fragNameB);
           
           
           if (idxb >=0 && idxb < idxa)
           {
               //a and b are in the same list but a is not before b
               return false;
           }
           
           if (idxb < 0)
           {
               //a and b are not in the same list, but it is still possible that a is before
               //b, depending on which list we're examining
               if (list == _beforeOthers)
               {
                   //The list we're looking at is the beforeOthers.If b is in the _afterOthers or the _noOthers, then by
                   //definition a is before it
                   return true;
               }
               else if (list == _afterOthers)
               {
                   //The list we're looking at is the afterOthers, then a will be the tail of
                   //the final list.  If b is in the beforeOthers list, then b will be before a and an error.
                   if (_beforeOthers.contains(fragNameB))
                       throw new IllegalStateException("Incorrect relationship: "+fragNameA+" before "+fragNameB);
                   else
                       return false; //b could be moved to the list
               }
           }
          
           //a and b are in the same list and a is already before b
           return true;
       }
       
       
       /**
        * Is fragment name "a" after fragment name "b"?
        * @param list
        * @param fragNameA
        * @param fragNameB
        * @return
        */
       protected boolean isAfter(List<Resource> list, String fragNameA, String fragNameB)
       {
           int idxa = getIndexOf(list, fragNameA);
           int idxb = getIndexOf(list, fragNameB);
           
           if (idxb >=0 && idxa < idxb)
           {
               //a and b are both in the same list, but a is before b
               return false;
           }
           
           if (idxb < 0)
           {
               //a and b are in different lists. a could still be after b depending on which list it is in.

               if (list == _afterOthers)
               {
                   //The list we're looking at is the afterOthers. If b is in the beforeOthers or noOthers then
                   //by definition a is after b because a is in the afterOthers list.
                   return true;
               }
               else if (list == _beforeOthers)
               {
                   //The list we're looking at is beforeOthers, and contains a and will be before
                   //everything else in the final ist. If b is in the afterOthers list, then a cannot be before b.
                   if (_afterOthers.contains(fragNameB))
                       throw new IllegalStateException("Incorrect relationship: "+fragNameB+" after "+fragNameA);
                   else
                       return false; //b could be moved from noOthers list
               }
           }

           return true; //a and b in the same list, a is after b
       }

       /**
        * Insert the resource matching the fragName into the list of resources
        * at the location indicated by index.
        * 
        * @param list
        * @param index
        * @param fragName
        */
       protected void insert(List<Resource> list, int index, String fragName)
       {
           Resource jar = getJarForFragment(fragName);
           if (jar == null)
               throw new IllegalStateException("No jar for insertion");
           
           insert(list, index, jar);
       }
       
       protected void insert(List<Resource> list, int index, Resource resource)
       {
           if (list == null)
               throw new IllegalStateException("List is null for insertion");
           
           //add it at the end
           if (index > list.size())
               list.add(resource);
           else
               list.add(index, resource);
       }
       
       protected void remove (List<Resource> resources, Resource r)
       {
           if (resources == null)
               return;
           resources.remove(r);
       }

       protected int getIndexOf(List<Resource> resources, String fragmentName)
       {
          FragmentDescriptor fd = getFragment(fragmentName);
          if (fd == null)
              return -1;
          
          
          Resource r = getJarForFragment(fragmentName);
          if (r == null)
              return -1;
          
          return resources.indexOf(r);
       }
    }
    
    public MetaData (WebAppContext context)
    {
        _context = context;

    }
    
    public WebAppContext getContext()
    {
        return _context;
    }
    
  
    
    public void setDefaults (Resource webDefaults)
    throws Exception
    {
        _webDefaultsRoot =  new DefaultsDescriptor(webDefaults); 
        _webDefaultsRoot.parse();
        if (_webDefaultsRoot.isOrdered())
        {
            if (_ordering == null)
                _ordering = new AbsoluteOrdering();

            List<String> order = _webDefaultsRoot.getOrdering();
            for (String s:order)
            {
                if (s.equalsIgnoreCase("others"))
                    ((AbsoluteOrdering)_ordering).addOthers();
                else 
                    ((AbsoluteOrdering)_ordering).add(s);
            }
        }    
    }
    
    public void setWebXml (Resource webXml)
    throws Exception
    {
        _webXmlRoot = new Descriptor(webXml);
        _webXmlRoot.parse();
        _metaDataComplete=_webXmlRoot.getMetaDataComplete() == Descriptor.MetaDataComplete.True;

        _context.setAttribute(WEBXML_CLASSNAMES, _webXmlRoot.getClassNames());
        
        if (_webXmlRoot.isOrdered())
        {
            if (_ordering == null)
                _ordering = new AbsoluteOrdering();

            List<String> order = _webXmlRoot.getOrdering();
            for (String s:order)
            {
                if (s.equalsIgnoreCase("others"))
                    ((AbsoluteOrdering)_ordering).addOthers();
                else 
                    ((AbsoluteOrdering)_ordering).add(s);
            }
        }    
    }
    
    public void setOverride (Resource override)
    throws Exception
    {
        _webOverrideRoot = new OverrideDescriptor(override);
        _webOverrideRoot.setValidating(false);
        _webOverrideRoot.parse();
        
        switch(_webOverrideRoot.getMetaDataComplete())
        {
            case True:
                _metaDataComplete=true;
                break;
            case False:
                _metaDataComplete=true;
                break;
            case NotSet:
                break;
        }
        
        if (_webOverrideRoot.isOrdered())
        {
            if (_ordering == null)
                _ordering = new AbsoluteOrdering();

            List<String> order = _webOverrideRoot.getOrdering();
            for (String s:order)
            {
                if (s.equalsIgnoreCase("others"))
                    ((AbsoluteOrdering)_ordering).addOthers();
                else 
                    ((AbsoluteOrdering)_ordering).add(s);
            }
        }   
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
            _webFragmentNameMap.put(descriptor.getName(), descriptor);

        //If web.xml has specified an absolute ordering, ignore any relative ordering in the fragment
        if (_ordering != null && _ordering.isAbsolute())
            return;
        
        if (_ordering == null && descriptor.isOrdered())
            _ordering = new RelativeOrdering();
    }

    /**
     * Annotations not associated with a WEB-INF/lib fragment jar.
     * These are from WEB-INF/classes or the ??container path??
     * @param annotations
     */
    public void addDiscoveredAnnotations(List<DiscoveredAnnotation> annotations)
    {
        _annotations.addAll(annotations);
    }

    public void addDiscoveredAnnotations(Resource resource, List<DiscoveredAnnotation> annotations)
    {
        _webFragmentAnnotations.put(resource, new ArrayList<DiscoveredAnnotation>(annotations));
    }
    
    public void addDescriptorProcessor(DescriptorProcessor p)
    {
        _descriptorProcessors.add(p);
    }
    
    public void orderFragments ()
    {
        //if we have already ordered them don't do it again
        if (_orderedResources != null)
            return;
        
        if (_ordering != null)
        {
            //Get the jars in WEB-INF/lib according to the order specified    
            _orderedResources = _ordering.order((List<Resource>)_context.getAttribute(WebInfConfiguration.WEB_INF_JAR_RESOURCES));
            
            _context.setAttribute(WebInfConfiguration.WEB_INF_ORDERED_JAR_RESOURCES, _orderedResources);
            List<String> orderedJars = new ArrayList<String>();

            for (Resource webInfJar:_orderedResources)
            {
                //get just the name of the jar file
                String fullname = webInfJar.getName();
                int i = fullname.indexOf(".jar");          
                int j = fullname.lastIndexOf("/", i);
                orderedJars.add(fullname.substring(j+1,i+4));
            }

            _context.setAttribute(ORDERED_LIBS, orderedJars);
        }
        else
            _orderedResources = new ArrayList<Resource>((List<Resource>)_context.getAttribute(WebInfConfiguration.WEB_INF_JAR_RESOURCES));
    }
    
    
    /**
     * Resolve all servlet/filter/listener metadata from all sources: descriptors and annotations.
     * 
     */
    public void resolve (WebAppContext context)
    throws Exception
    {
        //TODO - apply all descriptors and annotations in order:
        //apply descriptorProcessors to web-defaults.xml
        //apply descriptorProcessors to web.xml
        //apply descriptorProcessors to web-override.xml
        //apply discovered annotations from container path
        //apply discovered annotations from WEB-INF/classes
        //for the ordering of the jars in WEB-INF/lib:
        //  +apply descriptorProcessors to web-fragment.xml
        //  +apply discovered annotations
      
        for (DescriptorProcessor p:_descriptorProcessors)
        {
            p.process(context,getWebDefault());
            p.process(context,getWebXml());
            p.process(context,getOverrideWeb());
        }
        
        for (DiscoveredAnnotation a:_annotations)
            a.apply();
    
        
        List<Resource> resources = getOrderedResources();
        for (Resource r:resources)
        {
            FragmentDescriptor fd = _webFragmentResourceMap.get(r);
            if (fd != null)
            {
                for (DescriptorProcessor p:_descriptorProcessors)
                {
                    p.process(context,fd);
                }
            }
            
            List<DiscoveredAnnotation> fragAnnotations = _webFragmentAnnotations.get(r);
            if (fragAnnotations != null)
            {
                for (DiscoveredAnnotation a:fragAnnotations)
                    a.apply();
            }
        }
    }
    
    public boolean isDistributable ()
    {
        boolean distributable = (
                (_webDefaultsRoot != null && _webDefaultsRoot.isDistributable()) 
                || (_webXmlRoot != null && _webXmlRoot.isDistributable())
                || (_webOverrideRoot != null && _webOverrideRoot.isDistributable()));

        List<Resource> orderedResources = getOrderedResources();
        for (Resource r: orderedResources)
        {  
            FragmentDescriptor d = _webFragmentResourceMap.get(r);
            if (d!=null)
                distributable = distributable && d.isDistributable();
        }
        return distributable;
    }
   
    
    public Descriptor getWebXml ()
    {
        return _webXmlRoot;
    }
    
    public Descriptor getOverrideWeb ()
    {
        return _webOverrideRoot;
    }
    
    public Descriptor getWebDefault ()
    {
        return _webDefaultsRoot;
    }
    
    public List<FragmentDescriptor> getFragments ()
    {
        return _webFragmentRoots;
    }
    
    public List<Resource> getOrderedResources ()
    {
        return _orderedResources == null? new ArrayList<Resource>(): _orderedResources;
        //return _orderedResources;
    }
    
    public List<FragmentDescriptor> getOrderedFragments ()
    {
        List<FragmentDescriptor> list = new ArrayList<FragmentDescriptor>();
        if (_orderedResources == null)
            return list;

        for (Resource r:_orderedResources)
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

    public boolean isMetaDataComplete()
    {
        return _metaDataComplete;
    }
}
