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

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.ServletContext;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlParser;





/**
 * WebXmlProcessor
 *
 *
 */
public class WebXmlProcessor
{        
    public static final String WEB_PROCESSOR = "org.eclipse.jetty.webProcessor";
    public static final String METADATA_COMPLETE = "org.eclipse.jetty.metadataComplete";
    public static final String WEBXML_MAJOR_VERSION = "org.eclipse.jetty.webXmlMajorVersion";
    public static final String WEBXML_MINOR_VERSION = "org.eclipse.jetty.webXmlMinorVersion";
    public static final String WEBXML_CLASSNAMES = "org.eclipse.jetty.webXmlClassNames";

    public enum Origin {NotSet, WebXml, WebDefaults, WebOverride, WebFragment};
    
    protected WebAppContext _context;
    protected Map<String, Descriptor> _origins = new HashMap<String,Descriptor>();
    protected Descriptor _webDefaultsRoot;
    protected Descriptor _webXmlRoot;
    protected Descriptor _webOverrideRoot;
    protected List<Fragment> _webFragmentRoots = new ArrayList<Fragment>();
    protected Map<String,Fragment> _webFragmentNameMap = new HashMap<String,Fragment>();
    protected List<Fragment> _orderedFragments = new LinkedList<Fragment>();
    protected XmlParser _parser;
    protected Ordering _ordering;//can be set to RelativeOrdering by web-default.xml, web.xml, web-override.xml
    protected StandardDescriptorProcessor _standardDescriptorProcessor;
    
   

    
    public static XmlParser newParser()
    throws ClassNotFoundException
    {
        XmlParser xmlParser=new XmlParser();
        //set up cache of DTDs and schemas locally        
        URL dtd22=Loader.getResource(Servlet.class,"javax/servlet/resources/web-app_2_2.dtd",true);
        URL dtd23=Loader.getResource(Servlet.class,"javax/servlet/resources/web-app_2_3.dtd",true);
        URL j2ee14xsd=Loader.getResource(Servlet.class,"javax/servlet/resources/j2ee_1_4.xsd",true);
        URL webapp24xsd=Loader.getResource(Servlet.class,"javax/servlet/resources/web-app_2_4.xsd",true);
        URL webapp25xsd=Loader.getResource(Servlet.class,"javax/servlet/resources/web-app_2_5.xsd",true);
        URL webapp30xsd=Loader.getResource(Servlet.class,"javax/servlet/resources/web-app_3_0.xsd",true);
        URL webcommon30xsd=Loader.getResource(Servlet.class,"javax/servlet/resources/web-common_3_0.xsd",true);
        URL webfragment30xsd=Loader.getResource(Servlet.class,"javax/servlet/resources/web-fragment_3_0.xsd",true);
        URL schemadtd=Loader.getResource(Servlet.class,"javax/servlet/resources/XMLSchema.dtd",true);
        URL xmlxsd=Loader.getResource(Servlet.class,"javax/servlet/resources/xml.xsd",true);
        URL webservice11xsd=Loader.getResource(Servlet.class,"javax/servlet/resources/j2ee_web_services_client_1_1.xsd",true);
        URL webservice12xsd=Loader.getResource(Servlet.class,"javax/servlet/resources/javaee_web_services_client_1_2.xsd",true);
        URL datatypesdtd=Loader.getResource(Servlet.class,"javax/servlet/resources/datatypes.dtd",true);

        URL jsp20xsd = null;
        URL jsp21xsd = null;

        try
        {
            Class jsp_page = Loader.loadClass(WebXmlConfiguration.class, "javax.servlet.jsp.JspPage");
            jsp20xsd = jsp_page.getResource("/javax/servlet/resources/jsp_2_0.xsd");
            jsp21xsd = jsp_page.getResource("/javax/servlet/resources/jsp_2_1.xsd");
        }
        catch (Exception e)
        {
            Log.ignore(e);
        }
        finally
        {
            if (jsp20xsd == null) jsp20xsd = Loader.getResource(Servlet.class, "javax/servlet/resources/jsp_2_0.xsd", true);
            if (jsp21xsd == null) jsp21xsd = Loader.getResource(Servlet.class, "javax/servlet/resources/jsp_2_1.xsd", true);
        }
        
        redirect(xmlParser,"web-app_2_2.dtd",dtd22);
        redirect(xmlParser,"-//Sun Microsystems, Inc.//DTD Web Application 2.2//EN",dtd22);
        redirect(xmlParser,"web.dtd",dtd23);
        redirect(xmlParser,"web-app_2_3.dtd",dtd23);
        redirect(xmlParser,"-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN",dtd23);
        redirect(xmlParser,"XMLSchema.dtd",schemadtd);
        redirect(xmlParser,"http://www.w3.org/2001/XMLSchema.dtd",schemadtd);
        redirect(xmlParser,"-//W3C//DTD XMLSCHEMA 200102//EN",schemadtd);
        redirect(xmlParser,"jsp_2_0.xsd",jsp20xsd);
        redirect(xmlParser,"http://java.sun.com/xml/ns/j2ee/jsp_2_0.xsd",jsp20xsd);
        redirect(xmlParser,"http://java.sun.com/xml/ns/javaee/jsp_2_1.xsd",jsp21xsd);
        redirect(xmlParser,"j2ee_1_4.xsd",j2ee14xsd);
        redirect(xmlParser,"http://java.sun.com/xml/ns/j2ee/j2ee_1_4.xsd",j2ee14xsd);
        redirect(xmlParser,"web-app_2_4.xsd",webapp24xsd);
        redirect(xmlParser,"http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd",webapp24xsd);
        redirect(xmlParser,"web-app_2_5.xsd",webapp25xsd);
        redirect(xmlParser,"http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd",webapp25xsd);
        redirect(xmlParser,"web-app_3_0.xsd",webapp30xsd);
        redirect(xmlParser,"http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd",webapp30xsd);
        redirect(xmlParser,"web-common_3_0.xsd",webcommon30xsd);
        redirect(xmlParser,"http://java.sun.com/xml/ns/javaee/web-common_3_0.xsd",webcommon30xsd);
        redirect(xmlParser,"web-fragment_3_0.xsd",webfragment30xsd);
        redirect(xmlParser,"http://java.sun.com/xml/ns/javaee/web-fragment_3_0.xsd",webfragment30xsd);
        redirect(xmlParser,"xml.xsd",xmlxsd);
        redirect(xmlParser,"http://www.w3.org/2001/xml.xsd",xmlxsd);
        redirect(xmlParser,"datatypes.dtd",datatypesdtd);
        redirect(xmlParser,"http://www.w3.org/2001/datatypes.dtd",datatypesdtd);
        redirect(xmlParser,"j2ee_web_services_client_1_1.xsd",webservice11xsd);
        redirect(xmlParser,"http://www.ibm.com/webservices/xsd/j2ee_web_services_client_1_1.xsd",webservice11xsd);
        redirect(xmlParser,"javaee_web_services_client_1_2.xsd",webservice12xsd);
        redirect(xmlParser,"http://www.ibm.com/webservices/xsd/javaee_web_services_client_1_2.xsd",webservice12xsd);
        return xmlParser;
    }
    
    
    protected static void redirect(XmlParser parser, String resource, URL source)
    {
        if (source != null) parser.redirectEntity(resource, source);
    }
    
