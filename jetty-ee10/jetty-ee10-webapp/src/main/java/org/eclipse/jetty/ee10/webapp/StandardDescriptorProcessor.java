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

package org.eclipse.jetty.ee10.webapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.SessionTrackingMode;
import org.eclipse.jetty.ee10.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.FilterMapping;
import org.eclipse.jetty.ee10.servlet.ListenerHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler.JspConfig;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler.JspPropertyGroup;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler.TagLib;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.ServletMapping;
import org.eclipse.jetty.ee10.servlet.Source;
import org.eclipse.jetty.ee10.servlet.security.ConstraintAware;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.authentication.FormAuthenticator;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.xml.XmlParser;
import org.eclipse.jetty.xml.XmlParser.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * StandardDescriptorProcessor.
 * <p>
 * Process the web.xml, web-defaults.xml, web-overrides.xml, and web-fragment.xml descriptors.
 */
public class StandardDescriptorProcessor extends IterativeDescriptorProcessor
{
    private static final Logger LOG = LoggerFactory.getLogger(StandardDescriptorProcessor.class);

    public static final String STANDARD_PROCESSOR = "org.eclipse.jetty.standardDescriptorProcessor";

    final Map<String, FilterHolder> _filterHolderMap = new HashMap<>();
    final List<FilterHolder> _filterHolders = new ArrayList<>();
    final List<FilterMapping> _filterMappings = new ArrayList<>();
    final Map<String, ServletHolder> _servletHolderMap = new HashMap<>();
    final List<ServletHolder> _servletHolders = new ArrayList<>();
    final List<ServletMapping> _servletMappings = new ArrayList<>();

