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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.descriptor.JspPropertyGroupDescriptor;
import javax.servlet.descriptor.TaglibDescriptor;

import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.Holder;
import org.eclipse.jetty.servlet.JspPropertyGroupServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletContextHandler.JspConfig;
import org.eclipse.jetty.servlet.ServletContextHandler.JspPropertyGroup;
import org.eclipse.jetty.servlet.ServletContextHandler.TagLib;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.xml.XmlParser;

/**
 * StandardDescriptorProcessor
 *
 * Process a web.xml, web-defaults.xml, web-overrides.xml, web-fragment.xml.
 */
public class StandardDescriptorProcessor extends IterativeDescriptorProcessor
{
    private static final Logger LOG = Log.getLogger(StandardDescriptorProcessor.class);

    public static final String STANDARD_PROCESSOR = "org.eclipse.jetty.standardDescriptorProcessor";
    
    
    
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
     * {@inheritDoc}
     */
    public void start(WebAppContext context, Descriptor descriptor)
    { 
    }
    
    
    
    /** 
     * {@inheritDoc}
     */
    public void end(WebAppContext context, Descriptor descriptor)
    {
    }
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    public void visitContextParam (WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String name = node.getString("param-name", false, true);
        String value = node.getString("param-value", false, true);
        Origin o = context.getMetaData().getOrigin("context-param."+name);
        switch (o)
        {
            case NotSet:
            {
                //just set it
                context.getInitParams().put(name, value);
                context.getMetaData().setOrigin("context-param."+name, descriptor);
                break;
            }
            case WebXml:
            case WebDefaults:
            case WebOverride:
            {
                //previously set by a web xml, allow other web xml files to override
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    context.getInitParams().put(name, value);
                    context.getMetaData().setOrigin("context-param."+name, descriptor); 
                }
                break;
            }
            case WebFragment:
            {
                //previously set by a web-fragment, this fragment's value must be the same
                if (descriptor instanceof FragmentDescriptor)
                {
                    if (!((String)context.getInitParams().get(name)).equals(value))
                        throw new IllegalStateException("Conflicting context-param "+name+"="+value+" in "+descriptor.getResource());
                }
                break;
            }
        }
        if (LOG.isDebugEnabled()) 
            LOG.debug("ContextParam: " + name + "=" + value);

    }
    

    /* ------------------------------------------------------------ */
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitDisplayName(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //Servlet Spec 3.0 p. 74 Ignore from web-fragments
        if (!(descriptor instanceof FragmentDescriptor))
        {
            context.setDisplayName(node.toString(false, true));
            context.getMetaData().setOrigin("display-name", descriptor);
        }
    }
    
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitServlet(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String id = node.getAttribute("id");

        // initialize holder
        String servlet_name = node.getString("servlet-name", false, true);
        ServletHolder holder = context.getServletHandler().getServlet(servlet_name);
          
        /*
         * If servlet of that name does not already exist, create it.
         */
        if (holder == null)
        {
            holder = context.getServletHandler().newServletHolder(Holder.Source.DESCRIPTOR);
            holder.setName(servlet_name);
            context.getServletHandler().addServlet(holder);
        }

        // init params  
        Iterator<?> iParamsIter = node.iterator("init-param");
        while (iParamsIter.hasNext())
        {
            XmlParser.Node paramNode = (XmlParser.Node) iParamsIter.next();
            String pname = paramNode.getString("param-name", false, true);
            String pvalue = paramNode.getString("param-value", false, true);
            
            Origin origin = context.getMetaData().getOrigin(servlet_name+".servlet.init-param."+pname);
            
            switch (origin)
            {
                case NotSet:
                {
                    //init-param not already set, so set it
                    
                    holder.setInitParameter(pname, pvalue); 
                    context.getMetaData().setOrigin(servlet_name+".servlet.init-param."+pname, descriptor);
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
                        context.getMetaData().setOrigin(servlet_name+".servlet.init-param."+pname, descriptor);
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

        String servlet_class = node.getString("servlet-class", false, true);

        // Handle JSP
        String jspServletClass=null;;

        //Handle the default jsp servlet instance
        if (id != null && id.equals("jsp"))
        {
            jspServletClass = servlet_class;
            try
            {
                Loader.loadClass(this.getClass(), servlet_class);
                
                //Ensure there is a scratch dir
                if (holder.getInitParameter("scratchdir") == null)
                {
                    File tmp = context.getTempDirectory();
                    File scratch = new File(tmp, "jsp");
                    if (!scratch.exists()) scratch.mkdir();
                    holder.setInitParameter("scratchdir", scratch.getAbsolutePath());
                }
            }
            catch (ClassNotFoundException e)
            {
                LOG.info("NO JSP Support for {}, did not find {}", context.getContextPath(), servlet_class);
                jspServletClass = servlet_class = "org.eclipse.jetty.servlet.NoJspServlet";
            }
        }
        
       
        //Set the servlet-class
        if (servlet_class != null) 
        {
            ((WebDescriptor)descriptor).addClassName(servlet_class);
            
            Origin o = context.getMetaData().getOrigin(servlet_name+".servlet.servlet-class");
            switch (o)
            {
                case NotSet:
                {
                    //the class of the servlet has not previously been set, so set it
                    holder.setClassName(servlet_class);
                    context.getMetaData().setOrigin(servlet_name+".servlet.servlet-class", descriptor);
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
                        context.getMetaData().setOrigin(servlet_name+".servlet.servlet-class", descriptor);
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

        // Handle JSP file
        String jsp_file = node.getString("jsp-file", false, true);
        if (jsp_file != null)
        {
            holder.setForcedPath(jsp_file);
            ServletHolder jsp=context.getServletHandler().getServlet("jsp");
            if (jsp!=null)
                holder.setClassName(jsp.getClassName());
        }

        // handle load-on-startup 
        XmlParser.Node startup = node.get("load-on-startup");
        if (startup != null)
        {
            String s = startup.toString(false, true).toLowerCase(Locale.ENGLISH);
            int order = 0;
            if (s.startsWith("t"))
            {
                LOG.warn("Deprecated boolean load-on-startup.  Please use integer");
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
                    LOG.warn("Cannot parse load-on-startup " + s + ". Please use integer");
                    LOG.ignore(e);
                }
            }

            Origin o = context.getMetaData().getOrigin(servlet_name+".servlet.load-on-startup");
            switch (o)
            {
                case NotSet:
                {
                    //not already set, so set it now
                    holder.setInitOrder(order);
                    context.getMetaData().setOrigin(servlet_name+".servlet.load-on-startup", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //if it was already set by a web xml descriptor and we're parsing another web xml descriptor, then override it
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        holder.setInitOrder(order);
                        context.getMetaData().setOrigin(servlet_name+".servlet.load-on-startup", descriptor);
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
                if (LOG.isDebugEnabled()) LOG.debug("link role " + roleName + " to " + roleLink + " for " + this);
                Origin o = context.getMetaData().getOrigin(servlet_name+".servlet.role-name."+roleName);
                switch (o)
                {
                    case NotSet:
                    {
                        //set it
                        holder.setUserRoleLink(roleName, roleLink);
                        context.getMetaData().setOrigin(servlet_name+".servlet.role-name."+roleName, descriptor);
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
                            context.getMetaData().setOrigin(servlet_name+".servlet.role-name."+roleName, descriptor);
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
                LOG.warn("Ignored invalid security-role-ref element: " + "servlet-name=" + holder.getName() + ", " + securityRef);
            }
        }

        
        XmlParser.Node run_as = node.get("run-as");
        if (run_as != null)
        { 
            String roleName = run_as.getString("role-name", false, true);

            if (roleName != null)
            {
                Origin o = context.getMetaData().getOrigin(servlet_name+".servlet.run-as");
                switch (o)
                {
                    case NotSet:
                    {
                        //run-as not set, so set it
                        holder.setRunAsRole(roleName);
                        context.getMetaData().setOrigin(servlet_name+".servlet.run-as", descriptor);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {
                        //run-as was set by a web xml, only allow it to be changed if we're currently parsing another web xml(override/default)
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            holder.setRunAsRole(roleName);
                            context.getMetaData().setOrigin(servlet_name+".servlet.run-as", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //run-as was set by another fragment, this fragment must show the same value
                        if (!holder.getRunAsRole().equals(roleName))
                            throw new IllegalStateException("Conflicting run-as role "+roleName+" for servlet "+servlet_name+" in "+descriptor.getResource());
                        break;
                    }    
                }
            }
        }

        String async=node.getString("async-supported",false,true);
        if (async!=null)
        {
            boolean val = async.length()==0||Boolean.valueOf(async);
            Origin o =context.getMetaData().getOrigin(servlet_name+".servlet.async-supported");
            switch (o)
            {
                case NotSet:
                {
                    //set it
                    holder.setAsyncSupported(val);
                    context.getMetaData().setOrigin(servlet_name+".servlet.async-supported", descriptor);
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
                        context.getMetaData().setOrigin(servlet_name+".servlet.async-supported", descriptor);  
                    }             
                    break;
                }
                case WebFragment:
                {
                    //async-supported set by another fragment, this fragment's value must match
                    if (holder.isAsyncSupported() != val)
                        throw new IllegalStateException("Conflicting async-supported="+async+" for servlet "+servlet_name+" in "+descriptor.getResource());
                    break;
                }
            }
        }

        String enabled = node.getString("enabled", false, true);
        if (enabled!=null)
        {
            boolean is_enabled = enabled.length()==0||Boolean.valueOf(enabled);     
            Origin o = context.getMetaData().getOrigin(servlet_name+".servlet.enabled");
            switch (o)
            {
                case NotSet:
                {
                    //hasn't been set yet, so set it                
                    holder.setEnabled(is_enabled);
                    context.getMetaData().setOrigin(servlet_name+".servlet.enabled", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //was set in a web xml descriptor, only allow override from another web xml descriptor
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        holder.setEnabled(is_enabled);   
                        context.getMetaData().setOrigin(servlet_name+".servlet.enabled", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //was set by another fragment, this fragment's value must match
                    if (holder.isEnabled() != is_enabled)
                        throw new IllegalStateException("Conflicting value of servlet enabled for servlet "+servlet_name+" in "+descriptor.getResource());
                    break;
                }
            }
        }
        
        /*
         * If multipart config not set, then set it and record it was by the web.xml or fragment.
         * If it was set by web.xml then if this is a fragment, ignore the settings.
         * If it was set by a fragment, if this is a fragment and the values are different, error!
         */
        XmlParser.Node multipart = node.get("multipart-config");
        if (multipart != null)
        {
            String location = multipart.getString("location", false, true);
            String maxFile = multipart.getString("max-file-size", false, true);
            String maxRequest = multipart.getString("max-request-size", false, true);
            String threshold = multipart.getString("file-size-threshold",false,true);
            MultipartConfigElement element = new MultipartConfigElement(location,
                                                                        (maxFile==null||"".equals(maxFile)?-1L:Long.parseLong(maxFile)),
                                                                        (maxRequest==null||"".equals(maxRequest)?-1L:Long.parseLong(maxRequest)),
                                                                        (threshold==null||"".equals(threshold)?0:Integer.parseInt(threshold)));
            
            Origin o = context.getMetaData().getOrigin(servlet_name+".servlet.multipart-config");
            switch (o)
            {
                case NotSet:
                {
                    //hasn't been set, so set it
                    holder.getRegistration().setMultipartConfig(element);
                    context.getMetaData().setOrigin(servlet_name+".servlet.multipart-config", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //was set in a web xml, only allow changes if we're parsing another web xml (web.xml/web-default.xml/web-override.xml)
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        holder.getRegistration().setMultipartConfig(element);
                        context.getMetaData().setOrigin(servlet_name+".servlet.multipart-config", descriptor);  
                    }
                    break;
                }
                case WebFragment:
                {
                    //another fragment set the value, this fragment's values must match exactly or it is an error
                    MultipartConfigElement cfg = ((ServletHolder.Registration)holder.getRegistration()).getMultipartConfig();
                    
                    if (cfg.getMaxFileSize() != element.getMaxFileSize())
                        throw new IllegalStateException("Conflicting multipart-config max-file-size for servlet "+servlet_name+" in "+descriptor.getResource());
                    if (cfg.getMaxRequestSize() != element.getMaxRequestSize())
                        throw new IllegalStateException("Conflicting multipart-config max-request-size for servlet "+servlet_name+" in "+descriptor.getResource());
                    if (cfg.getFileSizeThreshold() != element.getFileSizeThreshold())
                        throw new IllegalStateException("Conflicting multipart-config file-size-threshold for servlet "+servlet_name+" in "+descriptor.getResource());
                    if ((cfg.getLocation() != null && (element.getLocation() == null || element.getLocation().length()==0))
                            || (cfg.getLocation() == null && (element.getLocation()!=null || element.getLocation().length() > 0)))
                        throw new IllegalStateException("Conflicting multipart-config location for servlet "+servlet_name+" in "+descriptor.getResource());
                    break;
                }
            } 
        }
    }
    
    

    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitServletMapping(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //Servlet Spec 3.0, p74
        //servlet-mappings are always additive, whether from web xml descriptors (web.xml/web-default.xml/web-override.xml) or web-fragments.
        //Maintenance update 3.0a to spec:
        //  Updated 8.2.3.g.v to say <servlet-mapping> elements are additive across web-fragments. 
        //  <servlet-mapping> declared in web.xml overrides the mapping for the servlet specified in the web-fragment.xml

        String servlet_name = node.getString("servlet-name", false, true); 
        Origin origin = context.getMetaData().getOrigin(servlet_name+".servlet.mappings");
        
        switch (origin)
        {
            case NotSet:
            {
                //no servlet mappings
                context.getMetaData().setOrigin(servlet_name+".servlet.mappings", descriptor);
                ServletMapping mapping = addServletMapping(servlet_name, node, context, descriptor);
                mapping.setDefault(context.getMetaData().getOrigin(servlet_name+".servlet.mappings") == Origin.WebDefaults);
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
                   addServletMapping(servlet_name, node, context, descriptor);
                }
                break;
            }
            case WebFragment:
            {
                //mappings previously set by another web-fragment, so merge in this web-fragment's mappings
                addServletMapping(servlet_name, node, context, descriptor);
                break;
            }
        }        
    }
    
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitSessionConfig(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        XmlParser.Node tNode = node.get("session-timeout");
        if (tNode != null)
        {
            int timeout = Integer.parseInt(tNode.toString(false, true));
            context.getSessionHandler().getSessionManager().setMaxInactiveInterval(timeout * 60);
        }
        
        //Servlet Spec 3.0 
        // <tracking-mode>
        // this is additive across web-fragments
        Iterator iter = node.iterator("tracking-mode");
        if (iter.hasNext())
        { 
            Set<SessionTrackingMode> modes = null;
            Origin o = context.getMetaData().getOrigin("session.tracking-mode");
            switch (o)
            {
                case NotSet://not previously set, starting fresh
                case WebDefaults://previously set in web defaults, allow this descriptor to start fresh
                {
                    
                    modes = new HashSet<SessionTrackingMode>();
                    context.getMetaData().setOrigin("session.tracking-mode", descriptor);
                    break;
                }
                case WebXml:
                case WebFragment:
                case WebOverride:
                {
                    //if setting from an override descriptor, start afresh, otherwise add-in tracking-modes
                    if (descriptor instanceof OverrideDescriptor)
                        modes = new HashSet<SessionTrackingMode>();
                    else
                        modes = new HashSet<SessionTrackingMode>(context.getSessionHandler().getSessionManager().getEffectiveSessionTrackingModes());
                    context.getMetaData().setOrigin("session.tracking-mode", descriptor);
                    break;
                }       
            }
            
            while (iter.hasNext())
            {
                XmlParser.Node mNode = (XmlParser.Node) iter.next();
                String trackMode = mNode.toString(false, true);
                modes.add(SessionTrackingMode.valueOf(trackMode));
            }
            context.getSessionHandler().getSessionManager().setSessionTrackingModes(modes);   
        }
       
        
        //Servlet Spec 3.0 
        //<cookie-config>
        XmlParser.Node cookieConfig = node.get("cookie-config");
        if (cookieConfig != null)
        {
            //  <name>
            String name = cookieConfig.getString("name", false, true);
            if (name != null)
            {
                Origin o = context.getMetaData().getOrigin("cookie-config.name");
                switch (o)
                {
                    case NotSet:
                    {
                        //no <cookie-config><name> set yet, accept it
                        context.getSessionHandler().getSessionManager().getSessionCookieConfig().setName(name);
                        context.getMetaData().setOrigin("cookie-config.name", descriptor);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {
                        //<cookie-config><name> set in a web xml, only allow web-default/web-override to change
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            context.getSessionHandler().getSessionManager().getSessionCookieConfig().setName(name);
                            context.getMetaData().setOrigin("cookie-config.name", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (!context.getSessionHandler().getSessionManager().getSessionCookieConfig().getName().equals(name))                  
                            throw new IllegalStateException("Conflicting cookie-config name "+name+" in "+descriptor.getResource());
                        break;
                    }
                }
            }
            
            //  <domain>
            String domain = cookieConfig.getString("domain", false, true);
            if (domain != null)
            {
                Origin o = context.getMetaData().getOrigin("cookie-config.domain");
                switch (o)
                {
                    case NotSet:
                    {
                        //no <cookie-config><domain> set yet, accept it
                        context.getSessionHandler().getSessionManager().getSessionCookieConfig().setDomain(domain);
                        context.getMetaData().setOrigin("cookie-config.domain", descriptor);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {
                        //<cookie-config><domain> set in a web xml, only allow web-default/web-override to change
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            context.getSessionHandler().getSessionManager().getSessionCookieConfig().setDomain(domain);
                            context.getMetaData().setOrigin("cookie-config.domain", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (!context.getSessionHandler().getSessionManager().getSessionCookieConfig().getDomain().equals(domain))                  
                            throw new IllegalStateException("Conflicting cookie-config domain "+domain+" in "+descriptor.getResource());
                        break;
                    }
                }
            }
            
            //  <path>
            String path = cookieConfig.getString("path", false, true);
            if (path != null)
            {
                Origin o = context.getMetaData().getOrigin("cookie-config.path");
                switch (o)
                {
                    case NotSet:
                    {
                        //no <cookie-config><domain> set yet, accept it
                        context.getSessionHandler().getSessionManager().getSessionCookieConfig().setPath(path);
                        context.getMetaData().setOrigin("cookie-config.path", descriptor);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {
                        //<cookie-config><domain> set in a web xml, only allow web-default/web-override to change
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            context.getSessionHandler().getSessionManager().getSessionCookieConfig().setPath(path);
                            context.getMetaData().setOrigin("cookie-config.path", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (!context.getSessionHandler().getSessionManager().getSessionCookieConfig().getPath().equals(path))                  
                            throw new IllegalStateException("Conflicting cookie-config path "+path+" in "+descriptor.getResource());
                        break;
                    }
                }
            }
            
            //  <comment>
            String comment = cookieConfig.getString("comment", false, true);
            if (comment != null)
            {
                Origin o = context.getMetaData().getOrigin("cookie-config.comment");
                switch (o)
                {
                    case NotSet:
                    {
                        //no <cookie-config><comment> set yet, accept it
                        context.getSessionHandler().getSessionManager().getSessionCookieConfig().setComment(comment);
                        context.getMetaData().setOrigin("cookie-config.comment", descriptor);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {
                        //<cookie-config><comment> set in a web xml, only allow web-default/web-override to change
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            context.getSessionHandler().getSessionManager().getSessionCookieConfig().setComment(comment);
                            context.getMetaData().setOrigin("cookie-config.comment", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (!context.getSessionHandler().getSessionManager().getSessionCookieConfig().getComment().equals(comment))                  
                            throw new IllegalStateException("Conflicting cookie-config comment "+comment+" in "+descriptor.getResource());
                        break;
                    }
                }
            }
            
            //  <http-only>true/false
            tNode = cookieConfig.get("http-only");
            if (tNode != null)
            {
                boolean httpOnly = Boolean.parseBoolean(tNode.toString(false,true));           
                Origin o = context.getMetaData().getOrigin("cookie-config.http-only");
                switch (o)
                {
                    case NotSet:
                    {
                        //no <cookie-config><http-only> set yet, accept it
                        context.getSessionHandler().getSessionManager().getSessionCookieConfig().setHttpOnly(httpOnly);
                        context.getMetaData().setOrigin("cookie-config.http-only", descriptor);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {
                        //<cookie-config><http-only> set in a web xml, only allow web-default/web-override to change
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            context.getSessionHandler().getSessionManager().getSessionCookieConfig().setHttpOnly(httpOnly);
                            context.getMetaData().setOrigin("cookie-config.http-only", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (context.getSessionHandler().getSessionManager().getSessionCookieConfig().isHttpOnly() != httpOnly)       
                            throw new IllegalStateException("Conflicting cookie-config http-only "+httpOnly+" in "+descriptor.getResource());
                        break;
                    }
                }
            }
            
            //  <secure>true/false
            tNode = cookieConfig.get("secure");
            if (tNode != null)
            {
                boolean secure = Boolean.parseBoolean(tNode.toString(false,true));
                Origin o = context.getMetaData().getOrigin("cookie-config.secure");
                switch (o)
                {
                    case NotSet:
                    {
                        //no <cookie-config><secure> set yet, accept it
                        context.getSessionHandler().getSessionManager().getSessionCookieConfig().setSecure(secure);
                        context.getMetaData().setOrigin("cookie-config.secure", descriptor);
                        break;
                    }                   
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {
                        //<cookie-config><secure> set in a web xml, only allow web-default/web-override to change
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            context.getSessionHandler().getSessionManager().getSessionCookieConfig().setSecure(secure);
                            context.getMetaData().setOrigin("cookie-config.secure", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (context.getSessionHandler().getSessionManager().getSessionCookieConfig().isSecure() != secure)       
                            throw new IllegalStateException("Conflicting cookie-config secure "+secure+" in "+descriptor.getResource());
                        break;
                    }
                }
            }
            
            //  <max-age>
            tNode = cookieConfig.get("max-age");
            if (tNode != null)
            {
                int maxAge = Integer.parseInt(tNode.toString(false,true));
                Origin o = context.getMetaData().getOrigin("cookie-config.max-age");
                switch (o)
                {
                    case NotSet:
                    { 
                        //no <cookie-config><max-age> set yet, accept it
                        context.getSessionHandler().getSessionManager().getSessionCookieConfig().setMaxAge(maxAge);
                        context.getMetaData().setOrigin("cookie-config.max-age", descriptor);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {   
                        //<cookie-config><max-age> set in a web xml, only allow web-default/web-override to change
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            context.getSessionHandler().getSessionManager().getSessionCookieConfig().setMaxAge(maxAge);
                            context.getMetaData().setOrigin("cookie-config.max-age", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (context.getSessionHandler().getSessionManager().getSessionCookieConfig().getMaxAge() != maxAge)
                            throw new IllegalStateException("Conflicting cookie-config max-age "+maxAge+" in "+descriptor.getResource());
                        break;
                    }
                }
            }
        }
    }
    
    
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitMimeMapping(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String extension = node.getString("extension", false, true);
        if (extension != null && extension.startsWith(".")) 
            extension = extension.substring(1);
        String mimeType = node.getString("mime-type", false, true);
        if (extension != null)
        {
            Origin o = context.getMetaData().getOrigin("extension."+extension);
            switch (o)
            {
                case NotSet:
                {
                    //no mime-type set for the extension yet
                    context.getMimeTypes().addMimeMapping(extension, mimeType);
                    context.getMetaData().setOrigin("extension."+extension, descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //a mime-type was set for the extension in a web xml, only allow web-default/web-override to change
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        context.getMimeTypes().addMimeMapping(extension, mimeType);
                        context.getMetaData().setOrigin("extension."+extension, descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //a web-fragment set the value, all web-fragments must have the same value
                    if (!context.getMimeTypes().getMimeByExtension("."+extension).equals(context.getMimeTypes().CACHE.lookup(mimeType)))
                        throw new IllegalStateException("Conflicting mime-type "+mimeType+" for extension "+extension+" in "+descriptor.getResource());
                    break;
                }
            }
        }
    }
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitWelcomeFileList(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        Origin o = context.getMetaData().getOrigin("welcome-file-list");
        switch (o)
        {
            case NotSet:
            {
                context.getMetaData().setOrigin("welcome-file-list", descriptor);
                addWelcomeFiles(context,node);
                break;
            }
            case WebXml:
            {
                //web.xml set the welcome-file-list, all other descriptors then just merge in
                addWelcomeFiles(context,node);
                break;
            }
            case WebDefaults:
            {
                //if web-defaults set the welcome-file-list first and
                //we're processing web.xml then reset the welcome-file-list
                if (!(descriptor instanceof DefaultsDescriptor) && !(descriptor instanceof OverrideDescriptor) && !(descriptor instanceof FragmentDescriptor))
                {
                    context.setWelcomeFiles(new String[0]);
                }
                addWelcomeFiles(context,node);
                break;
            }
            case WebOverride:
            {
                //web-override set the list, all other descriptors just merge in
                addWelcomeFiles(context,node);
                break;
            }
            case WebFragment:
            {
                //A web-fragment first set the welcome-file-list. Other descriptors just add. 
                addWelcomeFiles(context,node);
                break;
            }
        }
    }
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitLocaleEncodingList(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        Iterator<XmlParser.Node> iter = node.iterator("locale-encoding-mapping");
        while (iter.hasNext())
        {
            XmlParser.Node mapping = iter.next();
            String locale = mapping.getString("locale", false, true);
            String encoding = mapping.getString("encoding", false, true);
            
            if (encoding != null)
            {
                Origin o = context.getMetaData().getOrigin("locale-encoding."+locale);
                switch (o)
                {
                    case NotSet:
                    {
                        //no mapping for the locale yet, so set it
                        context.addLocaleEncoding(locale, encoding);
                        context.getMetaData().setOrigin("locale-encoding."+locale, descriptor);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    {
                        //a value was set in a web descriptor, only allow another web descriptor to change it (web-default/web-override)
                        if (!(descriptor instanceof FragmentDescriptor))
                        {
                            context.addLocaleEncoding(locale, encoding);
                            context.getMetaData().setOrigin("locale-encoding."+locale, descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a value was set by a web-fragment, all fragments must have the same value
                        if (!encoding.equals(context.getLocaleEncoding(locale)))
                            throw new IllegalStateException("Conflicting loacle-encoding mapping for locale "+locale+" in "+descriptor.getResource());
                        break;                    
                    }
                }
            }
        }
    }

    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitErrorPage(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String error = node.getString("error-code", false, true);
        int code=0;
        if (error == null || error.length() == 0) 
        {
            error = node.getString("exception-type", false, true);
            if (error == null || error.length() == 0)
                error = ErrorPageErrorHandler.GLOBAL_ERROR_PAGE;
        }
        else
            code=Integer.valueOf(error);
        
        String location = node.getString("location", false, true);
        ErrorPageErrorHandler handler = (ErrorPageErrorHandler)context.getErrorHandler();
        Origin o = context.getMetaData().getOrigin("error."+error);
        
        switch (o)
        {
            case NotSet:
            {
                //no error page setup for this code or exception yet
                if (code>0)
                    handler.addErrorPage(code,location);
                else
                    handler.addErrorPage(error,location);
                context.getMetaData().setOrigin("error."+error, descriptor);
                break;
            }
            case WebXml:
            case WebDefaults:
            case WebOverride:
            {
                //an error page setup was set in web.xml, only allow other web xml descriptors to override it
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    if (descriptor instanceof OverrideDescriptor || descriptor instanceof DefaultsDescriptor)
                    {
                        if (code>0)
                            handler.addErrorPage(code,location);
                        else
                            handler.addErrorPage(error,location);
                        context.getMetaData().setOrigin("error."+error, descriptor);
                    }
                    else
                        throw new IllegalStateException("Duplicate global error-page "+location);
                }
                break;
            }
            case WebFragment:
            {
                //another web fragment set the same error code or exception, if its different its an error
                if (!handler.getErrorPages().get(error).equals(location))
                    throw new IllegalStateException("Conflicting error-code or exception-type "+error+" in "+descriptor.getResource());
                break;
            }
        }
       
    }
    
    /**
     * @param context
     * @param node
     */
    protected void addWelcomeFiles(WebAppContext context, XmlParser.Node node)
    {
        Iterator<XmlParser.Node> iter = node.iterator("welcome-file");
        while (iter.hasNext())
        {
            XmlParser.Node indexNode = (XmlParser.Node) iter.next();
            String welcome = indexNode.toString(false, true);
            
            //Servlet Spec 3.0 p. 74 welcome files are additive
            if (welcome != null && welcome.trim().length() > 0)
                context.setWelcomeFiles((String[])LazyList.addToArray(context.getWelcomeFiles(),welcome,String.class));
        }
    }
    
    
    /**
     * @param servletName
     * @param node
     * @param context
     */
    protected ServletMapping addServletMapping (String servletName, XmlParser.Node node, WebAppContext context, Descriptor descriptor)
    {
        ServletMapping mapping = new ServletMapping();
        mapping.setServletName(servletName);
        
        List<String> paths = new ArrayList<String>();
        Iterator<XmlParser.Node> iter = node.iterator("url-pattern");
        while (iter.hasNext())
        {
            String p = iter.next().toString(false, true);
            p = normalizePattern(p);
            paths.add(p);
            context.getMetaData().setOrigin(servletName+".servlet.mapping."+p, descriptor);
        }
        mapping.setPathSpecs((String[]) paths.toArray(new String[paths.size()]));
        context.getServletHandler().addServletMapping(mapping);
        return mapping;
    }
    
    /**
     * @param filterName
     * @param node
     * @param context
     */
    protected void addFilterMapping (String filterName, XmlParser.Node node, WebAppContext context, Descriptor descriptor)
    {
        FilterMapping mapping = new FilterMapping();
        mapping.setFilterName(filterName);

        List<String> paths = new ArrayList<String>();
        Iterator<XmlParser.Node>  iter = node.iterator("url-pattern");
        while (iter.hasNext())
        {
            String p = iter.next().toString(false, true);
            p = normalizePattern(p);
            paths.add(p);
            context.getMetaData().setOrigin(filterName+".filter.mapping."+p, descriptor);
        }
        mapping.setPathSpecs((String[]) paths.toArray(new String[paths.size()]));

        List<String> names = new ArrayList<String>();
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

        context.getServletHandler().addFilterMapping(mapping);
    }
    
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitTagLib(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //Additive across web.xml and web-fragment.xml
        String uri = node.getString("taglib-uri", false, true);
        String location = node.getString("taglib-location", false, true);

        context.setResourceAlias(uri, location);
        
        JspConfig config = (JspConfig)context.getServletContext().getJspConfigDescriptor();
        if (config == null)
        {
            config = new JspConfig();
            context.getServletContext().setJspConfigDescriptor(config);
        }
        
        TagLib tl = new TagLib();
        tl.setTaglibLocation(location);
        tl.setTaglibURI(uri);
        config.addTaglibDescriptor(tl);
    }
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitJspConfig(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {   
        //Additive across web.xml and web-fragment.xml
        JspConfig config = (JspConfig)context.getServletContext().getJspConfigDescriptor();
        if (config == null)
        {
           config = new JspConfig();
           context.getServletContext().setJspConfigDescriptor(config);
        }
        
        
        for (int i = 0; i < node.size(); i++)
        {
            Object o = node.get(i);
            if (o instanceof XmlParser.Node && "taglib".equals(((XmlParser.Node) o).getTag())) 
                visitTagLib(context,descriptor, (XmlParser.Node) o);
        }

        // Map URLs from jsp property groups to JSP servlet.
        // this is more JSP stupidness creeping into the servlet spec
        Iterator<XmlParser.Node> iter = node.iterator("jsp-property-group");
        List<String> paths = new ArrayList<String>();
        while (iter.hasNext())
        {
            JspPropertyGroup jpg = new JspPropertyGroup();
            config.addJspPropertyGroup(jpg);
            XmlParser.Node group = iter.next();
            
            //url-patterns
            Iterator<XmlParser.Node> iter2 = group.iterator("url-pattern");
            while (iter2.hasNext())
            {
                String url = iter2.next().toString(false, true);
                url = normalizePattern(url);
                paths.add( url);
                jpg.addUrlPattern(url);
            }
            
            jpg.setElIgnored(group.getString("el-ignored", false, true));
            jpg.setPageEncoding(group.getString("page-encoding", false, true));
            jpg.setScriptingInvalid(group.getString("scripting-invalid", false, true));
            jpg.setIsXml(group.getString("is-xml", false, true));
            jpg.setDeferredSyntaxAllowedAsLiteral(group.getString("deferred-syntax-allowed-as-literal", false, true));
            jpg.setTrimDirectiveWhitespaces(group.getString("trim-directive-whitespaces", false, true));
            jpg.setDefaultContentType(group.getString("default-content-type", false, true));
            jpg.setBuffer(group.getString("buffer", false, true));
            jpg.setErrorOnUndeclaredNamespace(group.getString("error-on-undeclared-namespace", false, true));
            
            //preludes
            Iterator<XmlParser.Node> preludes = group.iterator("include-prelude");
            while (preludes.hasNext())
            {
                String prelude = preludes.next().toString(false, true);
                jpg.addIncludePrelude(prelude);
            }
            //codas
            Iterator<XmlParser.Node> codas = group.iterator("include-coda");
            while (codas.hasNext())
            {
                String coda = codas.next().toString(false, true);
                jpg.addIncludeCoda(coda);
            }
            
            if (LOG.isDebugEnabled()) LOG.debug(config.toString());
        }

        if (paths.size() > 0)
        {
            ServletHandler handler = context.getServletHandler();
            ServletHolder jsp_pg_servlet = handler.getServlet(JspPropertyGroupServlet.NAME);
            if (jsp_pg_servlet==null)
            {
                jsp_pg_servlet=new ServletHolder(JspPropertyGroupServlet.NAME,new JspPropertyGroupServlet(context,handler));
                handler.addServlet(jsp_pg_servlet);
            }

            ServletMapping mapping = new ServletMapping();
            mapping.setServletName(JspPropertyGroupServlet.NAME);
            mapping.setPathSpecs(paths.toArray(new String[paths.size()]));
            context.getServletHandler().addServletMapping(mapping);
        }
    }
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitSecurityConstraint(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        Constraint scBase = new Constraint();

        //ServletSpec 3.0, p74 security-constraints, as minOccurs > 1, are additive 
        //across fragments
        
        //TODO: need to remember origin of the constraints
        try
        {
            XmlParser.Node auths = node.get("auth-constraint");

            if (auths != null)
            {
                scBase.setAuthenticate(true);
                // auth-constraint
                Iterator<XmlParser.Node> iter = auths.iterator("role-name");
                List<String> roles = new ArrayList<String>();
                while (iter.hasNext())
                {
                    String role = iter.next().toString(false, true);
                    roles.add(role);
                }
                scBase.setRoles(roles.toArray(new String[roles.size()]));
            }

            XmlParser.Node data = node.get("user-data-constraint");
            if (data != null)
            {
                data = data.get("transport-guarantee");
                String guarantee = data.toString(false, true).toUpperCase(Locale.ENGLISH);
                if (guarantee == null || guarantee.length() == 0 || "NONE".equals(guarantee))
                    scBase.setDataConstraint(Constraint.DC_NONE);
                else if ("INTEGRAL".equals(guarantee))
                    scBase.setDataConstraint(Constraint.DC_INTEGRAL);
                else if ("CONFIDENTIAL".equals(guarantee))
                    scBase.setDataConstraint(Constraint.DC_CONFIDENTIAL);
                else
                {
                    LOG.warn("Unknown user-data-constraint:" + guarantee);
                    scBase.setDataConstraint(Constraint.DC_CONFIDENTIAL);
                }
            }
            Iterator<XmlParser.Node> iter = node.iterator("web-resource-collection");
            while (iter.hasNext())
            {
                XmlParser.Node collection =  iter.next();
                String name = collection.getString("web-resource-name", false, true);
                Constraint sc = (Constraint) scBase.clone();
                sc.setName(name);

                Iterator<XmlParser.Node> iter2 = collection.iterator("url-pattern");
                while (iter2.hasNext())
                {
                    String url = iter2.next().toString(false, true);
                    url = normalizePattern(url);
                    //remember origin so we can process ServletRegistration.Dynamic.setServletSecurityElement() correctly
                    context.getMetaData().setOrigin("constraint.url."+url, descriptor);
                    
                    Iterator<XmlParser.Node> iter3 = collection.iterator("http-method");
                    Iterator<XmlParser.Node> iter4 = collection.iterator("http-method-omission");
                   
                    if (iter3.hasNext())
                    {
                        if (iter4.hasNext())
                            throw new IllegalStateException ("web-resource-collection cannot contain both http-method and http-method-omission");
                        
                        //configure all the http-method elements for each url
                        while (iter3.hasNext())
                        {
                            String method = ((XmlParser.Node) iter3.next()).toString(false, true);
                            ConstraintMapping mapping = new ConstraintMapping();
                            mapping.setMethod(method);
                            mapping.setPathSpec(url);
                            mapping.setConstraint(sc);                                                      
                            ((ConstraintAware)context.getSecurityHandler()).addConstraintMapping(mapping);
                        }
                    }
                    else if (iter4.hasNext())
                    {
                        //configure all the http-method-omission elements for each url
                        while (iter4.hasNext())
                        {
                            String method = ((XmlParser.Node)iter4.next()).toString(false, true);
                            ConstraintMapping mapping = new ConstraintMapping();
                            mapping.setMethodOmissions(new String[]{method});
                            mapping.setPathSpec(url);
                            mapping.setConstraint(sc);
                            ((ConstraintAware)context.getSecurityHandler()).addConstraintMapping(mapping);
                        }
                    }
                    else
                    {
                        //No http-methods or http-method-omissions specified, the constraint applies to all
                        ConstraintMapping mapping = new ConstraintMapping();
                        mapping.setPathSpec(url);
                        mapping.setConstraint(sc);
                        ((ConstraintAware)context.getSecurityHandler()).addConstraintMapping(mapping);
                    }
                } 
            }
        }
        catch (CloneNotSupportedException e)
        {
            LOG.warn(e);
        }
    }
    
    /**
     * @param context
     * @param descriptor
     * @param node
     * @throws Exception
     */
    protected void visitLoginConfig(WebAppContext context, Descriptor descriptor, XmlParser.Node node) throws Exception
    {
        //ServletSpec 3.0 p74 says elements present 0/1 time if specified in web.xml take
        //precendece over any web-fragment. If not specified in web.xml, then if specified
        //in a web-fragment must be the same across all web-fragments.
        XmlParser.Node method = node.get("auth-method");
        if (method != null)
        {
            //handle auth-method merge
            Origin o = context.getMetaData().getOrigin("auth-method");
            switch (o)
            {
                case NotSet:
                {
                    //not already set, so set it now
                    context.getSecurityHandler().setAuthMethod(method.toString(false, true));
                    context.getMetaData().setOrigin("auth-method", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //if it was already set by a web xml descriptor and we're parsing another web xml descriptor, then override it
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        context.getSecurityHandler().setAuthMethod(method.toString(false, true));
                        context.getMetaData().setOrigin("auth-method", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //it was already set by another fragment, if we're parsing a fragment, the values must match
                    if (!context.getSecurityHandler().getAuthMethod().equals(method.toString(false, true)))
                        throw new IllegalStateException("Conflicting auth-method value in "+descriptor.getResource());
                    break;
                }
            } 
            
            //handle realm-name merge
            XmlParser.Node name = node.get("realm-name");
            String nameStr = (name == null ? "default" : name.toString(false, true));
            o = context.getMetaData().getOrigin("realm-name");
            switch (o)
            {
                case NotSet:
                {
                    //no descriptor has set the realm-name yet, so set it
                    context.getSecurityHandler().setRealmName(nameStr);
                    context.getMetaData().setOrigin("realm-name", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //set by a web xml file (web.xml/web-default.xm/web-override.xml), only allow it to be changed by another web xml file
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        context.getSecurityHandler().setRealmName(nameStr);
                        context.getMetaData().setOrigin("realm-name", descriptor); 
                    }
                    break;
                }
                case WebFragment:
                {
                    //a fragment set it, and we must be parsing another fragment, so the values must match
                    if (!context.getSecurityHandler().getRealmName().equals(nameStr))
                        throw new IllegalStateException("Conflicting realm-name value in "+descriptor.getResource());
                    break;
                }
            }
 
            if (Constraint.__FORM_AUTH.equals(context.getSecurityHandler().getAuthMethod()))
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
                    o = context.getMetaData().getOrigin("form-login-page");
                    switch (o)
                    {
                        case NotSet:
                        {
                            //Never been set before, so accept it
                            context.getSecurityHandler().setInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE,loginPageName);
                            context.getMetaData().setOrigin("form-login-page",descriptor);
                            break;
                        }
                        case WebXml:
                        case WebDefaults:
                        case WebOverride:
                        {
                            //a web xml descriptor previously set it, only allow another one to change it (web.xml/web-default.xml/web-override.xml)
                            if (!(descriptor instanceof FragmentDescriptor))
                            {
                                context.getSecurityHandler().setInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE,loginPageName);
                                context.getMetaData().setOrigin("form-login-page",descriptor);
                            }
                            break;
                        }
                        case WebFragment:
                        {
                            //a web-fragment previously set it. We must be parsing yet another web-fragment, so the values must agree
                            if (!context.getSecurityHandler().getInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE).equals(loginPageName))
                                throw new IllegalStateException("Conflicting form-login-page value in "+descriptor.getResource());
                            break;
                        }
                    }
                    
                    //handle form-error-page
                    o = context.getMetaData().getOrigin("form-error-page");
                    switch (o)
                    {
                        case NotSet:
                        {
                            //Never been set before, so accept it
                            context.getSecurityHandler().setInitParameter(FormAuthenticator.__FORM_ERROR_PAGE,errorPageName);
                            context.getMetaData().setOrigin("form-error-page",descriptor);
                            break;
                        }
                        case WebXml:
                        case WebDefaults:
                        case WebOverride:
                        {
                            //a web xml descriptor previously set it, only allow another one to change it (web.xml/web-default.xml/web-override.xml)
                            if (!(descriptor instanceof FragmentDescriptor))
                            {
                                context.getSecurityHandler().setInitParameter(FormAuthenticator.__FORM_ERROR_PAGE,errorPageName);
                                context.getMetaData().setOrigin("form-error-page",descriptor);
                            }
                            break;
                        }
                        case WebFragment:
                        {
                            //a web-fragment previously set it. We must be parsing yet another web-fragment, so the values must agree
                            if (!context.getSecurityHandler().getInitParameter(FormAuthenticator.__FORM_ERROR_PAGE).equals(errorPageName))
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
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitSecurityRole(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //ServletSpec 3.0, p74 elements with multiplicity >1 are additive when merged
        XmlParser.Node roleNode = node.get("role-name");
        String role = roleNode.toString(false, true);
        ((ConstraintAware)context.getSecurityHandler()).addRole(role);
    }
    
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitFilter(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String name = node.getString("filter-name", false, true);
        FilterHolder holder = context.getServletHandler().getFilter(name);
        if (holder == null)
        {
            holder = context.getServletHandler().newFilterHolder(Holder.Source.DESCRIPTOR);
            holder.setName(name);
            context.getServletHandler().addFilter(holder);
        }

        String filter_class = node.getString("filter-class", false, true);
        if (filter_class != null) 
        {
            ((WebDescriptor)descriptor).addClassName(filter_class);
            
            Origin o = context.getMetaData().getOrigin(name+".filter.filter-class");
            switch (o)
            {
                case NotSet:
                {
                    //no class set yet
                    holder.setClassName(filter_class);
                    context.getMetaData().setOrigin(name+".filter.filter-class", descriptor);
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
                        context.getMetaData().setOrigin(name+".filter.filter-class", descriptor); 
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

        Iterator<XmlParser.Node>  iter = node.iterator("init-param");
        while (iter.hasNext())
        {
            XmlParser.Node paramNode = iter.next();
            String pname = paramNode.getString("param-name", false, true);
            String pvalue = paramNode.getString("param-value", false, true);
            
            Origin origin = context.getMetaData().getOrigin(name+".filter.init-param."+pname);
            switch (origin)
            {
                case NotSet:
                {
                    //init-param not already set, so set it
                    holder.setInitParameter(pname, pvalue); 
                    context.getMetaData().setOrigin(name+".filter.init-param."+pname, descriptor);
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
                        context.getMetaData().setOrigin(name+".filter.init-param."+pname, descriptor);
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
            Origin o = context.getMetaData().getOrigin(name+".filter.async-supported");
            switch (o)
            {
                case NotSet:
                {
                    //set it
                    holder.setAsyncSupported(val);
                    context.getMetaData().setOrigin(name+".filter.async-supported", descriptor);
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
                        context.getMetaData().setOrigin(name+".filter.async-supported", descriptor);  
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

    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitFilterMapping(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //Servlet Spec 3.0, p74
        //filter-mappings are always additive, whether from web xml descriptors (web.xml/web-default.xml/web-override.xml) or web-fragments.
        //Maintenance update 3.0a to spec:
        //  Updated 8.2.3.g.v to say <servlet-mapping> elements are additive across web-fragments. 
      
        
        String filter_name = node.getString("filter-name", false, true);
        
        Origin origin = context.getMetaData().getOrigin(filter_name+".filter.mappings");
        
        switch (origin)
        {
            case NotSet:
            {
                //no filtermappings for this filter yet defined
                context.getMetaData().setOrigin(filter_name+".filter.mappings", descriptor);
                addFilterMapping(filter_name, node, context, descriptor);
                break;
            }
            case WebDefaults:
            case WebOverride:
            case WebXml:
            {
                //filter mappings defined in a web xml file. If we're processing a fragment, we ignore filter mappings.
                if (!(descriptor instanceof FragmentDescriptor))
                {
                   addFilterMapping(filter_name, node, context, descriptor);
                }
                break;
            }
            case WebFragment:
            {
                //filter mappings first defined in a web-fragment, allow other fragments to add
                addFilterMapping(filter_name, node, context, descriptor);
                break;
            }
        }
    }

    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitListener(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String className = node.getString("listener-class", false, true);
        EventListener listener = null;
        try
        {
            if (className != null && className.length()> 0)
            {
                //Servlet Spec 3.0 p 74
                //Duplicate listener declarations don't result in duplicate listener instances
                EventListener[] listeners=context.getEventListeners();
                if (listeners!=null)
                {
                    for (EventListener l : listeners)
                    {
                        if (l.getClass().getName().equals(className))
                            return;
                    }
                }
                
                ((WebDescriptor)descriptor).addClassName(className);

                Class<? extends EventListener> listenerClass = (Class<? extends EventListener>)context.loadClass(className);
                listener = newListenerInstance(context,listenerClass);
                if (!(listener instanceof EventListener))
                {
                    LOG.warn("Not an EventListener: " + listener);
                    return;
                }
                context.addEventListener(listener);
                context.getMetaData().setOrigin(className+".listener", descriptor);
                
            }
        }
        catch (Exception e)
        {
            LOG.warn("Could not instantiate listener " + className, e);
            return;
        }
    }
    
    /**
     * @param context
     * @param descriptor
     * @param node
     */
    protected void visitDistributable(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        // the element has no content, so its simple presence
        // indicates that the webapp is distributable...
        //Servlet Spec 3.0 p.74  distributable only if all fragments are distributable
        ((WebDescriptor)descriptor).setDistributable(true);
    }
    
    /**
     * @param context
     * @param clazz
     * @return the new event listener
     * @throws ServletException
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    protected EventListener newListenerInstance(WebAppContext context,Class<? extends EventListener> clazz) throws ServletException, InstantiationException, IllegalAccessException
    {
        try
        {
            return context.getServletContext().createListener(clazz);
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
    
    /**
     * @param p
     * @return the normalized pattern
     */
    protected String normalizePattern(String p)
    {
        if (p != null && p.length() > 0 && !p.startsWith("/") && !p.startsWith("*")) return "/" + p;
        return p;
    }

  
}
