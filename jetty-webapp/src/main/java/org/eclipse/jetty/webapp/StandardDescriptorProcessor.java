// ========================================================================
// Copyright (c) 2006-2010 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.server.DispatcherType;
import javax.servlet.ServletException;
import org.eclipse.jetty.servlet.api.ServletRegistration;


import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.Holder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.MetaData.Origin;
import org.eclipse.jetty.xml.XmlParser;

/**
 * StandardDescriptorProcessor
 *
 *
 */
public class StandardDescriptorProcessor extends IterativeDescriptorProcessor
{
    public static final String STANDARD_PROCESSOR = "org.eclipse.jetty.standardDescriptorProcessor";
    protected WebAppContext _context;
    
    //the shared configuration operated on by web-default.xml, web.xml, web-override.xml and all web-fragment.xml
    protected ServletHandler _servletHandler;
    protected SecurityHandler _securityHandler;
    protected Object _filters; 
    protected Object _filterMappings;
    protected Object _servlets;
    protected Object _servletMappings;
    protected Object _listeners;
    protected Object _listenerClassNames;
    protected Object _welcomeFiles;
    protected Set<String> _roles = new HashSet<String>();
    protected List<ConstraintMapping> _constraintMappings = new ArrayList<ConstraintMapping>();
    protected Map _errorPages;
    protected boolean _hasJSP;
    protected String _jspServletName;
    protected String _jspServletClass;
    protected boolean _defaultWelcomeFileList;
    protected MetaData _metaData;
   
    
    
