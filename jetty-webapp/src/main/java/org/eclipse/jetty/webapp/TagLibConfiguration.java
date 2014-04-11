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

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.Servlet;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlParser;

/* ------------------------------------------------------------ */
/** TagLibConfiguration.
 * 
 * The class searches for TLD descriptors found in web.xml, in WEB-INF/*.tld files of the web app
 * or *.tld files within jars found in WEB-INF/lib of the webapp.   Any listeners defined in these
 * tld's are added to the context.
 * 
 * &lt;bile&gt;This is total rubbish special case for JSPs! If there was a general use-case for web app
 * frameworks to register listeners directly, then a generic mechanism could have been added to the servlet
 * spec.  Instead some special purpose JSP support is required that breaks all sorts of encapsulation rules as
 * the servlet container must go searching for and then parsing the descriptors for one particular framework.
 * It only appears to be used by JSF, which is being developed by the same developer who implemented this
 * feature in the first place!
 * &lt;/bile&gt;
 * 
 * 
 * Note- this has been superceded by the new TldScanner in jasper which uses ServletContainerInitializer to
 * find all the listeners in tag libs and register them.
 */
public class TagLibConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = Log.getLogger(TagLibConfiguration.class);

    public static final String TLD_RESOURCES = "org.eclipse.jetty.tlds";
    
  
    /**
     * TagLibListener
     *
     * A listener that does the job of finding .tld files that contain
     * (other) listeners that need to be called by the servlet container.
     * 
     * This implementation is necessitated by the fact that it is only
     * after all the Configuration classes have run that we will
     * parse web.xml/fragments etc and thus find tlds mentioned therein.
     * 
     * Note: TagLibConfiguration is not used in jetty-8 as jasper (JSP engine)
     * uses the new TldScanner class - a ServletContainerInitializer from
     * Servlet Spec 3 - to find all listeners in taglibs and register them
     * with the servlet container.
     */
    public  class TagLibListener implements ServletContextListener {
        private List<EventListener> _tldListeners;
        private WebAppContext _context;       
        
        public TagLibListener (WebAppContext context) {
            _context = context;
        }

        public void contextDestroyed(ServletContextEvent sce)
        {
            if (_tldListeners == null)
                return;
            
            for (int i=_tldListeners.size()-1; i>=0; i--) {
                EventListener l = _tldListeners.get(i);
                if (l instanceof ServletContextListener) {
                    ((ServletContextListener)l).contextDestroyed(sce);
                }
            }
        }

        public void contextInitialized(ServletContextEvent sce)
        {
            try 
            {
                //For jasper 2.1: 
                //Get the system classpath tlds and tell jasper about them, if jasper is on the classpath
                try
                {

                    ClassLoader loader = _context.getClassLoader();
                    if (loader == null || loader.getParent() == null)
                        loader = getClass().getClassLoader();
                    else
                        loader = loader.getParent();
                    Class<?> clazz = loader.loadClass("org.apache.jasper.compiler.TldLocationsCache");
                    assert clazz!=null;
                    Collection<Resource> tld_resources = (Collection<Resource>)_context.getAttribute(TLD_RESOURCES);
                   
                    Map<URI, List<String>> tldMap = new HashMap<URI, List<String>>();
                    
                    if (tld_resources != null)
                    {
                        //get the jar file names of the files
                        for (Resource r:tld_resources)
                        {
                            Resource jarResource = extractJarResource(r);
                            //jasper is happy with an empty list of tlds
                            if (!tldMap.containsKey(jarResource.getURI()))
                                tldMap.put(jarResource.getURI(), null);

                        }
                        //set the magic context attribute that tells jasper about the system tlds
                        sce.getServletContext().setAttribute("com.sun.appserv.tld.map", tldMap);
                    }
                }
                catch (ClassNotFoundException e)
                {
                    LOG.ignore(e);
                }
               
                //find the tld files and parse them to get out their
                //listeners
                Set<Resource> tlds = findTldResources();
                List<TldDescriptor> descriptors = parseTlds(tlds);
                processTlds(descriptors);
                
                if (_tldListeners == null)
                    return;
                
                //call the listeners that are ServletContextListeners, put the
                //rest into the context's list of listeners to call at the appropriate
                //moment
                for (EventListener l:_tldListeners) {
                    if (l instanceof ServletContextListener) {
                        ((ServletContextListener)l).contextInitialized(sce);
                    } else {
                        _context.addEventListener(l);
                    }
                }
                
            } 
            catch (Exception e) {
                LOG.warn(e);
            }
        }


        
        
        private Resource extractJarResource (Resource r)
        {
            if (r == null)
                return null;
            
            try
            {
                String url = r.getURI().toURL().toString();
                int idx = url.lastIndexOf("!/");
                if (idx >= 0)
                    url = url.substring(0, idx);
                if (url.startsWith("jar:"))
                    url = url.substring(4);
                return Resource.newResource(url);
            }
            catch (IOException e)
            {
                LOG.warn(e);
                return null;
            }
        }
    
        /**
         * Find all the locations that can harbour tld files that may contain
         * a listener which the web container is supposed to instantiate and
         * call.
         * 
         * @return
         * @throws IOException
         */
        private Set<Resource> findTldResources () throws IOException {
            
            Set<Resource> tlds = new HashSet<Resource>();
            
            // Find tld's from web.xml
            // When web.xml was processed, it should have created aliases for all TLDs.  So search resources aliases
            // for aliases ending in tld
            if (_context.getResourceAliases()!=null && 
                    _context.getBaseResource()!=null && 
                    _context.getBaseResource().exists())
            {
                Iterator<String> iter=_context.getResourceAliases().values().iterator();
                while(iter.hasNext())
                {
                    String location = iter.next();
                    if (location!=null && location.toLowerCase(Locale.ENGLISH).endsWith(".tld"))
                    {
                        if (!location.startsWith("/"))
                            location="/WEB-INF/"+location;
                        Resource l=_context.getBaseResource().addPath(location);
                        tlds.add(l);
                    }
                }
            }
            
            // Look for any tlds in WEB-INF directly.
            Resource web_inf = _context.getWebInf();
            if (web_inf!=null)
            {
                String[] contents = web_inf.list();
                for (int i=0;contents!=null && i<contents.length;i++)
                {
                    if (contents[i]!=null && contents[i].toLowerCase(Locale.ENGLISH).endsWith(".tld"))
                    {
                        Resource l=web_inf.addPath(contents[i]);
                        tlds.add(l);
                    }
                }
            }
            
            //Look for tlds in common location of WEB-INF/tlds
            if (web_inf != null) {
                Resource web_inf_tlds = _context.getWebInf().addPath("/tlds/");
                if (web_inf_tlds.exists() && web_inf_tlds.isDirectory()) {
                    String[] contents = web_inf_tlds.list();
                    for (int i=0;contents!=null && i<contents.length;i++)
                    {
                        if (contents[i]!=null && contents[i].toLowerCase(Locale.ENGLISH).endsWith(".tld"))
                        {
                            Resource l=web_inf_tlds.addPath(contents[i]);
                            tlds.add(l);
                        }
                    }
                } 
            }

            // Add in tlds found in META-INF of jars. The jars that will be scanned are controlled by
            // the patterns defined in the context attributes: org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern,
            // and org.eclipse.jetty.server.webapp.WebInfIncludeJarPattern
            @SuppressWarnings("unchecked")
            Collection<Resource> tld_resources=(Collection<Resource>)_context.getAttribute(TLD_RESOURCES);
            if (tld_resources!=null)
                tlds.addAll(tld_resources);
            
            return tlds;
        }
        
        
        /**
         * Parse xml into in-memory tree
         * @param tlds
         * @return
         */
        private List<TldDescriptor> parseTlds (Set<Resource> tlds) {         
            List<TldDescriptor> descriptors = new ArrayList<TldDescriptor>();
            
            Resource tld = null;
            Iterator<Resource> iter = tlds.iterator();
            while (iter.hasNext())
            {
                try
                {
                    tld = iter.next();
                    if (LOG.isDebugEnabled()) LOG.debug("TLD="+tld);
                   
                    TldDescriptor d = new TldDescriptor(tld);
                    d.parse();
                    descriptors.add(d);
                }
                catch(Exception e)
                {
                    LOG.warn("Unable to parse TLD: " + tld,e);
                }
            }
            return descriptors;
        }
        
        
        /**
         * Create listeners from the parsed tld trees
         * @param descriptors
         * @throws Exception
         */
        private void processTlds (List<TldDescriptor> descriptors) throws Exception {

            TldProcessor processor = new TldProcessor();
            for (TldDescriptor d:descriptors)
                processor.process(_context, d); 
            
            _tldListeners = new ArrayList<EventListener>(processor.getListeners());
        }
    }
    
    
    
    
    /**
     * TldDescriptor
     *
     *
     */
    public static class TldDescriptor extends Descriptor
    {
        protected static XmlParser __parserSingleton;

        public TldDescriptor(Resource xml)
        {
            super(xml);
        }

        @Override
        public void ensureParser() throws ClassNotFoundException
        {
           if (__parserSingleton == null)
               __parserSingleton = newParser();
            _parser = __parserSingleton;
        }

        @Override
        public XmlParser newParser() throws ClassNotFoundException
        {
            // Create a TLD parser
            XmlParser parser = new XmlParser(false);
            
            URL taglib11=null;
            URL taglib12=null;
            URL taglib20=null;
            URL taglib21=null;

            try
            {
                Class<?> jsp_page = Loader.loadClass(WebXmlConfiguration.class,"javax.servlet.jsp.JspPage");
                taglib11=jsp_page.getResource("javax/servlet/jsp/resources/web-jsptaglibrary_1_1.dtd");
                taglib12=jsp_page.getResource("javax/servlet/jsp/resources/web-jsptaglibrary_1_2.dtd");
                taglib20=jsp_page.getResource("javax/servlet/jsp/resources/web-jsptaglibrary_2_0.xsd");
                taglib21=jsp_page.getResource("javax/servlet/jsp/resources/web-jsptaglibrary_2_1.xsd");
            }
            catch(Exception e)
            {
                LOG.ignore(e);
            }
            finally
            {
                if(taglib11==null)
                    taglib11=Loader.getResource(Servlet.class,"javax/servlet/jsp/resources/web-jsptaglibrary_1_1.dtd",true);
                if(taglib12==null)
                    taglib12=Loader.getResource(Servlet.class,"javax/servlet/jsp/resources/web-jsptaglibrary_1_2.dtd",true);
                if(taglib20==null)
                    taglib20=Loader.getResource(Servlet.class,"javax/servlet/jsp/resources/web-jsptaglibrary_2_0.xsd",true);
                if(taglib21==null)
                    taglib21=Loader.getResource(Servlet.class,"javax/servlet/jsp/resources/web-jsptaglibrary_2_1.xsd",true);
            }
            

            if(taglib11!=null)
            {
                redirect(parser, "web-jsptaglib_1_1.dtd",taglib11);  
                redirect(parser, "web-jsptaglibrary_1_1.dtd",taglib11);
            }
            if(taglib12!=null)
            {
                redirect(parser, "web-jsptaglib_1_2.dtd",taglib12);
                redirect(parser, "web-jsptaglibrary_1_2.dtd",taglib12);
            }
            if(taglib20!=null)
            {
                redirect(parser, "web-jsptaglib_2_0.xsd",taglib20);
                redirect(parser, "web-jsptaglibrary_2_0.xsd",taglib20);
            }
            if(taglib21!=null)
            {
                redirect(parser, "web-jsptaglib_2_1.xsd",taglib21);
                redirect(parser, "web-jsptaglibrary_2_1.xsd",taglib21);
            }
            
            parser.setXpath("/taglib/listener/listener-class");
            return parser;
        }
        
        public void parse ()
        throws Exception
        {
            ensureParser();
            try
            {
                //xerces on apple appears to sometimes close the zip file instead
                //of the inputstream, so try opening the input stream, but if
                //that doesn't work, fallback to opening a new url
                _root = _parser.parse(_xml.getInputStream());
            }
            catch (Exception e)
            {
                _root = _parser.parse(_xml.getURL().toString());
            }

            if (_root==null)
            {
                LOG.warn("No TLD root in {}",_xml);
            }
        }
    }
    
    
    /**
     * TldProcessor
     *
     * Process TldDescriptors representing tag libs to find listeners.
     */
    public class TldProcessor extends IterativeDescriptorProcessor
    {
        public static final String TAGLIB_PROCESSOR = "org.eclipse.jetty.tagLibProcessor";
        XmlParser _parser;
        List<XmlParser.Node> _roots = new ArrayList<XmlParser.Node>();
        List<EventListener> _listeners;
        
        
        public TldProcessor ()
        throws Exception
        {  
            _listeners = new ArrayList<EventListener>();
            registerVisitor("listener", this.getClass().getDeclaredMethod("visitListener", __signature));
        }
      

        public void visitListener (WebAppContext context, Descriptor descriptor, XmlParser.Node node)
        {     
            String className=node.getString("listener-class",false,true);
            if (LOG.isDebugEnabled()) 
                LOG.debug("listener="+className);

            try
            {
                Class<?> listenerClass = context.loadClass(className);
                EventListener l = (EventListener)listenerClass.newInstance();
                _listeners.add(l);
            }
            catch(Exception e)
            {
                LOG.warn("Could not instantiate listener "+className+": "+e);
                LOG.debug(e);
            }
            catch(Error e)
            {
                LOG.warn("Could not instantiate listener "+className+": "+e);
                LOG.debug(e);
            }

        }

        @Override
        public void end(WebAppContext context, Descriptor descriptor)
        {
        }

        @Override
        public void start(WebAppContext context, Descriptor descriptor)
        {  
        }
        
        public List<EventListener> getListeners() {
            return _listeners;
        }
    }


    @Override
    public void preConfigure(WebAppContext context) throws Exception
    {
        try
        {
            Class<?> jsp_page = Loader.loadClass(WebXmlConfiguration.class,"javax.servlet.jsp.JspPage");
        }
        catch (Exception e)
        {
            //no jsp available, don't parse TLDs
            return;
        }

        TagLibListener tagLibListener = new TagLibListener(context);
        context.addEventListener(tagLibListener);
    }
    

    @Override
    public void configure (WebAppContext context) throws Exception
    {         
    }

    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {     
    }


    @Override
    public void cloneConfigure(WebAppContext template, WebAppContext context) throws Exception
    {
    }


    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
    } 
}