    /**
     * Ordering
     *
     *
     */
    public interface Ordering
    {
        public List<Fragment> order();
        public boolean isAbsolute ();
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
 
        public List<Fragment> order()
        {           
            List<Fragment> orderedList = new ArrayList<Fragment>();
          
            //1. put everything into the list of named others, and take the named ones out of there,
            //assuming we will want to use the <other> clause
            Map<String,Fragment> others = new HashMap(getNamedFragments());
            
            //2. for each name, take out of the list of others, add to tail of list
            int index = -1;
            for (String item:_order)
            {
                if (!item.equals(OTHER))
                {
                    Fragment f = others.remove(item);
                    if (f != null)
                        orderedList.add(f); //take from others and put into final list in order, ignoring duplicate names
                }
                else
                    index = orderedList.size(); //remember the index at which we want to add in all the others
            }
            
            //3. if <other> was specified, insert the leftovers
            if (_hasOther)
                orderedList.addAll((index < 0? 0: index), others.values());
            
            return orderedList;
        }
        
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
    }
    
    
    /**
     * RelativeOrdering
     *
     * A set of <order> elements in web-fragment.xmls.
     */
    public class RelativeOrdering implements Ordering
    {
        protected LinkedList<String> _beforeOthers = new LinkedList<String>();
        protected LinkedList<String> _afterOthers = new LinkedList<String>();
        protected LinkedList<String> _noOthers = new LinkedList<String>();
        