    public StandardDescriptorProcessor ()
    {
 
        try
        {
            registerVisitor("context-param", this.getClass().getDeclaredMethod("visitContextParam", __signature));
            registerVisitor("display-name", this.getClass().getDeclaredMethod("visitDisplayName", __signature));
            registerVisitor("servlet", this.getClass().getDeclaredMethod("visitServlet",  __signature));
            registerVisitor("servlet-mapping", this.getClass().getDeclaredMethod("visitServletMapping",  __signature));
            registerVisitor("session-config", this.getClass().getDeclaredMethod("visitSessionConfig",  __signature));
            registerVisitor("mime-mapping", this.getClass().getDeclaredMethod("visitMimeMapping",  __signature)); 
            registerVisitor("welcome-file-list", this.getClass().getDeclaredMethod("visitWelcomeFileList",  __signature));
            registerVisitor("locale-encoding-mapping-list", this.getClass().getDeclaredMethod("visitLocaleEncodingList",  __signature));
            registerVisitor("error-page", this.getClass().getDeclaredMethod("visitErrorPage",  __signature));
            registerVisitor("taglib", this.getClass().getDeclaredMethod("visitTagLib",  __signature));
            registerVisitor("jsp-config", this.getClass().getDeclaredMethod("visitJspConfig",  __signature));
            registerVisitor("security-constraint", this.getClass().getDeclaredMethod("visitSecurityConstraint",  __signature));
            registerVisitor("login-config", this.getClass().getDeclaredMethod("visitLoginConfig",  __signature));
            registerVisitor("security-role", this.getClass().getDeclaredMethod("visitSecurityRole",  __signature));
            registerVisitor("filter", this.getClass().getDeclaredMethod("visitFilter",  __signature));
            registerVisitor("filter-mapping", this.getClass().getDeclaredMethod("visitFilterMapping",  __signature));
            registerVisitor("listener", this.getClass().getDeclaredMethod("visitListener",  __signature));
            registerVisitor("distributable", this.getClass().getDeclaredMethod("visitDistributable",  __signature));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    
    
    /** 
     * @see org.eclipse.jetty.webapp.IterativeDescriptorProcessor#start()
     */
    public void start(Descriptor descriptor)
    {
        _metaData = descriptor.getMetaData();
        _context = _metaData.getContext();
        
        //Get the current objects from the context
        _servletHandler = _context.getServletHandler();
        _securityHandler = (SecurityHandler)_context.getSecurityHandler();
        _filters = LazyList.array2List(_servletHandler.getFilters());
        _filterMappings = LazyList.array2List(_servletHandler.getFilterMappings());
        _servlets = LazyList.array2List(_servletHandler.getServlets());
        _servletMappings = LazyList.array2List(_servletHandler.getServletMappings());
        _listeners = LazyList.array2List(_context.getEventListeners());
        _welcomeFiles = LazyList.array2List(_context.getWelcomeFiles());
        if (_securityHandler instanceof ConstraintAware)
        {
             _constraintMappings.addAll(((ConstraintAware) _securityHandler).getConstraintMappings());            
            if (((ConstraintAware) _securityHandler).getRoles() != null)
            {
                _roles.addAll(((ConstraintAware) _securityHandler).getRoles());
            }
        }
       _errorPages = _context.getErrorHandler() instanceof ErrorPageErrorHandler ? ((ErrorPageErrorHandler)_context.getErrorHandler()).getErrorPages() : null;    
    }
    
    
    
    /** 
     * @see org.eclipse.jetty.webapp.IterativeDescriptorProcessor#end()
     */
    public void end(Descriptor descriptor)
    {
        //Set the context with the results of the processing
        _servletHandler.setFilters((FilterHolder[]) LazyList.toArray(_filters, FilterHolder.class));
        _servletHandler.setFilterMappings((FilterMapping[]) LazyList.toArray(_filterMappings, FilterMapping.class));
        _servletHandler.setServlets((ServletHolder[]) LazyList.toArray(_servlets, ServletHolder.class));
        _servletHandler.setServletMappings((ServletMapping[]) LazyList.toArray(_servletMappings, ServletMapping.class));
        _context.setEventListeners((EventListener[]) LazyList.toArray(_listeners, EventListener.class));
        _context.setWelcomeFiles((String[]) LazyList.toArray(_welcomeFiles, String.class));
        // TODO jaspi check this
        if (_securityHandler instanceof ConstraintAware)
        {
            for (ConstraintMapping m:_constraintMappings)
                ((ConstraintAware) _securityHandler).addConstraintMapping(m);
            for (String r:_roles)
                ((ConstraintAware) _securityHandler).addRole(r);
        }

        if (_errorPages != null && _context.getErrorHandler() instanceof ErrorPageErrorHandler)
            ((ErrorPageErrorHandler)_context.getErrorHandler()).setErrorPages(_errorPages);
        
        _roles.clear();
        _constraintMappings.clear();
        _metaData = null;
        _context = null;
    }
    
    public void visitContextParam (Descriptor descriptor, XmlParser.Node node)
    {
        String name = node.getString("param-name", false, true);
        String value = node.getString("param-value", false, true);
        Origin o = _metaData.getOrigin("context-param."+name);
        switch (o)
        {
            case NotSet:
            {
                //just set it
                _context.getInitParams().put(name, value);
                _metaData.setOrigin("context-param."+name, descriptor);
                break;
            }
            case WebXml:
            case WebDefaults:
            case WebOverride:
            {
                //previously set by a web xml, allow other web xml files to override
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    _context.getInitParams().put(name, value);
                    _metaData.setOrigin("context-param."+name, descriptor); 
                }
                break;
            }
            case WebFragment:
            {
                //previously set by a web-fragment, this fragment's value must be the same
                if (descriptor instanceof FragmentDescriptor)
                {
                    if (!((String)_context.getInitParams().get(name)).equals(value))
                        throw new IllegalStateException("Conflicting context-param "+name+"="+value+" in "+descriptor.getResource());
                }
                break;
            }
        }
        if (Log.isDebugEnabled()) Log.debug("ContextParam: " + name + "=" + value);

    }
    

    /* ------------------------------------------------------------ */
    protected void visitDisplayName(Descriptor descriptor, XmlParser.Node node)
    {
        //Servlet Spec 3.0 p. 74 Ignore from web-fragments
        if (!(descriptor instanceof FragmentDescriptor))
        {
            _context.setDisplayName(node.toString(false, true));
            _metaData.setOrigin("display-name", descriptor);
        }
    }
    
    protected void visitServlet(Descriptor descriptor, XmlParser.Node node)
    {
        String id = node.getAttribute("id");

        // initialize holder
        String servlet_name = node.getString("servlet-name", false, true);
        ServletHolder holder = _servletHandler.getServlet(servlet_name);
          
        /*
         * If servlet of that name does not already exist, create it.
         */
        if (holder == null)
        {
            holder = _servletHandler.newServletHolder();
            holder.setName(servlet_name);
            _servlets = LazyList.add(_servlets, holder);
        }
        ServletRegistration.Dynamic registration = holder.getRegistration();

        // init params  
        Iterator iParamsIter = node.iterator("init-param");
        while (iParamsIter.hasNext())
        {
            XmlParser.Node paramNode = (XmlParser.Node) iParamsIter.next();
            String pname = paramNode.getString("param-name", false, true);
            String pvalue = paramNode.getString("param-value", false, true);
            
            Origin origin = _metaData.getOrigin(servlet_name+".servlet.init-param."+pname);
            
            switch (origin)
            {
                case NotSet:
                {
                    //init-param not already set, so set it
                    
                    registration.setInitParameter(pname, pvalue); 
                    _metaData.setOrigin(servlet_name+".servlet.init-param."+pname, descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //previously set by a web xml descriptor, if we're parsing another web xml descriptor allow override
                    //otherwise just ignore it
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        registration.setInitParameter(pname, pvalue); 
                        _metaData.setOrigin(servlet_name+".servlet.init-param."+pname, descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //previously set by a web-fragment, make sure that the value matches, otherwise its an error
                    if (!registration.getInitParameter(pname).equals(pvalue))
                        throw new IllegalStateException("Mismatching init-param "+pname+"="+pvalue+" in "+descriptor.getResource());
                    break;
                }
            }  
        }

        String servlet_class = node.getString("servlet-class", false, true);

        // Handle JSP
        if (id != null && id.equals("jsp"))
        {
            _jspServletName = servlet_name;
            _jspServletClass = servlet_class;
            try
            {
                Loader.loadClass(this.getClass(), servlet_class);
                _hasJSP = true;
            }
            catch (ClassNotFoundException e)
            {
                Log.info("NO JSP Support for {}, did not find {}", _context.getContextPath(), servlet_class);
                _hasJSP = false;
                _jspServletClass = servlet_class = "org.eclipse.jetty.servlet.NoJspServlet";
            }
            if (registration.getInitParameter("scratchdir") == null)
            {
                File tmp = _context.getTempDirectory();
                File scratch = new File(tmp, "jsp");
                if (!scratch.exists()) scratch.mkdir();
                registration.setInitParameter("scratchdir", scratch.getAbsolutePath());

                if ("?".equals(registration.getInitParameter("classpath")))
                {
                    String classpath = _context.getClassPath();
                    Log.debug("classpath=" + classpath);
                    if (classpath != null) 
                        registration.setInitParameter("classpath", classpath);
                }
            }

            /* Set the webapp's classpath for Jasper */
            _context.setAttribute("org.apache.catalina.jsp_classpath", _context.getClassPath());
            /* Set the system classpath for Jasper */
            registration.setInitParameter("com.sun.appserv.jsp.classpath", getSystemClassPath());        
        }
        
        //Set the servlet-class
        if (servlet_class != null) 
        {
            descriptor.addClassName(servlet_class);
            
            Origin o = _metaData.getOrigin(servlet_name+".servlet.servlet-class");
            switch (o)
            {
                case NotSet:
                {
                    //the class of the servlet has not previously been set, so set it
                    holder.setClassName(servlet_class);
                    _metaData.setOrigin(servlet_name+".servlet.servlet-class", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //the class of the servlet was set by a web xml file, only allow web-override/web-default to change it
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        holder.setClassName(servlet_class);
                        _metaData.setOrigin(servlet_name+".servlet.servlet-class", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //the class was set by another fragment, ensure this fragment's value is the same
                    if (!servlet_class.equals(holder.getClassName()))
                        throw new IllegalStateException("Conflicting servlet-class "+servlet_class+" in "+descriptor.getResource());
                    break;
                }
            }          
        }

        // Handler JSP file
        String jsp_file = node.getString("jsp-file", false, true);
        if (jsp_file != null)
        {
            holder.setForcedPath(jsp_file);
            holder.setClassName(_jspServletClass);
        }

        // handle load-on-startup 
        XmlParser.Node startup = node.get("load-on-startup");
        if (startup != null)
        {
            String s = startup.toString(false, true).toLowerCase();
            int order = 0;
            if (s.startsWith("t"))
            {
                Log.warn("Deprecated boolean load-on-startup.  Please use integer");
                order = 1; 
            }
            else
            {
                try
                {
                    if (s != null && s.trim().length() > 0) order = Integer.parseInt(s);
                }
                catch (Exception e)
                {
                    Log.warn("Cannot parse load-on-startup " + s + ". Please use integer");
                    Log.ignore(e);
                }
            }

            Origin o = _metaData.getOrigin(servlet_name+".servlet.load-on-startup");
            switch (o)
            {
                case NotSet:
                {
                    //not already set, so set it now
                    registration.setLoadOnStartup(order);
                    _metaData.setOrigin(servlet_name+".servlet.load-on-startup", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //if it was already set by a web xml descriptor and we're parsing another web xml descriptor, then override it
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        registration.setLoadOnStartup(order);
                        _metaData.setOrigin(servlet_name+".servlet.load-on-startup", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //it was already set by another fragment, if we're parsing a fragment, the values must match
                    if (order != holder.getInitOrder())
                        throw new IllegalStateException("Conflicting load-on-startup value in "+descriptor.getResource());
                    break;
                }
            } 
        }

        Iterator sRefsIter = node.iterator("security-role-ref");
        while (sRefsIter.hasNext())
        {
            XmlParser.Node securityRef = (XmlParser.Node) sRefsIter.next();
            String roleName = securityRef.getString("role-name", false, true);
            String roleLink = securityRef.getString("role-link", false, true);
            if (roleName != null && roleName.length() > 0 && roleLink != null && roleLink.length() > 0)
            {
                if (Log.isDebugEnabled()) Log.debug("link role " + roleName + " to " + roleLink + " for " + this);
                Origin o = _metaData.getOrigin(servlet_name+".servlet.role-name."+roleName);
                switch (o)
                {
                    case NotSet:
                    {
                        //set it
                        holder.setUserRoleLink(roleName, roleLink);
                        _metaData.setOrigin(servlet_name+".servlet.role-name."+roleName, descriptor);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {
                        //only another web xml descriptor (web-default,web-override web.xml) can override an already set value
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            holder.setUserRoleLink(roleName, roleLink);
                            _metaData.setOrigin(servlet_name+".servlet.role-name."+roleName, descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        if (!holder.getUserRoleLink(roleName).equals(roleLink))
                            throw new IllegalStateException("Conflicting role-link for role-name "+roleName+" for servlet "+servlet_name+" in "+descriptor.getResource());
                        break;
                    }
                }
            }
            else
            {
                Log.warn("Ignored invalid security-role-ref element: " + "servlet-name=" + holder.getName() + ", " + securityRef);
            }
        }

        
        XmlParser.Node run_as = node.get("run-as");
        if (run_as != null)
        { 
            String roleName = run_as.getString("role-name", false, true);

            if (roleName != null)
            {
                Origin o = _metaData.getOrigin(servlet_name+".servlet.run-as");
                switch (o)
                {
                    case NotSet:
                    {
                        //run-as not set, so set it
                        registration.setRunAsRole(roleName);
                        _metaData.setOrigin(servlet_name+".servlet.run-as", descriptor);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {
                        //run-as was set by a web xml, only allow it to be changed if we're currently parsing another web xml(override/default)
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            registration.setRunAsRole(roleName);
                            _metaData.setOrigin(servlet_name+".servlet.run-as", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //run-as was set by another fragment, this fragment must show the same value
                        if (!registration.getRunAsRole().equals(roleName))
                            throw new IllegalStateException("Conflicting run-as role "+roleName+" for servlet "+servlet_name+" in "+descriptor.getResource());
                        break;
                    }    
                }
            }
        }
    }

    protected void visitServletMapping(Descriptor descriptor, XmlParser.Node node)
    {
        //Servlet Spec 3.0, p74
        //servlet-mappings are always additive, whether from web xml descriptors (web.xml/web-default.xml/web-override.xml) or web-fragments.
        String servlet_name = node.getString("servlet-name", false, true);
        ServletMapping mapping = new ServletMapping();
        mapping.setServletName(servlet_name);

        if (_metaData.getOrigin(servlet_name+".servlet.mappings") == Origin.NotSet)
            _metaData.setOrigin(servlet_name+".servlet.mappings", descriptor);
        
        ArrayList paths = new ArrayList();
        Iterator iter = node.iterator("url-pattern");
        while (iter.hasNext())
        {
            String p = ((XmlParser.Node) iter.next()).toString(false, true);
            p = normalizePattern(p);
            paths.add(p);
        }
        mapping.setPathSpecs((String[]) paths.toArray(new String[paths.size()]));
        _servletMappings = LazyList.add(_servletMappings, mapping);
    }
    
    
    protected void visitSessionConfig(Descriptor descriptor, XmlParser.Node node)
    {
        XmlParser.Node tNode = node.get("session-timeout");
        if (tNode != null)
        {
            int timeout = Integer.parseInt(tNode.toString(false, true));
            _context.getSessionHandler().getSessionManager().setMaxInactiveInterval(timeout * 60);
        }
    }
    
    protected void visitMimeMapping(Descriptor descriptor, XmlParser.Node node)
    {
        String extension = node.getString("extension", false, true);
        if (extension != null && extension.startsWith(".")) 
            extension = extension.substring(1);
        String mimeType = node.getString("mime-type", false, true);
        if (extension != null)
        {
            Origin o = _metaData.getOrigin("extension."+extension);
            switch (o)
            {
                case NotSet:
                {
                    //no mime-type set for the extension yet
                    _context.getMimeTypes().addMimeMapping(extension, mimeType);
                    _metaData.setOrigin("extension."+extension, descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //a mime-type was set for the extension in a web xml, only allow web-default/web-override to change
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        _context.getMimeTypes().addMimeMapping(extension, mimeType);
                        _metaData.setOrigin("extension."+extension, descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //a web-fragment set the value, all web-fragments must have the same value
                    if (!_context.getMimeTypes().getMimeByExtension("."+extension).equals(_context.getMimeTypes().CACHE.lookup(mimeType)))
                        throw new IllegalStateException("Conflicting mime-type "+mimeType+" for extension "+extension+" in "+descriptor.getResource());
                    break;
                }
            }
        }
    }
    
    protected void visitWelcomeFileList(Descriptor descriptor, XmlParser.Node node)
    {
        Origin o = _metaData.getOrigin("welcome-file-list");
        switch (o)
        {
            case NotSet:
            {
                _metaData.setOrigin("welcome-file-list", descriptor);
                addWelcomeFiles(node);
                break;
            }
            case WebXml:
            {
                //web.xml set the welcome-file-list, all other descriptors then just merge in
                addWelcomeFiles(node);
                break;
            }
            case WebDefaults:
            {
                //if web-defaults set the welcome-file-list first and
                //we're processing web.xml then reset the welcome-file-list
                if (!(descriptor instanceof DefaultsDescriptor) && !(descriptor instanceof OverrideDescriptor) && !(descriptor instanceof FragmentDescriptor))
                {
                    _welcomeFiles = null;
                }
                addWelcomeFiles(node);
                break;
            }
            case WebOverride:
            {
                //web-override set the list, all other descriptors just merge in
                addWelcomeFiles(node);
                break;
            }
            case WebFragment:
            {
                //A web-fragment first set the welcome-file-list. Other descriptors just add. 
                addWelcomeFiles(node);
                break;
            }
        }
    }
    
    protected void visitLocaleEncodingList(Descriptor descriptor, XmlParser.Node node)
    {
        Iterator iter = node.iterator("locale-encoding-mapping");
        while (iter.hasNext())
        {
            XmlParser.Node mapping = (XmlParser.Node) iter.next();
            String locale = mapping.getString("locale", false, true);
            String encoding = mapping.getString("encoding", false, true);
            
            if (encoding != null)
            {
                Origin o = _metaData.getOrigin("locale-encoding."+locale);
                switch (o)
                {
                    case NotSet:
                    {
                        //no mapping for the locale yet, so set it
                        _context.addLocaleEncoding(locale, encoding);
                        _metaData.setOrigin("locale-encoding."+locale, descriptor);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {
                        //a value was set in a web descriptor, only allow another web descriptor to change it (web-default/web-override)
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            _context.addLocaleEncoding(locale, encoding);
                            _metaData.setOrigin("locale-encoding."+locale, descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a value was set by a web-fragment, all fragments must have the same value
                        if (!encoding.equals(_context.getLocaleEncoding(locale)))
                            throw new IllegalStateException("Conflicting locale-encoding mapping for locale "+locale+" in "+descriptor.getResource());
                        break;                    
                    }
                }
            }
        }
    }

    protected void visitErrorPage(Descriptor descriptor, XmlParser.Node node)
    {
        String error = node.getString("error-code", false, true);
        if (error == null || error.length() == 0) error = node.getString("exception-type", false, true);
        String location = node.getString("location", false, true);

        if (_errorPages == null)
            _errorPages = new HashMap();
        
        Origin o = _metaData.getOrigin("error."+error);
        switch (o)
        {
            case NotSet:
            {
                //no error page setup for this code or exception yet
                _errorPages.put(error, location);
                _metaData.setOrigin("error."+error, descriptor);
                break;
            }
            case WebXml:
            case WebDefaults:
            case WebOverride:
            {
                //an error page setup was set in web.xml, only allow other web xml descriptors to override it
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    _errorPages.put(error, location);
                    _metaData.setOrigin("error."+error, descriptor);
                }
                break;
            }
            case WebFragment:
            {
                //another web fragment set the same error code or exception, if its different its an error
                if (!_errorPages.get(error).equals(location))
                    throw new IllegalStateException("Conflicting error-code or exception-type "+error+" in "+descriptor.getResource());
                break;
            }
        }
       
    }
    
    protected void addWelcomeFiles (XmlParser.Node node)
    {
        Iterator iter = node.iterator("welcome-file");
        while (iter.hasNext())
        {
            XmlParser.Node indexNode = (XmlParser.Node) iter.next();
            String welcome = indexNode.toString(false, true);
            
            //Servlet Spec 3.0 p. 74 welcome files are additive
            _welcomeFiles = LazyList.add(_welcomeFiles, welcome);
        }
    }
    
    protected void visitTagLib(Descriptor descriptor, XmlParser.Node node)
    {
        //Additive across web.xml and web-fragment.xml
        String uri = node.getString("taglib-uri", false, true);
        String location = node.getString("taglib-location", false, true);

        _context.setResourceAlias(uri, location);
    }
    
    protected void visitJspConfig(Descriptor descriptor, XmlParser.Node node)
    {  
        for (int i = 0; i < node.size(); i++)
        {
            Object o = node.get(i);
            if (o instanceof XmlParser.Node && "taglib".equals(((XmlParser.Node) o).getTag())) 
                visitTagLib(descriptor, (XmlParser.Node) o);
        }

        // Map URLs from jsp property groups to JSP servlet.
        // this is more JSP stupidness creaping into the servlet spec
        Iterator iter = node.iterator("jsp-property-group");
        Object paths = null;
        while (iter.hasNext())
        {
            XmlParser.Node group = (XmlParser.Node) iter.next();
            Iterator iter2 = group.iterator("url-pattern");
            while (iter2.hasNext())
            {
                String url = ((XmlParser.Node) iter2.next()).toString(false, true);
                url = normalizePattern(url);
                paths = LazyList.add(paths, url);
            }
        }

        if (LazyList.size(paths) > 0)
        {
            String jspName = getJSPServletName();
            if (jspName != null)
            {
                ServletMapping mapping = new ServletMapping();
                mapping.setServletName(jspName);
                mapping.setPathSpecs(LazyList.toStringArray(paths));
                _servletMappings = LazyList.add(_servletMappings, mapping);
            }
        }
    }
    
    protected void visitSecurityConstraint(Descriptor descriptor, XmlParser.Node node)
    {
        Constraint scBase = new Constraint();

        //ServletSpec 3.0, p74 security-constraints, as minOccurs > 1, are additive 
        //across fragments
        try
        {
            XmlParser.Node auths = node.get("auth-constraint");

            if (auths != null)
            {
                scBase.setAuthenticate(true);
                // auth-constraint
                Iterator iter = auths.iterator("role-name");
                Object roles = null;
                while (iter.hasNext())
                {
                    String role = ((XmlParser.Node) iter.next()).toString(false, true);
                    roles = LazyList.add(roles, role);
                }
                scBase.setRoles(LazyList.toStringArray(roles));
            }

            XmlParser.Node data = node.get("user-data-constraint");
            if (data != null)
            {
                data = data.get("transport-guarantee");
                String guarantee = data.toString(false, true).toUpperCase();
                if (guarantee == null || guarantee.length() == 0 || "NONE".equals(guarantee))
                    scBase.setDataConstraint(Constraint.DC_NONE);
                else if ("INTEGRAL".equals(guarantee))
                    scBase.setDataConstraint(Constraint.DC_INTEGRAL);
                else if ("CONFIDENTIAL".equals(guarantee))
                    scBase.setDataConstraint(Constraint.DC_CONFIDENTIAL);
                else
                {
                    Log.warn("Unknown user-data-constraint:" + guarantee);
                    scBase.setDataConstraint(Constraint.DC_CONFIDENTIAL);
                }
            }
            Iterator iter = node.iterator("web-resource-collection");
            while (iter.hasNext())
            {
                XmlParser.Node collection = (XmlParser.Node) iter.next();
                String name = collection.getString("web-resource-name", false, true);
                Constraint sc = (Constraint) scBase.clone();
                sc.setName(name);

                Iterator iter2 = collection.iterator("url-pattern");
                while (iter2.hasNext())
                {
                    String url = ((XmlParser.Node) iter2.next()).toString(false, true);
                    url = normalizePattern(url);

                    Iterator iter3 = collection.iterator("http-method");
                    if (iter3.hasNext())
                    {
                        while (iter3.hasNext())
                        {
                            String method = ((XmlParser.Node) iter3.next()).toString(false, true);
                            ConstraintMapping mapping = new ConstraintMapping();
                            mapping.setMethod(method);
                            mapping.setPathSpec(url);
                            mapping.setConstraint(sc);
                            _constraintMappings.add(mapping);
                        }
                    }
                    else
                    {
                        ConstraintMapping mapping = new ConstraintMapping();
                        mapping.setPathSpec(url);
                        mapping.setConstraint(sc);
                        _constraintMappings.add(mapping);
                    }
                }
            }
        }
        catch (CloneNotSupportedException e)
        {
            Log.warn(e);
        }
    }
    
    protected void visitLoginConfig(Descriptor descriptor, XmlParser.Node node) throws Exception
    {
        //ServletSpec 3.0 p74 says elements present 0/1 time if specified in web.xml take
        //precendece over any web-fragment. If not specified in web.xml, then if specified
        //in a web-fragment must be the same across all web-fragments.
        XmlParser.Node method = node.get("auth-method");
        if (method != null)
        {
            //handle auth-method merge
            Origin o = _metaData.getOrigin("auth-method");
            switch (o)
            {
                case NotSet:
                {
                    //not already set, so set it now
                    _securityHandler.setAuthMethod(method.toString(false, true));
                    _metaData.setOrigin("auth-method", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //if it was already set by a web xml descriptor and we're parsing another web xml descriptor, then override it
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        _securityHandler.setAuthMethod(method.toString(false, true));
                        _metaData.setOrigin("auth-method", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //it was already set by another fragment, if we're parsing a fragment, the values must match
                    if (!_securityHandler.getAuthMethod().equals(method.toString(false, true)))
                        throw new IllegalStateException("Conflicting auth-method value in "+descriptor.getResource());
                    break;
                }
            } 
            
            //handle realm-name merge
            XmlParser.Node name = node.get("realm-name");
            String nameStr = (name == null ? "default" : name.toString(false, true));
            o = _metaData.getOrigin("realm-name");
            switch (o)
            {
                case NotSet:
                {
                    //no descriptor has set the realm-name yet, so set it
                    _securityHandler.setRealmName(nameStr);
                    _metaData.setOrigin("realm-name", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //set by a web xml file (web.xml/web-default.xm/web-override.xml), only allow it to be changed by another web xml file
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        _securityHandler.setRealmName(nameStr);
                        _metaData.setOrigin("realm-name", descriptor); 
                    }
                    break;
                }
                case WebFragment:
                {
                    //a fragment set it, and we must be parsing another fragment, so the values must match
                    if (!_securityHandler.getRealmName().equals(nameStr))
                        throw new IllegalStateException("Conflicting realm-name value in "+descriptor.getResource());
                    break;
                }
            }
 
            if (Constraint.__FORM_AUTH.equals(_securityHandler.getAuthMethod()))
            {  
                XmlParser.Node formConfig = node.get("form-login-config");
                if (formConfig != null)
                {
                    String loginPageName = null;
                    XmlParser.Node loginPage = formConfig.get("form-login-page");
                    if (loginPage != null) 
                        loginPageName = loginPage.toString(false, true);
                    String errorPageName = null;
                    XmlParser.Node errorPage = formConfig.get("form-error-page");
                    if (errorPage != null) 
                        errorPageName = errorPage.toString(false, true);
                    
                    //handle form-login-page
                    o = _metaData.getOrigin("form-login-page");
                    switch (o)
                    {
                        case NotSet:
                        {
                            //Never been set before, so accept it
                            _securityHandler.setInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE,loginPageName);
                            _metaData.setOrigin("form-login-page",descriptor);
                            break;
                        }
                        case WebXml:
                        case WebDefaults:
                        case WebOverride:
                        {
                            //a web xml descriptor previously set it, only allow another one to change it (web.xml/web-default.xml/web-override.xml)
                            if (!(descriptor instanceof FragmentDescriptor))
                            {
                                _securityHandler.setInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE,loginPageName);
                                _metaData.setOrigin("form-login-page",descriptor);
                            }
                            break;
                        }
                        case WebFragment:
                        {
                            //a web-fragment previously set it. We must be parsing yet another web-fragment, so the values must agree
                            if (!_securityHandler.getInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE).equals(loginPageName))
                                throw new IllegalStateException("Conflicting form-login-page value in "+descriptor.getResource());
                            break;
                        }
                    }
                    
                    //handle form-error-page
                    o = _metaData.getOrigin("form-error-page");
                    switch (o)
                    {
                        case NotSet:
                        {
                            //Never been set before, so accept it
                            _securityHandler.setInitParameter(FormAuthenticator.__FORM_ERROR_PAGE,errorPageName);
                            _metaData.setOrigin("form-error-page",descriptor);
                            break;
                        }
                        case WebXml:
                        case WebDefaults:
                        case WebOverride:
                        {
                            //a web xml descriptor previously set it, only allow another one to change it (web.xml/web-default.xml/web-override.xml)
                            if (!(descriptor instanceof FragmentDescriptor))
                            {
                                _securityHandler.setInitParameter(FormAuthenticator.__FORM_ERROR_PAGE,errorPageName);
                                _metaData.setOrigin("form-error-page",descriptor);
                            }
                            break;
                        }
                        case WebFragment:
                        {
                            //a web-fragment previously set it. We must be parsing yet another web-fragment, so the values must agree
                            if (!_securityHandler.getInitParameter(FormAuthenticator.__FORM_ERROR_PAGE).equals(errorPageName))
                                throw new IllegalStateException("Conflicting form-error-page value in "+descriptor.getResource());
                            break;
                        }
                    }              
                }
                else
                {
                    throw new IllegalStateException("!form-login-config");
                }
            }
        }
    }
    
    protected void visitSecurityRole(Descriptor descriptor, XmlParser.Node node)
    {
        //ServletSpec 3.0, p74 elements with multiplicity >1 are additive when merged
        XmlParser.Node roleNode = node.get("role-name");
        String role = roleNode.toString(false, true);
        _roles.add(role);
    }
    
    
    protected void visitFilter(Descriptor descriptor, XmlParser.Node node)
    {
        String name = node.getString("filter-name", false, true);
        FilterHolder holder = _servletHandler.getFilter(name);
        if (holder == null)
        {
            holder = _servletHandler.newFilterHolder();
            holder.setName(name);
            _filters = LazyList.add(_filters, holder);
        }

        String filter_class = node.getString("filter-class", false, true);
        if (filter_class != null) 
        {
            descriptor.addClassName(filter_class);
            
            Origin o = _metaData.getOrigin(name+".filter.filter-class");
            switch (o)
            {
                case NotSet:
                {
                    //no class set yet
                    holder.setClassName(filter_class);
                    _metaData.setOrigin(name+".filter.filter-class", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //filter class was set in web.xml, only allow other web xml descriptors (override/default) to change it
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        holder.setClassName(filter_class);
                        _metaData.setOrigin(name+".filter.filter-class", descriptor); 
                    }
                    break;
                }
                case WebFragment:
                {
                    //the filter class was set up by a web fragment, all fragments must be the same
                    if (!holder.getClassName().equals(filter_class))
                        throw new IllegalStateException("Conflicting filter-class for filter "+name+" in "+descriptor.getResource());
                    break;
                }
            }
           
        }

        Iterator iter = node.iterator("init-param");
        while (iter.hasNext())
        {
            XmlParser.Node paramNode = (XmlParser.Node) iter.next();
            String pname = paramNode.getString("param-name", false, true);
            String pvalue = paramNode.getString("param-value", false, true);
            
            Origin origin = _metaData.getOrigin(name+".filter.init-param."+pname);
            switch (origin)
            {
                case NotSet:
                {
                    //init-param not already set, so set it
                    holder.setInitParameter(pname, pvalue); 
                    _metaData.setOrigin(name+".filter.init-param."+pname, descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //previously set by a web xml descriptor, if we're parsing another web xml descriptor allow override
                    //otherwise just ignore it
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        holder.setInitParameter(pname, pvalue); 
                        _metaData.setOrigin(name+".filter.init-param."+pname, descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //previously set by a web-fragment, make sure that the value matches, otherwise its an error
                    if (!holder.getInitParameter(pname).equals(pvalue))
                        throw new IllegalStateException("Mismatching init-param "+pname+"="+pvalue+" in "+descriptor.getResource());
                    break;
                }
            }  
        }

        String async=node.getString("async-supported",false,true);
        if (async!=null)
            holder.setAsyncSupported(async.length()==0||Boolean.valueOf(async));
        if (async!=null)
        {
            boolean val = async.length()==0||Boolean.valueOf(async);
            Origin o = _metaData.getOrigin(name+".filter.async-supported");
            switch (o)
            {
                case NotSet:
                {
                    //set it
                    holder.setAsyncSupported(val);
                    _metaData.setOrigin(name+".filter.async-supported", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //async-supported set by previous web xml descriptor, only allow override if we're parsing another web descriptor(web.xml/web-override.xml/web-default.xml)
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        holder.setAsyncSupported(val);
                        _metaData.setOrigin(name+".filter.async-supported", descriptor);  
                    }             
                    break;
                }
                case WebFragment:
                {
                    //async-supported set by another fragment, this fragment's value must match
                    if (holder.isAsyncSupported() != val)
                        throw new IllegalStateException("Conflicting async-supported="+async+" for filter "+name+" in "+descriptor.getResource());
                    break;
                }
            }
        }
        
    }

    protected void visitFilterMapping(Descriptor descriptor, XmlParser.Node node)
    {
        //Servlet Spec 3.0, p74
        //filter-mappings are always additive, whether from web xml descriptors (web.xml/web-default.xml/web-override.xml) or web-fragments.
        
        String filter_name = node.getString("filter-name", false, true);

        FilterMapping mapping = new FilterMapping();

        mapping.setFilterName(filter_name);

        ArrayList paths = new ArrayList();
        Iterator iter = node.iterator("url-pattern");
        while (iter.hasNext())
        {
            String p = ((XmlParser.Node) iter.next()).toString(false, true);
            p = normalizePattern(p);
            paths.add(p);
        }
        mapping.setPathSpecs((String[]) paths.toArray(new String[paths.size()]));

        ArrayList names = new ArrayList();
        iter = node.iterator("servlet-name");
        while (iter.hasNext())
        {
            String n = ((XmlParser.Node) iter.next()).toString(false, true);
            names.add(n);
        }
        mapping.setServletNames((String[]) names.toArray(new String[names.size()]));

        
        List<DispatcherType> dispatches = new ArrayList<DispatcherType>();
        iter=node.iterator("dispatcher");
        while(iter.hasNext())
        {
            String d=((XmlParser.Node)iter.next()).toString(false,true);
            dispatches.add(FilterMapping.dispatch(d));
        }
        
        if (dispatches.size()>0)
            mapping.setDispatcherTypes(EnumSet.copyOf(dispatches));

        _filterMappings = LazyList.add(_filterMappings, mapping);
    }

    
    protected void visitListener(Descriptor descriptor, XmlParser.Node node)
    {
        String className = node.getString("listener-class", false, true);
        Object listener = null;
        try
        {
            if (className != null && className.length()> 0)
            {
                descriptor.addClassName(className);

                //Servlet Spec 3.0 p 74
                //Duplicate listener declarations don't result in duplicate listener instances
                if (!LazyList.contains(_listenerClassNames, className))
                {
                    LazyList.add(_listenerClassNames, className);
                    Class listenerClass = _context.loadClass(className);
                    listener = newListenerInstance(listenerClass);
                    if (!(listener instanceof EventListener))
                    {
                        Log.warn("Not an EventListener: " + listener);
                        return;
                    }
                    _metaData.setOrigin(className+".listener", descriptor);
                    _listeners = LazyList.add(_listeners, listener);
                }
            }
        }
        catch (Exception e)
        {
            Log.warn("Could not instantiate listener " + className, e);
            return;
        }
    }
    
    protected void visitDistributable(Descriptor descriptor, XmlParser.Node node)
    {
        // the element has no content, so its simple presence
        // indicates that the webapp is distributable...
        //Servlet Spec 3.0 p.74  distributable only if all fragments are distributable
        descriptor.setDistributable(true);
    }
    
    protected Object newListenerInstance(Class<?extends EventListener> clazz) throws ServletException, InstantiationException, IllegalAccessException
    {
        try
        {
            return ((ServletContextHandler.Context)_context.getServletContext()).createListener(clazz);
        }
        catch (ServletException se)
        {
            Throwable cause = se.getRootCause();
            if (cause instanceof InstantiationException)
                throw (InstantiationException)cause;
            if (cause instanceof IllegalAccessException)
                throw (IllegalAccessException)cause;
            throw se;
        }
    }
    
    protected String getJSPServletName()
    {
        if (_jspServletName == null)
        {
            Map.Entry entry = _context.getServletHandler().getHolderEntry("test.jsp");
            if (entry != null)
            {
                ServletHolder holder = (ServletHolder) entry.getValue();
                _jspServletName = holder.getName();
            }
        }
        return _jspServletName;
    }
    
    protected String normalizePattern(String p)
    {
        if (p != null && p.length() > 0 && !p.startsWith("/") && !p.startsWith("*")) return "/" + p;
        return p;
    }

    /**
     * Generate the classpath (as a string) of all classloaders
     * above the webapp's classloader.
     * 
     * This is primarily used for jasper.
     * @return
     */
    protected String getSystemClassPath ()
    {
        ClassLoader loader = _context.getClassLoader();
        if (loader.getParent() != null)
            loader = loader.getParent();

        StringBuilder classpath=new StringBuilder();
        while (loader != null && (loader instanceof URLClassLoader))
        {
            URL[] urls = ((URLClassLoader)loader).getURLs();
            if (urls != null)
            {     
                for (int i=0;i<urls.length;i++)
                {
                    try
                    {
                        Resource resource = _context.newResource(urls[i]);
                        File file=resource.getFile();
                        if (file!=null && file.exists())
                        {
                            if (classpath.length()>0)
                                classpath.append(File.pathSeparatorChar);
                            classpath.append(file.getAbsolutePath());
                        }
                    }
                    catch (IOException e)
                    {
                        Log.debug(e);
                    }
                }
            }
            loader = loader.getParent();
        }
        return classpath.toString();
    }
}
