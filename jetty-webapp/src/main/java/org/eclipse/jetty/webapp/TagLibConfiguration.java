// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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
import java.util.Collection;
import java.util.EventListener;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.servlet.Servlet;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
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
 *
 */
public class TagLibConfiguration implements Configuration
{
    public static final String TLD_RESOURCES = "org.eclipse.jetty.tlds";
    
    
    public class TldProcessor
    {
        public static final String TAGLIB_PROCESSOR = "org.eclipse.jetty.tagLibProcessor";
        XmlParser _parser;
        WebAppContext _context;
        List<XmlParser.Node> _roots = new ArrayList<XmlParser.Node>();
        
        
        public TldProcessor (WebAppContext context)
        throws Exception
        {
            _context = context;
            createParser();
        }
        
        private void createParser ()
        throws Exception
        {
            // Create a TLD parser
            _parser = new XmlParser(false);
            
            URL taglib11=null;
            URL taglib12=null;
            URL taglib20=null;
            URL taglib21=null;

            try
            {
                Class jsp_page = Loader.loadClass(WebXmlConfiguration.class,"javax.servlet.jsp.JspPage");
                taglib11=jsp_page.getResource("javax/servlet/jsp/resources/web-jsptaglibrary_1_1.dtd");
                taglib12=jsp_page.getResource("javax/servlet/jsp/resources/web-jsptaglibrary_1_2.dtd");
                taglib20=jsp_page.getResource("javax/servlet/jsp/resources/web-jsptaglibrary_2_0.xsd");
                taglib21=jsp_page.getResource("javax/servlet/jsp/resources/web-jsptaglibrary_2_1.xsd");
            }
            catch(Exception e)
            {
                Log.ignore(e);
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
                _parser.redirectEntity("web-jsptaglib_1_1.dtd",taglib11);
                _parser.redirectEntity("web-jsptaglibrary_1_1.dtd",taglib11);
            }
            if(taglib12!=null)
            {
                _parser.redirectEntity("web-jsptaglib_1_2.dtd",taglib12);
                _parser.redirectEntity("web-jsptaglibrary_1_2.dtd",taglib12);
            }
            if(taglib20!=null)
            {
                _parser.redirectEntity("web-jsptaglib_2_0.xsd",taglib20);
                _parser.redirectEntity("web-jsptaglibrary_2_0.xsd",taglib20);
            }
            if(taglib21!=null)
            {
                _parser.redirectEntity("web-jsptaglib_2_1.xsd",taglib21);
                _parser.redirectEntity("web-jsptaglibrary_2_1.xsd",taglib21);
            }
            
            _parser.setXpath("/taglib/listener/listener-class");
        }
        
        
        public XmlParser.Node parse (Resource tld)
        throws Exception
        {
            XmlParser.Node root;
            try
            {
                //xerces on apple appears to sometimes close the zip file instead
                //of the inputstream, so try opening the input stream, but if
                //that doesn't work, fallback to opening a new url
                root = _parser.parse(tld.getInputStream());
            }
            catch (Exception e)
            {
                root = _parser.parse(tld.getURL().toString());
            }

            if (root==null)
            {
                Log.warn("No TLD root in {}",tld);
            }
            else
                _roots.add(root);
            
            return root;
        }
        
        public void processRoots ()
        {
            for (XmlParser.Node root: _roots)
                process(root);
        }
        
        public void process (XmlParser.Node root)
        {     
            for (int i=0;i<root.size();i++)
            {
                Object o=root.get(i);
                if (o instanceof XmlParser.Node)
                {
                    XmlParser.Node node = (XmlParser.Node)o;
                    if ("listener".equals(node.getTag()))
                    {
                        String className=node.getString("listener-class",false,true);
                        if (Log.isDebugEnabled()) Log.debug("listener="+className);
                        
                        try
                        {
                            Class listenerClass = _context.loadClass(className);
                            EventListener l = (EventListener)listenerClass.newInstance();
                            _context.addEventListener(l);
                        }
                        catch(Exception e)
                        {
                            Log.warn("Could not instantiate listener "+className+": "+e);
                            Log.debug(e);
                        }
                        catch(Error e)
                        {
                            Log.warn("Could not instantiate listener "+className+": "+e);
                            Log.debug(e);
                        }
                    }
                }
            }
        }
        
    }


    public void preConfigure(WebAppContext context) throws Exception
    {
        Set tlds = new HashSet();

        // Find tld's from web.xml
        // When web.xml was processed, it should have created aliases for all TLDs.  So search resources aliases
        // for aliases ending in tld
        if (context.getResourceAliases()!=null && 
                context.getBaseResource()!=null && 
                context.getBaseResource().exists())
        {
            Iterator iter=context.getResourceAliases().values().iterator();
            while(iter.hasNext())
            {
                String location = (String)iter.next();
                if (location!=null && location.toLowerCase().endsWith(".tld"))
                {
                    if (!location.startsWith("/"))
                        location="/WEB-INF/"+location;
                    Resource l=context.getBaseResource().addPath(location);
                    tlds.add(l);
                }
            }
        }
        
        // Look for any tlds in WEB-INF directly.
        Resource web_inf = context.getWebInf();
        if (web_inf!=null)
        {
            String[] contents = web_inf.list();
            for (int i=0;contents!=null && i<contents.length;i++)
            {
                if (contents[i]!=null && contents[i].toLowerCase().endsWith(".tld"))
                {
                    Resource l=web_inf.addPath(contents[i]);
                    tlds.add(l);
                }
            }
        }
        
    
        // Add in tlds found in META-INF of jars. The jars that will be scanned are controlled by
        // the patterns defined in the context attributes: org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern,
        // and org.eclipse.jetty.server.webapp.WebInfIncludeJarPattern
        Collection<Resource> tld_resources=(Collection<Resource>)context.getAttribute(TLD_RESOURCES);
        if (tld_resources!=null)
            tlds.addAll(tld_resources);
        
        // Create a processor for the tlds and save it
        TldProcessor processor = new TldProcessor (context);
        context.setAttribute(TldProcessor.TAGLIB_PROCESSOR, processor);
        
        // Parse the tlds into memory
        Resource tld = null;
        Iterator iter = tlds.iterator();
        while (iter.hasNext())
        {
            try
            {
                tld = (Resource)iter.next();
                if (Log.isDebugEnabled()) Log.debug("TLD="+tld);
                processor.parse(tld);
            }
            catch(Exception e)
            {
                Log.warn("Unable to parse TLD: " + tld,e);
            }
        }
    }
    

    public void configure (WebAppContext context) throws Exception
    {         
        TldProcessor processor = (TldProcessor)context.getAttribute(TldProcessor.TAGLIB_PROCESSOR); 
        if (processor == null)
        {
            Log.warn("No TldProcessor configured, skipping tld processing");
            return;
        }

        //Create listeners from the parsed tld trees
        processor.processRoots();
    }

    public void postConfigure(WebAppContext context) throws Exception
    {
        context.setAttribute(TldProcessor.TAGLIB_PROCESSOR, null);
    }

    public void deconfigure(WebAppContext context) throws Exception
    {
        
    }

}