        public List<Fragment> order()
        {           
            List<Fragment> orderedList = new ArrayList<Fragment>();
 
            int maxIterations = 2;
            boolean done = false;
            do
            {
                //1. order the before-others according to any explicit before/after relationships 
                done = orderList(_beforeOthers);

                //2. order the after-others according to any explicit before/after relationships
                done = orderList(_afterOthers);

                //3. order the no-others according to their explicit before/after relationships
                done = orderList(_noOthers);
            }
            while (!done && (--maxIterations >0));
            
            //5. merge before-others + no-others +after-others
            if (!done)
                throw new IllegalStateException("Circular references for fragments");
            
            for (String s: _beforeOthers)
                orderedList.add(getFragment(s));
            for (String s: _noOthers)
                orderedList.add(getFragment(s));
            for(String s: _afterOthers)
                orderedList.add(getFragment(s));
            
            return orderedList;
        }
        
        public boolean isAbsolute ()
        {
            return false;
        }
        
        public void addBeforeOthers (Fragment d)
        {
            _beforeOthers.addLast(d.getName());
        }
        
        public void addAfterOthers (Fragment d)
        {
            _afterOthers.addLast(d.getName());
        }
        
        public void addNoOthers (Fragment d)
        {
            _noOthers.addLast(d.getName());
        }
        
       protected boolean orderList (LinkedList<String> list)
       {
           //Take a copy of the list so we can iterate over it and at the same time do random insertions
           boolean noChanges = true;
           List<String> iterable = new ArrayList(list);
           Iterator<String> itor = iterable.iterator();
           
           while (itor.hasNext())
           {
               String name = itor.next();
               Fragment f = _webFragmentNameMap.get(name);
               if (f == null)
                  throw new IllegalStateException ("No fragment matching name "+name);

               //Handle any explicit <before> relationships for the fragment we're considering
               List<String> befores = f.getBefores();
               if (befores != null && !befores.isEmpty())
               {
                   for (String b: befores)
                   {
                       //Fragment we're considering must be before this name
                       //Check that we are already before it, if not, move us so that we are.
                       //If the name does not exist in our list, then get it out of the no-other list
                       
                       if (!isBefore(list, name, b))
                       {
                           //b is not already before name, move it so that it is
                           int idx1 = list.indexOf(name);
                           int idx2 = list.indexOf(b);

                           //if b is not in the same list
                           if (idx2 < 0)
                           {
                               // must be in the noOthers list or it would have been an error
                               _noOthers.remove(b);

                               //If its in the no-others list, insert into this list so that we are before it
                               insert(list, idx1+1, b);
                               noChanges = false;
                           }
                           else
                           {
                               //b is in the same list but b is before name, so swap it around
                               list.remove(name);
                               insert(list, idx2, name);
                               noChanges = false;
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
                       //Check that name is after a, moving it if possible if its not
                       if (!isAfter(list, name, a))
                       {
                           //name is not after a, move it
                           int idx1 = list.indexOf(name);
                           int idx2 = list.indexOf(a);
                           
                           //if a is not in the same list as name
                           if (idx2 < 0)
                           {
                               //take it out of the noOthers list and put it in the right place in this list
                               _noOthers.remove(a);
                               insert(list,idx1, a);
                               noChanges = false;
                           }
                           else
                           {
                               //a is in the same list as name, but in the wrong place, so move it
                               list.remove(a);
                               insert(list,idx1, a);
                               noChanges = false;
                           }
                       }
                       //Name we're considering must be after this name
                       //Check we're already after it, if not, move us so that we are.
                       //If the name does not exist in our list, then get it out of the no-other list
                   }
               }
           }
 
           return noChanges;
       }
       
       /**
        * Is a before b?
        * @param list
        * @param a
        * @param b
        * @return
        */
       protected boolean isBefore (List<String> list, String a, String b)
       {
           //check if a and b are already in the same list, and b is already
           //before a 
           int idxa = list.indexOf(a);
           int idxb = list.indexOf(b);
           
           
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
                   if (_beforeOthers.contains(b))
                       throw new IllegalStateException("Incorrect relationship: "+a+" before "+b);
                   else
                       return false; //b could be moved to the list
               }
           }
          
           //a and b are in the same list and a is already before b
           return true;
       }
       
       
       /**
        * Is a after b?
        * @param list
        * @param a
        * @param b
        * @return
        */
       protected boolean isAfter(List<String> list, String a, String b)
       {
           int idxa = list.indexOf(a);
           int idxb = list.indexOf(b);
           
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
                   if (_afterOthers.contains(b))
                       throw new IllegalStateException("Incorrect relationship: "+b+" after "+a);
                   else
                       return false; //b could be moved from noOthers list
               }
           }