    public StandardDescriptorProcessor()
    {
        try
        {
            registerVisitor("context-param", this.getClass().getMethod("visitContextParam", __signature));
            registerVisitor("display-name", this.getClass().getMethod("visitDisplayName", __signature));
            registerVisitor("servlet", this.getClass().getMethod("visitServlet", __signature));
            registerVisitor("servlet-mapping", this.getClass().getMethod("visitServletMapping", __signature));
            registerVisitor("session-config", this.getClass().getMethod("visitSessionConfig", __signature));
            registerVisitor("mime-mapping", this.getClass().getMethod("visitMimeMapping", __signature));
            registerVisitor("welcome-file-list", this.getClass().getMethod("visitWelcomeFileList", __signature));
            registerVisitor("locale-encoding-mapping-list", this.getClass().getMethod("visitLocaleEncodingList", __signature));
            registerVisitor("error-page", this.getClass().getMethod("visitErrorPage", __signature));
            registerVisitor("taglib", this.getClass().getMethod("visitTagLib", __signature));
            registerVisitor("jsp-config", this.getClass().getMethod("visitJspConfig", __signature));
            registerVisitor("security-constraint", this.getClass().getMethod("visitSecurityConstraint", __signature));
            registerVisitor("login-config", this.getClass().getMethod("visitLoginConfig", __signature));
            registerVisitor("security-role", this.getClass().getMethod("visitSecurityRole", __signature));
            registerVisitor("filter", this.getClass().getMethod("visitFilter", __signature));
            registerVisitor("filter-mapping", this.getClass().getMethod("visitFilterMapping", __signature));
            registerVisitor("listener", this.getClass().getMethod("visitListener", __signature));
            registerVisitor("deny-uncovered-http-methods", this.getClass().getMethod("visitDenyUncoveredHttpMethods", __signature));
            registerVisitor("default-context-path", this.getClass().getMethod("visitDefaultContextPath", __signature));
            registerVisitor("request-character-encoding", this.getClass().getMethod("visitRequestCharacterEncoding", __signature));
            registerVisitor("response-character-encoding", this.getClass().getMethod("visitResponseCharacterEncoding", __signature));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(WebAppContext context, Descriptor descriptor)
    {
        for (FilterHolder h : context.getServletHandler().getFilters())
        {
            _filterHolderMap.put(h.getName(), h);
            _filterHolders.add(h);
        }
        if (context.getServletHandler().getFilterMappings() != null)
            _filterMappings.addAll(Arrays.asList(context.getServletHandler().getFilterMappings()));
        for (ServletHolder h : context.getServletHandler().getServlets())
        {
            _servletHolderMap.put(h.getName(), h);
            _servletHolders.add(h);
        }
        if (context.getServletHandler().getServletMappings() != null)
            _servletMappings.addAll(Arrays.asList(context.getServletHandler().getServletMappings()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void end(WebAppContext context, Descriptor descriptor)
    {
        context.getServletHandler().setFilters(_filterHolders.toArray(new FilterHolder[_filterHolderMap.size()]));
        context.getServletHandler().setServlets(_servletHolders.toArray(new ServletHolder[_servletHolderMap.size()]));

        context.getServletHandler().setFilterMappings(_filterMappings.toArray(new FilterMapping[_filterMappings.size()]));
        context.getServletHandler().setServletMappings(_servletMappings.toArray(new ServletMapping[_servletMappings.size()]));

        _filterHolderMap.clear();
        _filterHolders.clear();
        _filterMappings.clear();
        _servletHolderMap.clear();
        _servletHolders.clear();
        _servletMappings.clear();
    }

    public void visitContextParam(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String name = node.getString("param-name", false, true);
        String value = node.getString("param-value", false, true);
        Origin origin = context.getMetaData().getOrigin("context-param." + name);
        switch (origin)
        {
            case NotSet:
            {
                //just set it
                context.getInitParams().put(name, value);
                context.getMetaData().setOrigin("context-param." + name, descriptor);
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
                    context.getMetaData().setOrigin("context-param." + name, descriptor);
                }
                break;
            }
            case WebFragment:
            {
                //previously set by a web-fragment, this fragment's value must be the same
                if (descriptor instanceof FragmentDescriptor)
                {
                    if (!((String)context.getInitParams().get(name)).equals(value))
                        throw new IllegalStateException("Conflicting context-param " + name + "=" + value + " in " + descriptor.getResource());
                }
                break;
            }
            default:
                unknownOrigin(origin);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("ContextParam: {}={}", name, value);
    }

    public void visitDisplayName(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //Servlet Spec 3.0 p. 74 Ignore from web-fragments
        if (!(descriptor instanceof FragmentDescriptor))
        {
            context.setDisplayName(node.toString(false, true));
            context.getMetaData().setOrigin("display-name", descriptor);
        }
    }

    public void visitServlet(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        final String id = node.getAttribute("id");
        final String name = node.getString("servlet-name", false, true);
        ServletHolder holder = _servletHolderMap.get(name);

        //If servlet of that name does not already exist, create it.
        if (holder == null)
        {
            holder = context.getServletHandler().newServletHolder(new Source(Source.Origin.DESCRIPTOR, descriptor.getResource().toString()));
            holder.setName(name);
            _servletHolderMap.put(name, holder);
            _servletHolders.add(holder);
        }

        // init params
        Iterator<?> iParamsIter = node.iterator("init-param");
        while (iParamsIter.hasNext())
        {
            XmlParser.Node paramNode = (XmlParser.Node)iParamsIter.next();
            String pname = paramNode.getString("param-name", false, true);
            String pvalue = paramNode.getString("param-value", false, true);
            String originName = name + ".servlet.init-param." + pname;

            Descriptor originDescriptor = context.getMetaData().getOriginDescriptor(originName);
            Origin origin = context.getMetaData().getOrigin(originName);
            switch (origin)
            {
                case NotSet:
                {
                    //init-param not already set, so set it
                    holder.setInitParameter(pname, pvalue);
                    context.getMetaData().setOrigin(originName, descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //previously set by a web xml descriptor, if we're parsing another web xml descriptor allow override as long as it is from a different descriptor
                    //ie ignore setting more than once within the same descriptor
                    //otherwise just ignore it
                    if (!(descriptor instanceof FragmentDescriptor) && (descriptor != originDescriptor))
                    {
                        holder.setInitParameter(pname, pvalue);
                        context.getMetaData().setOrigin(originName, descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //previously set by a web-fragment, make sure that the value matches, otherwise its an error
                    if ((descriptor != originDescriptor) && !holder.getInitParameter(pname).equals(pvalue))
                        throw new IllegalStateException("Mismatching init-param " + pname + "=" + pvalue + " in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(origin);
            }
        }

        String servletClass = node.getString("servlet-class", false, true);
        if ("".equals(servletClass))
            servletClass = null;

        //Handle the default jsp servlet instance
        if (id != null && id.equals("jsp") && servletClass != null)
        {
            try
            {
                Loader.loadClass(servletClass);
            }
            catch (ClassNotFoundException e)
            {
                LOG.info("NO JSP Support for {}, did not find {}", context.getContextPath(), servletClass);
                servletClass = "org.eclipse.jetty.servlet.NoJspServlet";
            }
        }

        //Set the servlet-class
        if (servletClass != null)
        {
            ((WebDescriptor)descriptor).addClassName(servletClass);
            Origin origin = context.getMetaData().getOrigin(name + ".servlet.servlet-class");
            switch (origin)
            {
                case NotSet:
                {
                    //the class of the servlet has not previously been set, so set it
                    holder.setClassName(servletClass);
                    context.getMetaData().setOrigin(name + ".servlet.servlet-class", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //the class of the servlet was set by a web xml file, only allow web-override/web-default to change it
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        holder.setClassName(servletClass);
                        context.getMetaData().setOrigin(name + ".servlet.servlet-class", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //the class was set by another fragment, ensure this fragment's value is the same
                    if (!servletClass.equals(holder.getClassName()))
                        throw new IllegalStateException("Conflicting servlet-class " + servletClass + " in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(origin);
            }
        }

        // Handle JSP file
        String jspFile = node.getString("jsp-file", false, true);
        if (jspFile != null)
            holder.setForcedPath(jspFile);

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
                    if (s != null && s.trim().length() > 0)
                        order = Integer.parseInt(s);
                }
                catch (Exception e)
                {
                    LOG.warn("Cannot parse load-on-startup {}. Please use integer", s);
                    LOG.trace("IGNORED", e);
                }
            }

            Origin origin = context.getMetaData().getOrigin(name + ".servlet.load-on-startup");
            switch (origin)
            {
                case NotSet:
                {
                    //not already set, so set it now
                    holder.setInitOrder(order);
                    context.getMetaData().setOrigin(name + ".servlet.load-on-startup", descriptor);
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
                        context.getMetaData().setOrigin(name + ".servlet.load-on-startup", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //it was already set by another fragment, if we're parsing a fragment, the values must match
                    if (order != holder.getInitOrder())
                        throw new IllegalStateException("Conflicting load-on-startup value in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(origin);
            }
        }

        Iterator<Node> sRefsIter = node.iterator("security-role-ref");
        while (sRefsIter.hasNext())
        {
            XmlParser.Node securityRef = (XmlParser.Node)sRefsIter.next();
            String roleName = securityRef.getString("role-name", false, true);
            String roleLink = securityRef.getString("role-link", false, true);
            if (roleName != null && roleName.length() > 0 && roleLink != null && roleLink.length() > 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("link role {} to {} for {}", roleName, roleLink, this);
                Origin origin = context.getMetaData().getOrigin(name + ".servlet.role-name." + roleName);
                switch (origin)
                {
                    case NotSet:
                    {
                        //set it
                        holder.setUserRoleLink(roleName, roleLink);
                        context.getMetaData().setOrigin(name + ".servlet.role-name." + roleName, descriptor);
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
                            context.getMetaData().setOrigin(name + ".servlet.role-name." + roleName, descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        if (!holder.getUserRoleLink(roleName).equals(roleLink))
                            throw new IllegalStateException("Conflicting role-link for role-name " + roleName + " for servlet " + name + " in " + descriptor.getResource());
                        break;
                    }
                    default:
                        unknownOrigin(origin);
                }
            }
            else
            {
                LOG.warn("Ignored invalid security-role-ref element: servlet-name={}, {}", holder.getName(), securityRef);
            }
        }

        XmlParser.Node runAs = node.get("run-as");
        if (runAs != null)
        {
            String roleName = runAs.getString("role-name", false, true);

            if (roleName != null)
            {
                Origin origin = context.getMetaData().getOrigin(name + ".servlet.run-as");
                switch (origin)
                {
                    case NotSet:
                    {
                        //run-as not set, so set it
                        holder.setRunAsRole(roleName);
                        context.getMetaData().setOrigin(name + ".servlet.run-as", descriptor);
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
                            context.getMetaData().setOrigin(name + ".servlet.run-as", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //run-as was set by another fragment, this fragment must show the same value
                        if (!holder.getRunAsRole().equals(roleName))
                            throw new IllegalStateException("Conflicting run-as role " + roleName + " for servlet " + name + " in " + descriptor.getResource());
                        break;
                    }
                    default:
                        unknownOrigin(origin);
                }
            }
        }

        String async = node.getString("async-supported", false, true);
        if (async != null)
        {
            boolean val = async.length() == 0 || Boolean.parseBoolean(async);
            Origin origin = context.getMetaData().getOrigin(name + ".servlet.async-supported");
            switch (origin)
            {
                case NotSet:
                {
                    //set it
                    holder.setAsyncSupported(val);
                    context.getMetaData().setOrigin(name + ".servlet.async-supported", descriptor);
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
                        context.getMetaData().setOrigin(name + ".servlet.async-supported", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //async-supported set by another fragment, this fragment's value must match
                    if (holder.isAsyncSupported() != val)
                        throw new IllegalStateException("Conflicting async-supported=" + async + " for servlet " + name + " in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(origin);
            }
        }

        String enabled = node.getString("enabled", false, true);
        if (enabled != null)
        {
            boolean isEnabled = enabled.length() == 0 || Boolean.parseBoolean(enabled);
            Origin origin = context.getMetaData().getOrigin(name + ".servlet.enabled");
            switch (origin)
            {
                case NotSet:
                {
                    //hasn't been set yet, so set it
                    holder.setEnabled(isEnabled);
                    context.getMetaData().setOrigin(name + ".servlet.enabled", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //was set in a web xml descriptor, only allow override from another web xml descriptor
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        holder.setEnabled(isEnabled);
                        context.getMetaData().setOrigin(name + ".servlet.enabled", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //was set by another fragment, this fragment's value must match
                    if (holder.isEnabled() != isEnabled)
                        throw new IllegalStateException("Conflicting value of servlet enabled for servlet " + name + " in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(origin);
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
            String threshold = multipart.getString("file-size-threshold", false, true);
            MultipartConfigElement element = new MultipartConfigElement(location,
                (maxFile == null || "".equals(maxFile) ? -1L : Long.parseLong(maxFile)),
                (maxRequest == null || "".equals(maxRequest) ? -1L : Long.parseLong(maxRequest)),
                (threshold == null || "".equals(threshold) ? 0 : Integer.parseInt(threshold)));

            Origin origin = context.getMetaData().getOrigin(name + ".servlet.multipart-config");
            switch (origin)
            {
                case NotSet:
                {
                    //hasn't been set, so set it
                    holder.getRegistration().setMultipartConfig(element);
                    context.getMetaData().setOrigin(name + ".servlet.multipart-config", descriptor);
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
                        context.getMetaData().setOrigin(name + ".servlet.multipart-config", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //another fragment set the value, this fragment's values must match exactly or it is an error
                    MultipartConfigElement cfg = ((ServletHolder.Registration)holder.getRegistration()).getMultipartConfig();

                    if (cfg.getMaxFileSize() != element.getMaxFileSize())
                        throw new IllegalStateException("Conflicting multipart-config max-file-size for servlet " + name + " in " + descriptor.getResource());
                    if (cfg.getMaxRequestSize() != element.getMaxRequestSize())
                        throw new IllegalStateException("Conflicting multipart-config max-request-size for servlet " + name + " in " + descriptor.getResource());
                    if (cfg.getFileSizeThreshold() != element.getFileSizeThreshold())
                        throw new IllegalStateException("Conflicting multipart-config file-size-threshold for servlet " + name + " in " + descriptor.getResource());
                    if ((cfg.getLocation() != null && (element.getLocation() == null || element.getLocation().length() == 0)) ||
                        (cfg.getLocation() == null && (element.getLocation() != null || element.getLocation().length() > 0)))
                        throw new IllegalStateException("Conflicting multipart-config location for servlet " + name + " in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(origin);
            }
        }
    }

    public void visitServletMapping(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //Servlet Spec 3.0, p74
        //servlet-mappings are always additive, whether from web xml descriptors (web.xml/web-default.xml/web-override.xml) or web-fragments.
        //Maintenance update 3.0a to spec:
        //  Updated 8.2.3.g.v to say <servlet-mapping> elements are additive across web-fragments.
        //  <servlet-mapping> declared in web.xml overrides the mapping for the servlet specified in the web-fragment.xml

        String servletName = node.getString("servlet-name", false, true);
        Origin origin = context.getMetaData().getOrigin(servletName + ".servlet.mappings");
        switch (origin)
        {
            case NotSet:
            {
                //no servlet mappings
                context.getMetaData().setOrigin(servletName + ".servlet.mappings", descriptor);
                addServletMapping(servletName, node, context, descriptor);
                break;
            }
            case WebDefaults:
            case WebXml:
            case WebOverride:
            {
                //previously set by a web xml descriptor, if we're parsing another web xml descriptor allow override
                //otherwise just ignore it as web.xml takes precedence (pg 8-81 5.g.vi)
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    addServletMapping(servletName, node, context, descriptor);
                }
                break;
            }
            case WebFragment:
            {
                //mappings previously set by another web-fragment, so merge in this web-fragment's mappings
                addServletMapping(servletName, node, context, descriptor);
                break;
            }
            default:
                unknownOrigin(origin);
        }
    }

    public void visitSessionConfig(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        if (context.getSessionHandler() == null)
            return; //no session handler, ignore session setup

        XmlParser.Node tNode = node.get("session-timeout");
        if (tNode != null)
        {
            long mins = Long.parseLong(tNode.toString(false, true));
            if (TimeUnit.MINUTES.toSeconds(mins) > Integer.MAX_VALUE)
                throw new IllegalStateException("Max session-timeout in minutes is " + TimeUnit.SECONDS.toMinutes(Integer.MAX_VALUE));
            context.getServletContext().setSessionTimeout((int)mins);
            context.getMetaData().setOrigin("session.timeout", descriptor);
        }

        //Servlet Spec 3.0
        // <tracking-mode>
        // this is additive across web-fragments
        Iterator<Node> iter = node.iterator("tracking-mode");
        if (iter.hasNext())
        {
            Set<SessionTrackingMode> modes = null;
            Origin o = context.getMetaData().getOrigin("session.tracking-modes");
            switch (o)
            {
                case NotSet://not previously set, starting fresh
                case WebDefaults://previously set in web defaults, allow this descriptor to start fresh
                {

                    modes = new HashSet<SessionTrackingMode>();
                    context.getMetaData().setOrigin("session.tracking-modes", descriptor);
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
                        modes = new HashSet<SessionTrackingMode>(context.getSessionHandler().getEffectiveSessionTrackingModes());
                    context.getMetaData().setOrigin("session.tracking-modes", descriptor);
                    break;
                }
                default:
                    unknownOrigin(o);
            }

            while (iter.hasNext())
            {
                XmlParser.Node mNode = (XmlParser.Node)iter.next();
                String trackMode = mNode.toString(false, true);
                SessionTrackingMode mode = SessionTrackingMode.valueOf(trackMode);
                modes.add(mode);
                context.getMetaData().setOrigin("session.tracking-mode." + mode, descriptor);
            }
            context.getSessionHandler().setSessionTrackingModes(modes);
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
                Origin origin = context.getMetaData().getOrigin("cookie-config.name");
                switch (origin)
                {
                    case NotSet:
                    {
                        //no <cookie-config><name> set yet, accept it
                        context.getSessionHandler().getSessionCookieConfig().setName(name);
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
                            context.getSessionHandler().getSessionCookieConfig().setName(name);
                            context.getMetaData().setOrigin("cookie-config.name", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        //TODO: evaluate that is can never be null?!
                        if (!name.equals(context.getSessionHandler().getSessionCookie()))
                            throw new IllegalStateException("Conflicting cookie-config name " + name + " in " + descriptor.getResource());
                        break;
                    }
                    default:
                        unknownOrigin(origin);
                }
            }

            //  <domain>
            String domain = cookieConfig.getString("domain", false, true);
            if (domain != null)
            {
                Origin origin = context.getMetaData().getOrigin("cookie-config.domain");
                switch (origin)
                {
                    case NotSet:
                    {
                        //no <cookie-config><domain> set yet, accept it
                        context.getSessionHandler().getSessionCookieConfig().setDomain(domain);
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
                            context.getSessionHandler().getSessionCookieConfig().setDomain(domain);
                            context.getMetaData().setOrigin("cookie-config.domain", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (!context.getSessionHandler().getSessionCookieConfig().getDomain().equals(domain))
                            throw new IllegalStateException("Conflicting cookie-config domain " + domain + " in " + descriptor.getResource());
                        break;
                    }
                    default:
                        unknownOrigin(origin);
                }
            }

            //  <path>
            String path = cookieConfig.getString("path", false, true);
            if (path != null)
            {
                Origin origin = context.getMetaData().getOrigin("cookie-config.path");
                switch (origin)
                {
                    case NotSet:
                    {
                        //no <cookie-config><domain> set yet, accept it
                        context.getSessionHandler().getSessionCookieConfig().setPath(path);
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
                            context.getSessionHandler().getSessionCookieConfig().setPath(path);
                            context.getMetaData().setOrigin("cookie-config.path", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (!path.equals(context.getSessionHandler().getSessionCookieConfig().getPath()))
                            throw new IllegalStateException("Conflicting cookie-config path " + path + " in " + descriptor.getResource());
                        break;
                    }
                    default:
                        unknownOrigin(origin);
                }
            }

            //  <comment>
            String comment = cookieConfig.getString("comment", false, true);
            if (comment != null)
            {
                Origin origin = context.getMetaData().getOrigin("cookie-config.comment");
                switch (origin)
                {
                    case NotSet:
                    {
                        //no <cookie-config><comment> set yet, accept it
                        context.getSessionHandler().getSessionCookieConfig().setComment(comment);
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
                            context.getSessionHandler().getSessionCookieConfig().setComment(comment);
                            context.getMetaData().setOrigin("cookie-config.comment", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (!context.getSessionHandler().getSessionCookieConfig().getComment().equals(comment))
                            throw new IllegalStateException("Conflicting cookie-config comment " + comment + " in " + descriptor.getResource());
                        break;
                    }
                    default:
                        unknownOrigin(origin);
                }
            }

            //  <http-only>true/false
            tNode = cookieConfig.get("http-only");
            if (tNode != null)
            {
                boolean httpOnly = Boolean.parseBoolean(tNode.toString(false, true));
                Origin origin = context.getMetaData().getOrigin("cookie-config.http-only");
                switch (origin)
                {
                    case NotSet:
                    {
                        //no <cookie-config><http-only> set yet, accept it
                        context.getSessionHandler().getSessionCookieConfig().setHttpOnly(httpOnly);
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
                            context.getSessionHandler().getSessionCookieConfig().setHttpOnly(httpOnly);
                            context.getMetaData().setOrigin("cookie-config.http-only", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (context.getSessionHandler().getSessionCookieConfig().isHttpOnly() != httpOnly)
                            throw new IllegalStateException("Conflicting cookie-config http-only " + httpOnly + " in " + descriptor.getResource());
                        break;
                    }
                    default:
                        unknownOrigin(origin);
                }
            }

            //  <secure>true/false
            tNode = cookieConfig.get("secure");
            if (tNode != null)
            {
                boolean secure = Boolean.parseBoolean(tNode.toString(false, true));
                Origin origin = context.getMetaData().getOrigin("cookie-config.secure");
                switch (origin)
                {
                    case NotSet:
                    {
                        //no <cookie-config><secure> set yet, accept it
                        context.getSessionHandler().getSessionCookieConfig().setSecure(secure);
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
                            context.getSessionHandler().getSessionCookieConfig().setSecure(secure);
                            context.getMetaData().setOrigin("cookie-config.secure", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (context.getSessionHandler().getSessionCookieConfig().isSecure() != secure)
                            throw new IllegalStateException("Conflicting cookie-config secure " + secure + " in " + descriptor.getResource());
                        break;
                    }
                    default:
                        unknownOrigin(origin);
                }
            }

            //  <max-age>
            tNode = cookieConfig.get("max-age");
            if (tNode != null)
            {
                int maxAge = Integer.parseInt(tNode.toString(false, true));
                Origin origin = context.getMetaData().getOrigin("cookie-config.max-age");
                switch (origin)
                {
                    case NotSet:
                    {
                        //no <cookie-config><max-age> set yet, accept it
                        context.getSessionHandler().getSessionCookieConfig().setMaxAge(maxAge);
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
                            context.getSessionHandler().getSessionCookieConfig().setMaxAge(maxAge);
                            context.getMetaData().setOrigin("cookie-config.max-age", descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a web-fragment set the value, all web-fragments must have the same value
                        if (context.getSessionHandler().getSessionCookieConfig().getMaxAge() != maxAge)
                            throw new IllegalStateException("Conflicting cookie-config max-age " + maxAge + " in " + descriptor.getResource());
                        break;
                    }
                    default:
                        unknownOrigin(origin);
                }
            }
        }
    }

    public void visitMimeMapping(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String extension = node.getString("extension", false, true);
        if (extension != null && extension.startsWith("."))
            extension = extension.substring(1);
        String mimeType = node.getString("mime-type", false, true);
        if (extension != null)
        {
            Origin origin = context.getMetaData().getOrigin("extension." + extension);
            switch (origin)
            {
                case NotSet:
                {
                    //no mime-type set for the extension yet
                    context.getMimeTypes().addMimeMapping(extension, mimeType);
                    context.getMetaData().setOrigin("extension." + extension, descriptor);
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
                        context.getMetaData().setOrigin("extension." + extension, descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //a web-fragment set the value, all web-fragments must have the same value
                    if (!context.getMimeTypes().getMimeByExtension("." + extension).equals(mimeType))
                        throw new IllegalStateException("Conflicting mime-type " + mimeType + " for extension " + extension + " in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(origin);
            }
        }
    }

    public void visitWelcomeFileList(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        Origin origin = context.getMetaData().getOrigin("welcome-file-list");
        switch (origin)
        {
            case NotSet:
            {
                context.getMetaData().setOrigin("welcome-file-list", descriptor);
                addWelcomeFiles(context, node, descriptor);
                break;
            }
            case WebXml:
            {
                //web.xml set the welcome-file-list, all other descriptors then just merge in
                addWelcomeFiles(context, node, descriptor);
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
                addWelcomeFiles(context, node, descriptor);
                break;
            }
            case WebOverride:
            {
                //web-override set the list, all other descriptors just merge in
                addWelcomeFiles(context, node, descriptor);
                break;
            }
            case WebFragment:
            {
                //A web-fragment first set the welcome-file-list. Other descriptors just add.
                addWelcomeFiles(context, node, descriptor);
                break;
            }
            default:
                unknownOrigin(origin);
        }
    }

    public void visitLocaleEncodingList(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        Iterator<XmlParser.Node> iter = node.iterator("locale-encoding-mapping");
        while (iter.hasNext())
        {
            XmlParser.Node mapping = iter.next();
            String locale = mapping.getString("locale", false, true);
            String encoding = mapping.getString("encoding", false, true);

            if (encoding != null)
            {
                Origin origin = context.getMetaData().getOrigin("locale-encoding." + locale);
                switch (origin)
                {
                    case NotSet:
                    {
                        //no mapping for the locale yet, so set it
                        context.addLocaleEncoding(locale, encoding);
                        context.getMetaData().setOrigin("locale-encoding." + locale, descriptor);
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
                            context.getMetaData().setOrigin("locale-encoding." + locale, descriptor);
                        }
                        break;
                    }
                    case WebFragment:
                    {
                        //a value was set by a web-fragment, all fragments must have the same value
                        if (!encoding.equals(context.getLocaleEncoding(locale)))
                            throw new IllegalStateException("Conflicting loacle-encoding mapping for locale " + locale + " in " + descriptor.getResource());
                        break;
                    }
                    default:
                        unknownOrigin(origin);
                }
            }
        }
    }

    public void visitErrorPage(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String error = node.getString("error-code", false, true);
        int code = 0;
        if (error == null || error.length() == 0)
        {
            error = node.getString("exception-type", false, true);
            if (error == null || error.length() == 0)
                error = ErrorPageErrorHandler.GLOBAL_ERROR_PAGE;
        }
        else
            code = Integer.parseInt(error);

        String location = node.getString("location", false, true);
        if (!location.startsWith("/"))
            throw new IllegalStateException("Missing leading '/' for location: " + location);
        //TODO is the ErrorProcessor always going to be an ErrorPageErrorHandler?
        ErrorPageErrorHandler handler = (ErrorPageErrorHandler)context.getErrorProcessor();
        String originName = "error." + error;
        Origin origin = context.getMetaData().getOrigin(originName);
        switch (origin)
        {
            case NotSet:
            {
                //no error page setup for this code or exception yet
                if (code > 0)
                    handler.addErrorPage(code, location);
                else
                    handler.addErrorPage(error, location);
                context.getMetaData().setOrigin("error." + error, descriptor);
                break;
            }
            case WebXml:
            case WebDefaults:
            case WebOverride:
            {
                //an error page setup was set in web.xml/webdefault.xml/web-override.xml, only allow other web xml descriptors to override it
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    //if set twice in the same descriptor, its an error
                    Descriptor originDescriptor = context.getMetaData().getOriginDescriptor(originName);
                    if (descriptor == originDescriptor)
                        throw new IllegalStateException("Duplicate error-page " + error + " at " + location);

                    if (code > 0)
                        handler.addErrorPage(code, location);
                    else
                        handler.addErrorPage(error, location);
                    context.getMetaData().setOrigin("error." + error, descriptor);
                }
                break;
            }
            case WebFragment:
            {
                //another web fragment set the same error code or exception, if its different its an error
                if (!handler.getErrorPages().get(error).equals(location))
                    throw new IllegalStateException("Conflicting error-code or exception-type " + error + " in " + descriptor.getResource());
                break;
            }
            default:
                unknownOrigin(origin);
        }
    }

    public void addWelcomeFiles(WebAppContext context, XmlParser.Node node, Descriptor descriptor)
    {
        Iterator<XmlParser.Node> iter = node.iterator("welcome-file");
        while (iter.hasNext())
        {
            XmlParser.Node indexNode = (XmlParser.Node)iter.next();
            String welcome = indexNode.toString(false, true);
            context.getMetaData().setOrigin("welcome-file." + welcome, descriptor);
            //Servlet Spec 3.0 p. 74 welcome files are additive
            if (welcome != null && welcome.trim().length() > 0)
                context.setWelcomeFiles((String[])ArrayUtil.addToArray(context.getWelcomeFiles(), welcome, String.class));
        }
    }

    public ServletMapping addServletMapping(String servletName, XmlParser.Node node, WebAppContext context, Descriptor descriptor)
    {
        ServletMapping mapping = new ServletMapping(new Source(Source.Origin.DESCRIPTOR, descriptor.getResource().toString()));
        mapping.setServletName(servletName);
        mapping.setFromDefaultDescriptor(descriptor instanceof DefaultsDescriptor);
        context.getMetaData().setOrigin(servletName + ".servlet.mapping." + Long.toHexString(mapping.hashCode()), descriptor);
        
        List<String> paths = new ArrayList<String>();
        Iterator<XmlParser.Node> iter = node.iterator("url-pattern");
        while (iter.hasNext())
        {
            String p = iter.next().toString(false, true);
            p = ServletPathSpec.normalize(p);

            //check if there is already a mapping for this path
            ListIterator<ServletMapping> listItor = _servletMappings.listIterator();
            boolean found = false;
            while (listItor.hasNext() && !found)
            {
                ServletMapping sm = listItor.next();
                if (sm.getPathSpecs() != null)
                {
                    for (String ps : sm.getPathSpecs())
                    {
                        //The same path has been mapped multiple times, either to a different servlet or the same servlet.
                        //If its a different servlet, this is only valid to do if the old mapping was from a default descriptor.
                        if (p.equals(ps) && (sm.isFromDefaultDescriptor() || servletName.equals(sm.getServletName())))
                        {
                            if (sm.isFromDefaultDescriptor())
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("{} in mapping {} from defaults descriptor is overridden by {}", ps, sm, servletName);
                            }
                            else
                                LOG.warn("Duplicate mapping from {} to {}", p, servletName);

                            //remove ps from the path specs on the existing mapping
                            //if the mapping now has no pathspecs, remove it
                            String[] updatedPaths = ArrayUtil.removeFromArray(sm.getPathSpecs(), ps);

                            if (updatedPaths == null || updatedPaths.length == 0)
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Removed empty mapping {}", sm);
                                listItor.remove();
                            }
                            else
                            {
                                sm.setPathSpecs(updatedPaths);
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Removed path {} from mapping {}", p, sm);
                            }
                            found = true;
                            break;
                        }
                    }
                }
            }

            paths.add(p);
            context.getMetaData().setOrigin(servletName + ".servlet.mapping.url" + p, descriptor);
        }

        mapping.setPathSpecs((String[])paths.toArray(new String[paths.size()]));
        if (LOG.isDebugEnabled())
            LOG.debug("Added mapping {} ", mapping);
        _servletMappings.add(mapping);
        return mapping;
    }

    public void addFilterMapping(String filterName, XmlParser.Node node, WebAppContext context, Descriptor descriptor)
    {
        FilterMapping mapping = new FilterMapping();
        mapping.setFilterName(filterName);
        context.getMetaData().setOrigin(filterName + ".filter.mapping." + Long.toHexString(mapping.hashCode()), descriptor);
        List<String> paths = new ArrayList<String>();
        Iterator<XmlParser.Node> iter = node.iterator("url-pattern");
        while (iter.hasNext())
        {
            String p = iter.next().toString(false, true);
            p = ServletPathSpec.normalize(p);
            paths.add(p);
            context.getMetaData().setOrigin(filterName + ".filter.mapping.url" + p, descriptor);
        }
        mapping.setPathSpecs((String[])paths.toArray(new String[paths.size()]));

        List<String> names = new ArrayList<String>();
        iter = node.iterator("servlet-name");
        while (iter.hasNext())
        {
            String n = ((XmlParser.Node)iter.next()).toString(false, true);
            context.getMetaData().setOrigin(filterName + ".filter.mapping.servlet" + n, descriptor);
            names.add(n);
        }
        mapping.setServletNames((String[])names.toArray(new String[names.size()]));

        List<DispatcherType> dispatches = new ArrayList<DispatcherType>();
        iter = node.iterator("dispatcher");
        while (iter.hasNext())
        {
            String d = ((XmlParser.Node)iter.next()).toString(false, true);
            dispatches.add(FilterMapping.dispatch(d));
        }

        if (dispatches.size() > 0)
            mapping.setDispatcherTypes(EnumSet.copyOf(dispatches));

        _filterMappings.add(mapping);
    }

    public void visitTagLib(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //Additive across web.xml and web-fragment.xml
        String uri = node.getString("taglib-uri", false, true);
        String location = node.getString("taglib-location", false, true);

        context.setResourceAlias(uri, location);

        JspConfig config = (JspConfig)context.getServletContext().getJspConfigDescriptor();
        if (config == null)
        {
            config = new JspConfig();
            if (context.getServletContext() instanceof ServletContextHandler.ServletContextApi servletContextApi)
            {
                servletContextApi.setJspConfigDescriptor(config);
            }
            else
                throw new IllegalStateException("Cannot configure taglib for non ServletContextApi"); //TODO
        }

        TagLib tl = new TagLib();
        tl.setTaglibLocation(location);
        tl.setTaglibURI(uri);
        config.addTaglibDescriptor(tl);
    }

    public void visitJspConfig(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //Additive across web.xml and web-fragment.xml
        JspConfig config = (JspConfig)context.getServletContext().getJspConfigDescriptor();
        if (config == null)
        {
            config = new JspConfig();
            if (context.getServletContext() instanceof ServletContextHandler.ServletContextApi servletContextApi)
            {
                servletContextApi.setJspConfigDescriptor(config);
            }
            else
                throw new IllegalStateException("Cannot configure jspconfig for non ServletContextApi"); //TODO
        }

        for (int i = 0; i < node.size(); i++)
        {
            Object o = node.get(i);
            if (o instanceof XmlParser.Node && "taglib".equals(((XmlParser.Node)o).getTag()))
                visitTagLib(context, descriptor, (XmlParser.Node)o);
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
                url = ServletPathSpec.normalize(url);
                paths.add(url);
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

            if (LOG.isDebugEnabled())
                LOG.debug(config.toString());
        }

        //add mappings to the jsp servlet from the property-group mappings
        if (paths.size() > 0)
        {
            ServletMapping jspMapping = null;
            for (ServletMapping m : _servletMappings)
            {
                if (m.getServletName().equals("jsp"))
                {
                    jspMapping = m;
                    break;
                }
            }
            if (jspMapping != null)
            {
                if (jspMapping.getPathSpecs() == null)
                {
                    //no paths in jsp servlet mapping, we will add all of ours
                    if (LOG.isDebugEnabled())
                        LOG.debug("Adding all paths from jsp-config to jsp servlet mapping");
                    jspMapping.setPathSpecs(paths.toArray(new String[paths.size()]));
                }
                else
                {
                    //check if each of our paths is already present in existing mapping
                    ListIterator<String> piterator = paths.listIterator();
                    while (piterator.hasNext())
                    {
                        String p = piterator.next();
                        if (jspMapping.containsPathSpec(p))
                            piterator.remove();
                    }

                    //any remaining paths, add to the jspMapping
                    if (paths.size() > 0)
                    {
                        for (String p : jspMapping.getPathSpecs())
                        {
                            paths.add(p);
                        }
                        if (LOG.isDebugEnabled())
                            LOG.debug("Adding extra paths from jsp-config to jsp servlet mapping");
                        jspMapping.setPathSpecs((String[])paths.toArray(new String[paths.size()]));
                    }
                }
            }
            else
            {
                //no mapping for jsp yet, make one
                ServletMapping mapping = new ServletMapping(new Source(Source.Origin.DESCRIPTOR, descriptor.getResource().toString()));
                mapping.setServletName("jsp");
                mapping.setPathSpecs(paths.toArray(new String[paths.size()]));
                _servletMappings.add(mapping);
            }
        }
    }

    public void visitSecurityConstraint(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        if (context.getSecurityHandler() == null)
        {
            LOG.warn("security-constraint declared but SecurityHandler==null");
            return;
        }

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
                    LOG.warn("Unknown user-data-constraint: {}", guarantee);
                    scBase.setDataConstraint(Constraint.DC_CONFIDENTIAL);
                }
            }
            Iterator<XmlParser.Node> iter = node.iterator("web-resource-collection");
            while (iter.hasNext())
            {
                XmlParser.Node collection = iter.next();
                String name = collection.getString("web-resource-name", false, true);
                Constraint sc = (Constraint)scBase.clone();
                sc.setName(name);

                Iterator<XmlParser.Node> iter2 = collection.iterator("url-pattern");
                while (iter2.hasNext())
                {
                    String url = iter2.next().toString(false, true);
                    url = ServletPathSpec.normalize(url);
                    //remember origin so we can process ServletRegistration.Dynamic.setServletSecurityElement() correctly
                    context.getMetaData().setOrigin("constraint.url." + url, descriptor);

                    Iterator<XmlParser.Node> methods = collection.iterator("http-method");
                    Iterator<XmlParser.Node> ommissions = collection.iterator("http-method-omission");

                    if (methods.hasNext())
                    {
                        if (ommissions.hasNext())
                            throw new IllegalStateException("web-resource-collection cannot contain both http-method and http-method-omission");

                        //configure all the http-method elements for each url
                        while (methods.hasNext())
                        {
                            String method = ((XmlParser.Node)methods.next()).toString(false, true);
                            ConstraintMapping mapping = new ConstraintMapping();
                            mapping.setMethod(method);
                            mapping.setPathSpec(url);
                            mapping.setConstraint(sc);
                            ((ConstraintAware)context.getSecurityHandler()).addConstraintMapping(mapping);
                        }
                    }
                    else if (ommissions.hasNext())
                    {
                        //configure all the http-method-omission elements for each url
                        // TODO use the array
                        while (ommissions.hasNext())
                        {
                            String method = ((XmlParser.Node)ommissions.next()).toString(false, true);
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
            LOG.warn("Unable to clone {}", scBase, e);
        }
    }

    public void visitLoginConfig(WebAppContext context, Descriptor descriptor, XmlParser.Node node) throws Exception
    {
        //ServletSpec 3.0 p74 says elements present 0/1 time if specified in web.xml take
        //precendece over any web-fragment. If not specified in web.xml, then if specified
        //in a web-fragment must be the same across all web-fragments.
        XmlParser.Node method = node.get("auth-method");
        if (method != null)
        {
            //handle auth-method merge
            Origin origin = context.getMetaData().getOrigin("auth-method");
            switch (origin)
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
                        throw new IllegalStateException("Conflicting auth-method value in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(origin);
            }

            //handle realm-name merge
            XmlParser.Node name = node.get("realm-name");
            String nameStr = (name == null ? "default" : name.toString(false, true));
            Origin originRealmName = context.getMetaData().getOrigin("realm-name");
            switch (originRealmName)
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
                        throw new IllegalStateException("Conflicting realm-name value in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(originRealmName);
            }

            if (Constraint.__FORM_AUTH.equalsIgnoreCase(context.getSecurityHandler().getAuthMethod()))
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
                    Origin originFormLoginPage = context.getMetaData().getOrigin("form-login-page");
                    switch (originFormLoginPage)
                    {
                        case NotSet:
                        {
                            //Never been set before, so accept it
                            context.getSecurityHandler().setInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE, loginPageName);
                            context.getMetaData().setOrigin("form-login-page", descriptor);
                            break;
                        }
                        case WebXml:
                        case WebDefaults:
                        case WebOverride:
                        {
                            //a web xml descriptor previously set it, only allow another one to change it (web.xml/web-default.xml/web-override.xml)
                            if (!(descriptor instanceof FragmentDescriptor))
                            {
                                context.getSecurityHandler().setInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE, loginPageName);
                                context.getMetaData().setOrigin("form-login-page", descriptor);
                            }
                            break;
                        }
                        case WebFragment:
                        {
                            //a web-fragment previously set it. We must be parsing yet another web-fragment, so the values must agree
                            if (!context.getSecurityHandler().getInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE).equals(loginPageName))
                                throw new IllegalStateException("Conflicting form-login-page value in " + descriptor.getResource());
                            break;
                        }
                        default:
                            unknownOrigin(originFormLoginPage);
                    }

                    //handle form-error-page
                    Origin originFormErrorPage = context.getMetaData().getOrigin("form-error-page");
                    switch (originFormErrorPage)
                    {
                        case NotSet:
                        {
                            //Never been set before, so accept it
                            context.getSecurityHandler().setInitParameter(FormAuthenticator.__FORM_ERROR_PAGE, errorPageName);
                            context.getMetaData().setOrigin("form-error-page", descriptor);
                            break;
                        }
                        case WebXml:
                        case WebDefaults:
                        case WebOverride:
                        {
                            //a web xml descriptor previously set it, only allow another one to change it (web.xml/web-default.xml/web-override.xml)
                            if (!(descriptor instanceof FragmentDescriptor))
                            {
                                context.getSecurityHandler().setInitParameter(FormAuthenticator.__FORM_ERROR_PAGE, errorPageName);
                                context.getMetaData().setOrigin("form-error-page", descriptor);
                            }
                            break;
                        }
                        case WebFragment:
                        {
                            //a web-fragment previously set it. We must be parsing yet another web-fragment, so the values must agree
                            if (!context.getSecurityHandler().getInitParameter(FormAuthenticator.__FORM_ERROR_PAGE).equals(errorPageName))
                                throw new IllegalStateException("Conflicting form-error-page value in " + descriptor.getResource());
                            break;
                        }
                        default:
                            unknownOrigin(originFormErrorPage);
                    }
                }
                else
                {
                    throw new IllegalStateException("!form-login-config");
                }
            }
        }
    }

    public void visitSecurityRole(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        if (context.getSecurityHandler() == null)
        {
            LOG.warn("security-role declared but SecurityHandler==null");
            return;
        }
        //ServletSpec 3.0, p74 elements with multiplicity >1 are additive when merged
        XmlParser.Node roleNode = node.get("role-name");
        String role = roleNode.toString(false, true);
        ((ConstraintAware)context.getSecurityHandler()).addRole(role);
        context.getMetaData().setOrigin("security-role." + role, descriptor);
    }

    public void visitFilter(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String name = node.getString("filter-name", false, true);
        FilterHolder holder = _filterHolderMap.get(name);
        if (holder == null)
        {
            holder = context.getServletHandler().newFilterHolder(new Source(Source.Origin.DESCRIPTOR, descriptor.getResource().toString()));
            holder.setName(name);
            _filterHolderMap.put(name, holder);
            _filterHolders.add(holder);
        }

        String filterClass = node.getString("filter-class", false, true);
        if (filterClass != null)
        {
            ((WebDescriptor)descriptor).addClassName(filterClass);

            Origin origin = context.getMetaData().getOrigin(name + ".filter.filter-class");
            switch (origin)
            {
                case NotSet:
                {
                    //no class set yet
                    holder.setClassName(filterClass);
                    context.getMetaData().setOrigin(name + ".filter.filter-class", descriptor);
                    break;
                }
                case WebXml:
                case WebDefaults:
                case WebOverride:
                {
                    //filter class was set in web.xml, only allow other web xml descriptors (override/default) to change it
                    if (!(descriptor instanceof FragmentDescriptor))
                    {
                        holder.setClassName(filterClass);
                        context.getMetaData().setOrigin(name + ".filter.filter-class", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //the filter class was set up by a web fragment, all fragments must be the same
                    if (!holder.getClassName().equals(filterClass))
                        throw new IllegalStateException("Conflicting filter-class for filter " + name + " in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(origin);
            }
        }

        Iterator<XmlParser.Node> iter = node.iterator("init-param");
        while (iter.hasNext())
        {
            XmlParser.Node paramNode = iter.next();
            String pname = paramNode.getString("param-name", false, true);
            String pvalue = paramNode.getString("param-value", false, true);

            Origin origin = context.getMetaData().getOrigin(name + ".filter.init-param." + pname);
            switch (origin)
            {
                case NotSet:
                {
                    //init-param not already set, so set it
                    holder.setInitParameter(pname, pvalue);
                    context.getMetaData().setOrigin(name + ".filter.init-param." + pname, descriptor);
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
                        context.getMetaData().setOrigin(name + ".filter.init-param." + pname, descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //previously set by a web-fragment, make sure that the value matches, otherwise its an error
                    if (!holder.getInitParameter(pname).equals(pvalue))
                        throw new IllegalStateException("Mismatching init-param " + pname + "=" + pvalue + " in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(origin);
            }
        }

        String async = node.getString("async-supported", false, true);
        if (async != null)
            holder.setAsyncSupported(async.length() == 0 || Boolean.parseBoolean(async));
        if (async != null)
        {
            boolean val = async.length() == 0 || Boolean.parseBoolean(async);
            Origin origin = context.getMetaData().getOrigin(name + ".filter.async-supported");
            switch (origin)
            {
                case NotSet:
                {
                    //set it
                    holder.setAsyncSupported(val);
                    context.getMetaData().setOrigin(name + ".filter.async-supported", descriptor);
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
                        context.getMetaData().setOrigin(name + ".filter.async-supported", descriptor);
                    }
                    break;
                }
                case WebFragment:
                {
                    //async-supported set by another fragment, this fragment's value must match
                    if (holder.isAsyncSupported() != val)
                        throw new IllegalStateException("Conflicting async-supported=" + async + " for filter " + name + " in " + descriptor.getResource());
                    break;
                }
                default:
                    unknownOrigin(origin);
            }
        }
    }

    public void visitFilterMapping(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //Servlet Spec 3.0, p74
        //filter-mappings are always additive, whether from web xml descriptors (web.xml/web-default.xml/web-override.xml) or web-fragments.
        //Maintenance update 3.0a to spec:
        //  Updated 8.2.3.g.v to say <servlet-mapping> elements are additive across web-fragments.
        String filterName = node.getString("filter-name", false, true);
        Origin origin = context.getMetaData().getOrigin(filterName + ".filter.mappings");
        switch (origin)
        {
            case NotSet:
            {
                //no filtermappings for this filter yet defined
                context.getMetaData().setOrigin(filterName + ".filter.mappings", descriptor);
                addFilterMapping(filterName, node, context, descriptor);
                break;
            }
            case WebDefaults:
            case WebOverride:
            case WebXml:
            {
                //filter mappings defined in a web xml file. If we're processing a fragment, we ignore filter mappings.
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    addFilterMapping(filterName, node, context, descriptor);
                }
                break;
            }
            case WebFragment:
            {
                //filter mappings first defined in a web-fragment, allow other fragments to add
                addFilterMapping(filterName, node, context, descriptor);
                break;
            }
            default:
                unknownOrigin(origin);
        }
    }

    public void visitListener(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String className = node.getString("listener-class", false, true);
        try
        {
            if (className != null && className.length() > 0)
            {
                //Servlet Spec 3.0 p 74
                //Duplicate listener declarations don't result in duplicate listener instances
                for (ListenerHolder holder : context.getServletHandler().getListeners())
                {
                    if (holder.getClassName().equals(className))
                        return;
                }

                ((WebDescriptor)descriptor).addClassName(className);

                ListenerHolder h = context.getServletHandler().newListenerHolder(new Source(Source.Origin.DESCRIPTOR, descriptor.getResource().toString()));
                h.setClassName(className);
                context.getServletHandler().addListener(h);
                context.getMetaData().setOrigin(className + ".listener", descriptor);
            }
        }
        catch (Exception e)
        {
            LOG.warn("Could not instantiate listener {}", className, e);
            return;
        }
    }

    /**
     * Servlet spec 3.1. When present in web.xml, this means that http methods that are
     * not covered by security constraints should have access denied.
     * <p>
     * See section 13.8.4, pg 145
     *
     * @param context the of the processing
     * @param descriptor the descriptor
     * @param node the xml node
     */
    public void visitDenyUncoveredHttpMethods(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        if (context.getSecurityHandler() == null)
        {
            LOG.warn("deny-uncovered-http-methods declared but SecurityHandler==null");
            return;
        }

        ((ConstraintAware)context.getSecurityHandler()).setDenyUncoveredHttpMethods(true);
    }

    /**
     * When specified, this element provides a default context path
     * of the web application. The default context path starts
     * with a / character. If it is not rooted at the root of the
     * server's name space, the path does not end with a / character.
     *
     * @param context the of the processing
     * @param descriptor the descriptor
     * @param node the xml node
     * @since Servlet 4.0
     */
    public void visitDefaultContextPath(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        if (!(descriptor instanceof FragmentDescriptor))
        {
            String path = node.toString(false, true);
            context.setAttribute("default-context-path", path);
            if (context.isContextPathDefault())
            {
                context.setDefaultContextPath(path);
                context.getMetaData().setOrigin("default-context-path", descriptor);
            }
        }
    }

    /**
     * When specified, this element provides a default request
     * encoding of the web application.
     *
     * @param context the of the processing
     * @param descriptor the descriptor
     * @param node the xml node
     * @since Servlet 4.0
     */
    public void visitRequestCharacterEncoding(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //As per spec, this element can only appear in web.xml, never in a fragment. Jetty will
        //allow it to be specified in webdefault.xml, web.xml, and web-override.xml.
        if (!(descriptor instanceof FragmentDescriptor))
        {
            String encoding = node.toString(false, true);
            context.setAttribute("request-character-encoding", encoding);
            context.setDefaultRequestCharacterEncoding(encoding);
            context.getMetaData().setOrigin("request-character-encoding", descriptor);
        }
    }

    /**
     * When specified, this element provides a default response
     * encoding of the web application.
     *
     * @param context the of the processing
     * @param descriptor the descriptor
     * @param node the xml node
     * @since Servlet 4.0
     */
    public void visitResponseCharacterEncoding(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        //As per spec, this element can only appear in web.xml, never in a fragment. Jetty will
        //allow it to be specified in webdefault.xml, web.xml, and web-override.xml.
        if (!(descriptor instanceof FragmentDescriptor))
        {
            String encoding = node.toString(false, true);
            context.setAttribute("response-character-encoding", encoding);
            context.setDefaultResponseCharacterEncoding(encoding);
            context.getMetaData().setOrigin("response-character-encoding", descriptor);
        }
    }

    private void unknownOrigin(Origin origin)
    {
        LOG.warn("Unknown descriptor origin {}", origin, new Throwable()); // TODO throw ISE?
    }
}