           return true; //a and b in the same list, a is after b
       }
       
       protected void insert(List<String> list, int index, String element)
       {
           if (index > list.size())
               list.add(element);
           else
               list.add(index, element);
       }
    }


    
    public WebXmlProcessor (WebAppContext context) throws ClassNotFoundException
    {
        _context = context;
        _parser = newParser();
    }
    
    public WebAppContext getContext()
    {
        return _context;
    }
    
    public XmlParser getParser()
    {
        return _parser;
    }
    
    public void parseDefaults (Resource webDefaults)
    throws Exception
    {
        _webDefaultsRoot =  new DefaultsDescriptor(webDefaults, this); 
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
    
    public void parseWebXml (Resource webXml)
    throws Exception
    {
        _webXmlRoot = new Descriptor(webXml, this);
        _webXmlRoot.parse();
        _webXmlRoot.processClassNames();
        if (_webXmlRoot.getMetaDataComplete() == Descriptor.MetaDataComplete.True)          
            _context.setAttribute(METADATA_COMPLETE, Boolean.TRUE);
        else
            _context.setAttribute(METADATA_COMPLETE, Boolean.FALSE);
        _context.getServletContext().setEffectiveMajorVersion(_webXmlRoot.getMajorVersion());
        _context.getServletContext().setEffectiveMinorVersion(_webXmlRoot.getMinorVersion());
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
    
    public void parseOverride (Resource override)
    throws Exception
    {
        _webOverrideRoot = new OverrideDescriptor(override, this);
        _webOverrideRoot.setValidating(false);
        _webOverrideRoot.parse();
        if (_webOverrideRoot.getMetaDataComplete() == Descriptor.MetaDataComplete.True)
            _context.setAttribute(METADATA_COMPLETE, Boolean.TRUE);
        else if (_webOverrideRoot.getMetaDataComplete() == Descriptor.MetaDataComplete.False)
            _context.setAttribute(METADATA_COMPLETE, Boolean.FALSE);  
        
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
    
    
    public void parseFragment (Resource fragment)
    throws Exception
    { 
        Boolean metaComplete = (Boolean)_context.getAttribute(METADATA_COMPLETE);
        if (metaComplete != null && metaComplete.booleanValue())
            return; //do not process anything else if web.xml/web-override.xml set metadata-complete
        
        //Metadata-complete is not set, or there is no web.xml
        Fragment frag = new Fragment(fragment, this);
        frag.parse();
        _webFragmentRoots.add(frag);
        if (frag.getName() != null)
            _webFragmentNameMap.put(frag.getName(), frag);

        //If web.xml has specified an absolute ordering, ignore any relative ordering in the fragment
        if (_ordering != null && _ordering.isAbsolute())
            return;
        
        if (frag.isOrdered())
        {
            if (_ordering == null)
                _ordering = new RelativeOrdering();

            switch (frag.getOtherType())
            {
                case None:
                {
                    ((RelativeOrdering)_ordering).addNoOthers(frag);
                    break;
                }
                case Before:
                { 
                    ((RelativeOrdering)_ordering).addBeforeOthers(frag);
                    break;
                }
                case After:
                {
                    ((RelativeOrdering)_ordering).addAfterOthers(frag);
                    break;
                }
            } 
        }
    }


    
    public void orderFragments ()
    {
        if (_ordering != null)
        {
            _orderedFragments = _ordering.order();
            
            List<String> orderedJars = new ArrayList<String>();
            for (Descriptor frag: _orderedFragments)
            {
                //get just the name of the jar file
                String fullname = frag.getResource().getName();
                int i = fullname.indexOf(".jar");          
                int j = fullname.lastIndexOf("/", i);
                orderedJars.add(fullname.substring(j+1,i+4));
            }
            _context.setAttribute(ServletContext.ORDERED_LIBS, orderedJars);
        }
        else
            _orderedFragments = _webFragmentRoots;
    }
    
    
    public void processFragments ()
    throws Exception
    {
        //Servlet Spec 3.0 p.74 says all descriptors must say distributable
        boolean distributable = ((_webDefaultsRoot != null && _webDefaultsRoot.isDistributable()) 
                                 || (_webXmlRoot != null && _webXmlRoot.isDistributable())
                                 || (_webOverrideRoot != null && _webOverrideRoot.isDistributable()));
        for (Fragment frag : _orderedFragments)
        {
            process(frag);       
            distributable = distributable && frag.isDistributable();
        }
        
        _context.setDistributable(distributable);
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
    
    public List<Fragment> getFragments ()
    {
        return _webFragmentRoots;
    }
    
    public List<Fragment> getOrderedFragments ()
    {
        return _orderedFragments;
    }
    
    public Ordering getOrdering()
    {
        return _ordering;
    }
    
    public void setOrdering (Ordering o)
    {
        _ordering = o;
    }
    
    public Fragment getFragment(String name)
    {
        return _webFragmentNameMap.get(name);
    }
    
    public Map<String,Fragment> getNamedFragments ()
    {
        return Collections.unmodifiableMap(_webFragmentNameMap);
    }
    
    
    /**
     * Convenience method. Process the standard elements of the web descriptor.
     * @param descriptor
     * @throws Exception
     */
    public void process (Descriptor descriptor)
    throws Exception
    {      
        if (descriptor != null)
        {
            initStandardDescriptorProcessor();
            process(descriptor, _standardDescriptorProcessor);
        }
    }
  
    
    
    /**
     * Process the elements of the Descriptor according to the
     * given DescriptorProcessor.
     * @param descriptor
     * @param processor
     * @throws Exception
     */
    public void process (Descriptor descriptor, DescriptorProcessor processor)
    throws Exception
    {
        if (descriptor != null && processor != null)
            processor.process(descriptor);
    }
    
    public Origin getOrigin (String name)
    {
        Descriptor d = _origins.get(name);
        if (d == null)
            return Origin.NotSet;
        if (d instanceof Fragment)
            return Origin.WebFragment;
        if (d instanceof OverrideDescriptor)
            return Origin.WebOverride;
        if (d instanceof DefaultsDescriptor)
            return Origin.WebDefaults;
        return Origin.WebXml;
    }
 
    public Descriptor getOriginDescriptor (String name)
    {
        return _origins.get(name);
    }
    
    public void setOrigin (String name, Descriptor d)
    {
        _origins.put(name, d);
    }
       
    public void initStandardDescriptorProcessor ()
    {
        if (_standardDescriptorProcessor == null)
            _standardDescriptorProcessor = new StandardDescriptorProcessor(this);
    }
}
