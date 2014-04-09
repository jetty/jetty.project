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

package org.eclipse.jetty.quickstart;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletContext;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspPropertyGroupDescriptor;
import javax.servlet.descriptor.TaglibDescriptor;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.plus.annotation.LifeCycleCallback;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.Holder;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.MetaData.OriginInfo;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlAppendable;

/**
 * QuickStartWar
 *
 */
public class QuickStartWebApp extends WebAppContext
{
    private static final Logger LOG = Log.getLogger(QuickStartWebApp.class);
    
    public static final String[] __configurationClasses = new String[] 
            {
                org.eclipse.jetty.quickstart.QuickStartConfiguration.class.getCanonicalName(),
                org.eclipse.jetty.plus.webapp.EnvConfiguration.class.getCanonicalName(),
                org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getCanonicalName(),
                org.eclipse.jetty.webapp.JettyWebXmlConfiguration.class.getCanonicalName()
            };
    
    
    private boolean _preconfigure=false;
    private boolean _autoPreconfigure=false;
    private boolean _startWebapp=false;
    private PreconfigureDescriptorProcessor _preconfigProcessor;
    

    public static final String[] __preconfigurationClasses = new String[]
    { 
        org.eclipse.jetty.webapp.WebInfConfiguration.class.getCanonicalName(), 
        org.eclipse.jetty.webapp.WebXmlConfiguration.class.getCanonicalName(),
        org.eclipse.jetty.webapp.MetaInfConfiguration.class.getCanonicalName(), 
        org.eclipse.jetty.webapp.FragmentConfiguration.class.getCanonicalName(),
        org.eclipse.jetty.plus.webapp.EnvConfiguration.class.getCanonicalName(), 
        org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getCanonicalName(),
        org.eclipse.jetty.annotations.AnnotationConfiguration.class.getCanonicalName(),
    };
    
    public QuickStartWebApp()
    {
        super();
        setConfigurationClasses(__preconfigurationClasses);
        setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",".*\\.jar");
    }

    public boolean isPreconfigure()
    {
        return _preconfigure;
    }

    /* ------------------------------------------------------------ */
    /** Preconfigure webapp
     * @param preconfigure  If true, then starting the webapp will generate 
     * the WEB-INF/quickstart-web.xml rather than start the webapp.
     */
    public void setPreconfigure(boolean preconfigure)
    {
        _preconfigure = preconfigure;
    }

    public boolean isAutoPreconfigure()
    {
        return _autoPreconfigure;
    }
    
    public void setAutoPreconfigure(boolean autoPrecompile)
    {
        _autoPreconfigure = autoPrecompile;
    }
    
    @Override
    protected void startWebapp() throws Exception
    {
        if (isPreconfigure())
            generateQuickstartWebXml(_preconfigProcessor.getXML());
        
        if (_startWebapp)
            super.startWebapp();
    }
    
    @Override
    protected void doStart() throws Exception
    {
        // unpack and Adjust paths.
        Resource war = null;
        Resource dir = null;

        Resource base = getBaseResource();
        if (base==null)
            base=Resource.newResource(getWar());

        if (base.isDirectory())
            dir=base;
        else if (base.toString().toLowerCase().endsWith(".war"))
        {
            war=base;
            String w=war.toString();
            dir=Resource.newResource(w.substring(0,w.length()-4));

            if (!dir.exists())
            {                       
                LOG.info("Quickstart Extract " + war + " to " + dir);
                dir.getFile().mkdirs();
                JarResource.newJarResource(war).copyTo(dir.getFile());
            }

            setWar(null);
            setBaseResource(dir);
        }
        else 
            throw new IllegalArgumentException();


        Resource qswebxml=dir.addPath("/WEB-INF/quickstart-web.xml");
        
        if (isPreconfigure())
        {
            _preconfigProcessor = new PreconfigureDescriptorProcessor();
            getMetaData().addDescriptorProcessor(_preconfigProcessor);
            _startWebapp=false;
        }
        else if (qswebxml.exists())
        {
            setConfigurationClasses(__configurationClasses);
            _startWebapp=true;
        }
        else if (_autoPreconfigure)
        {   
            LOG.info("Quickstart preconfigure: {}(war={},dir={})",this,war,dir);

            _preconfigProcessor = new PreconfigureDescriptorProcessor();
            
            getMetaData().addDescriptorProcessor(_preconfigProcessor);
            setPreconfigure(true);
            _startWebapp=true;
        }
        else
            _startWebapp=true;
            
        super.doStart();
    }


    public void generateQuickstartWebXml(String extraXML) throws IOException
    {
        getMetaData().getOrigins();
        // dumpStdErr();

        if (getBaseResource()==null)
            throw new IllegalArgumentException("No base resource for "+this);
        
        File webxml = new File(getWebInf().getFile(),"quickstart-web.xml");

        LOG.info("Quickstart generate {}",webxml);

        XmlAppendable out = new XmlAppendable(new FileOutputStream(webxml),"UTF-8");
        MetaData md = getMetaData();

        Map<String, String> webappAttr = new HashMap<>();
        webappAttr.put("xmlns","http://xmlns.jcp.org/xml/ns/javaee");
        webappAttr.put("xmlns:xsi","http://www.w3.org/2001/XMLSchema-instance");
        webappAttr.put("xsi:schemaLocation","http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd");
        webappAttr.put("metadata-complete","true");
        webappAttr.put("version","3.1");

        out.open("web-app",webappAttr);

        if (getDisplayName() != null)
            out.tag("display-name",getDisplayName());

        // Set some special context parameters

        // The location of the war file on disk
        String resourceBase=getBaseResource().getFile().getCanonicalFile().getAbsoluteFile().toURI().toString();
        
        // The library order
        addContextParamFromAttribute(out,ServletContext.ORDERED_LIBS);
        //the servlet container initializers
        addContextParamFromAttribute(out,AnnotationConfiguration.CONTAINER_INITIALIZERS);
        //the tlds discovered
        addContextParamFromAttribute(out,MetaInfConfiguration.METAINF_TLDS,resourceBase);
        //the META-INF/resources discovered
        addContextParamFromAttribute(out,MetaInfConfiguration.METAINF_RESOURCES,resourceBase);

        
        // init params
        for (String p : getInitParams().keySet())
            out.open("context-param",origin(md,"context-param." + p))
            .tag("param-name",p)
            .tag("param-value",getInitParameter(p))
            .close();

        if (getEventListeners() != null)
            for (EventListener e : getEventListeners())
                out.open("listener",origin(md,e.getClass().getCanonicalName() + ".listener"))
                .tag("listener-class",e.getClass().getCanonicalName())
                .close();

        ServletHandler servlets = getServletHandler();

        if (servlets.getFilters() != null)
        {
            for (FilterHolder holder : servlets.getFilters())
                outholder(out,md,"filter",holder);
        }

        if (servlets.getFilterMappings() != null)
        {
            for (FilterMapping mapping : servlets.getFilterMappings())
            {
                out.open("filter-mapping");
                out.tag("filter-name",mapping.getFilterName());
                if (mapping.getPathSpecs() != null)
                    for (String s : mapping.getPathSpecs())
                        out.tag("url-pattern",s);
                if (mapping.getServletNames() != null)
                    for (String n : mapping.getServletNames())
                        out.tag("servlet-name",n);

                if (!mapping.isDefaultDispatches())
                {
                    if (mapping.appliesTo(DispatcherType.REQUEST))
                        out.tag("dispatcher","REQUEST");
                    if (mapping.appliesTo(DispatcherType.ASYNC))
                        out.tag("dispatcher","ASYNC");
                    if (mapping.appliesTo(DispatcherType.ERROR))
                        out.tag("dispatcher","ERROR");
                    if (mapping.appliesTo(DispatcherType.FORWARD))
                        out.tag("dispatcher","FORWARD");
                    if (mapping.appliesTo(DispatcherType.INCLUDE))
                        out.tag("dispatcher","INCLUDE");
                }
                out.close();
            }
        }

        if (servlets.getServlets() != null)
        {
            for (ServletHolder holder : servlets.getServlets())
                outholder(out,md,"servlet",holder);
        }

        if (servlets.getServletMappings() != null)
        {
            for (ServletMapping mapping : servlets.getServletMappings())
            {
                out.open("servlet-mapping",origin(md,mapping.getServletName() + ".servlet.mappings"));
                out.tag("servlet-name",mapping.getServletName());
                if (mapping.getPathSpecs() != null)
                    for (String s : mapping.getPathSpecs())
                        out.tag("url-pattern",s);
                out.close();
            }
        }

        // Security elements
        SecurityHandler security = getSecurityHandler();
        
        if (security!=null && (security.getRealmName()!=null || security.getAuthMethod()!=null))
        {
            out.open("login-config");
            if (security.getAuthMethod()!=null)
                out.tag("auth-method",origin(md,"auth-method"),security.getAuthMethod());
            if (security.getRealmName()!=null)
                out.tag("realm-name",origin(md,"realm-name"),security.getRealmName());
            
            
            if (Constraint.__FORM_AUTH.equalsIgnoreCase(security.getAuthMethod()))
            {
                out.open("form-login-config");
                out.tag("form-login-page",origin(md,"form-login-page"),security.getInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE));
                out.tag("form-error-page",origin(md,"form-error-page"),security.getInitParameter(FormAuthenticator.__FORM_ERROR_PAGE));
                out.close();
            }
            
            out.close();
        }
        
        if (security instanceof ConstraintAware)
        {
            ConstraintAware ca = (ConstraintAware)security;
            for (String r:ca.getRoles())
                out.open("security-role")
                .tag("role-name",r)
                .close();
            
            for (ConstraintMapping m : ca.getConstraintMappings())
            {
                out.open("security-constraint");
                
                if (m.getConstraint().getAuthenticate())
                {
                    out.open("auth-constraint");
                    if (m.getConstraint().getRoles()!=null)
                        for (String r : m.getConstraint().getRoles())
                            out.tag("role-name",r);

                    out.close();
                }
                
                switch (m.getConstraint().getDataConstraint())
                {
                    case Constraint.DC_NONE:
                        out.open("user-data-constraint").tag("transport-guarantee","NONE").close();
                        break;
                        
                    case Constraint.DC_INTEGRAL:
                        out.open("user-data-constraint").tag("transport-guarantee","INTEGRAL").close();
                        break;
                        
                    case Constraint.DC_CONFIDENTIAL:
                        out.open("user-data-constraint").tag("transport-guarantee","CONFIDENTIAL").close();
                        break;
                        
                    default:
                            break;
                        
                }

                out.open("web-resource-collection");
                {
                    if (m.getConstraint().getName()!=null)
                        out.tag("web-resource-name",m.getConstraint().getName());
                    if (m.getPathSpec()!=null)
                        out.tag("url-pattern",origin(md,"constraint.url."+m.getPathSpec()),m.getPathSpec());
                    if (m.getMethod()!=null)
                        out.tag("http-method",m.getMethod());

                    if (m.getMethodOmissions()!=null)
                        for (String o:m.getMethodOmissions())
                            out.tag("http-method-omission",o);

                    out.close();
                }
                
                out.close();
                
            }
        }
        
        if (getWelcomeFiles() != null)
        {
            out.open("welcome-file-list");
            for (String welcomeFile:getWelcomeFiles())
            {
                out.tag("welcome-file", welcomeFile);
            }
            out.close();
        }
    
        Map<String,String> localeEncodings = getLocaleEncodings();
        if (localeEncodings != null && !localeEncodings.isEmpty())
        {
            out.open("locale-encoding-mapping-list");
            for (Map.Entry<String, String> entry:localeEncodings.entrySet())
            {
                out.open("locale-encoding-mapping", origin(md,"locale-encoding."+entry.getKey()));
                out.tag("locale", entry.getKey());
                out.tag("encoding", entry.getValue());
                out.close();
            }
            out.close();
        }

        //session-config
        if (getSessionHandler().getSessionManager() != null)
        {
            out.open("session-config");
            int maxInactiveSec = getSessionHandler().getSessionManager().getMaxInactiveInterval();
            out.tag("session-timeout", (maxInactiveSec==0?"0":Integer.toString(maxInactiveSec/60)));
 
            Set<SessionTrackingMode> modes = getSessionHandler().getSessionManager().getEffectiveSessionTrackingModes();
            if (modes != null)
            {
                for (SessionTrackingMode mode:modes)
                    out.tag("tracking-mode", mode.toString());
            }
            
            //cookie-config
            SessionCookieConfig cookieConfig = getSessionHandler().getSessionManager().getSessionCookieConfig();
            if (cookieConfig != null)
            {
                out.open("cookie-config");
                if (cookieConfig.getName() != null)
                    out.tag("name", origin(md,"cookie-config.name"), cookieConfig.getName());
                
                if (cookieConfig.getDomain() != null)
                    out.tag("domain", origin(md, "cookie-config.domain"), cookieConfig.getDomain());
                
                if (cookieConfig.getPath() != null)
                    out.tag("path", origin(md, "cookie-config.path"), cookieConfig.getPath());
                
                if (cookieConfig.getComment() != null)
                    out.tag("comment", origin(md, "cookie-config.comment"), cookieConfig.getComment());
                
                out.tag("http-only", origin(md, "cookie-config.http-only"), Boolean.toString(cookieConfig.isHttpOnly()));
                out.tag("secure", origin(md, "cookie-config.secure"), Boolean.toString(cookieConfig.isSecure()));
                out.tag("max-age", origin(md, "cookie-config.max-age"), Integer.toString(cookieConfig.getMaxAge()));
                out.close();
            }
            out.close();     
        }
        
        //error-pages
        Map<String,String> errorPages = ((ErrorPageErrorHandler)getErrorHandler()).getErrorPages();
        if (errorPages != null)
        {
            for (Map.Entry<String, String> entry:errorPages.entrySet())
            {
                out.open("error-page", origin(md, "error."+entry.getKey()));
                //a global or default error page has no code or exception               
                if (!ErrorPageErrorHandler.GLOBAL_ERROR_PAGE.equals(entry.getKey()))
                {
                    if (entry.getKey().matches("\\d{3}"))
                        out.tag("error-code", entry.getKey());
                    else
                        out.tag("exception-type", entry.getKey());
                }
                out.tag("location", entry.getValue());
                out.close();
            }
        }
        
        //mime-types
        MimeTypes mimeTypes = getMimeTypes();
        if (mimeTypes != null)
        {
            for (Map.Entry<String, String> entry:mimeTypes.getMimeMap().entrySet())
            {
                out.open("mime-mapping");
                out.tag("extension", origin(md, "extension."+entry.getKey()), entry.getKey());
                out.tag("mime-type", entry.getValue());
                out.close();
            }
        }
        
        //jsp-config
        JspConfig jspConfig = (JspConfig)getServletContext().getJspConfigDescriptor();
        if (jspConfig != null)
        {
            out.open("jsp-config");
            Collection<TaglibDescriptor> tlds = jspConfig.getTaglibs();
            if (tlds != null && !tlds.isEmpty())
            {
                for (TaglibDescriptor tld:tlds)
                {
                    out.open("taglib");
                    out.tag("taglib-uri", tld.getTaglibURI());
                    out.tag("taglib-location", tld.getTaglibLocation());
                    out.close();
                }
            }
            
            Collection<JspPropertyGroupDescriptor> jspPropertyGroups = jspConfig.getJspPropertyGroups();
            if (jspPropertyGroups != null && !jspPropertyGroups.isEmpty())
            {
                for (JspPropertyGroupDescriptor jspPropertyGroup:jspPropertyGroups)
                {
                    out.open("jsp-property-group");
                    Collection<String> strings = jspPropertyGroup.getUrlPatterns();
                    if (strings != null && !strings.isEmpty())
                    {
                        for (String urlPattern:strings)
                            out.tag("url-pattern", urlPattern);
                    }

                    if (jspPropertyGroup.getElIgnored() != null)
                        out.tag("el-ignored", jspPropertyGroup.getElIgnored());

                    if (jspPropertyGroup.getPageEncoding() != null)
                        out.tag("page-encoding", jspPropertyGroup.getPageEncoding());

                    if (jspPropertyGroup.getScriptingInvalid() != null)
                        out.tag("scripting-invalid", jspPropertyGroup.getScriptingInvalid());

                    if (jspPropertyGroup.getIsXml() != null)
                        out.tag("is-xml", jspPropertyGroup.getIsXml());

                    if (jspPropertyGroup.getDeferredSyntaxAllowedAsLiteral() != null)
                        out.tag("deferred-syntax-allowed-as-literal", jspPropertyGroup.getDeferredSyntaxAllowedAsLiteral());

                    if (jspPropertyGroup.getTrimDirectiveWhitespaces() != null)
                        out.tag("trim-directive-whitespaces", jspPropertyGroup.getTrimDirectiveWhitespaces());

                    if (jspPropertyGroup.getDefaultContentType() != null)
                        out.tag("default-content-type", jspPropertyGroup.getDefaultContentType());

                    if (jspPropertyGroup.getBuffer() != null)
                        out.tag("buffer", jspPropertyGroup.getBuffer());

                    if (jspPropertyGroup.getErrorOnUndeclaredNamespace() != null)
                        out.tag("error-on-undeclared-namespace", jspPropertyGroup.getErrorOnUndeclaredNamespace());

                    strings = jspPropertyGroup.getIncludePreludes();
                    if (strings != null && !strings.isEmpty())
                    {
                        for (String prelude:strings)
                            out.tag("include-prelude", prelude);
                    }
                   
                    strings = jspPropertyGroup.getIncludeCodas();
                    if (strings != null && !strings.isEmpty())
                    {
                        for (String coda:strings)
                            out.tag("include-coda", coda);
                    }
                   
                    out.close();
                }
            }
            
            out.close();
        }

        //lifecycle: post-construct, pre-destroy
        LifeCycleCallbackCollection lifecycles = ((LifeCycleCallbackCollection)getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION));
        if (lifecycles != null)
        {
            Collection<LifeCycleCallback> tmp = lifecycles.getPostConstructCallbacks();

            for (LifeCycleCallback c:tmp)
            {
                out.open("post-construct");
                out.tag("lifecycle-callback-class", c.getTargetClassName());
                out.tag("lifecycle-callback-method", c.getMethodName());
                out.close();
            }
            
            tmp = lifecycles.getPreDestroyCallbacks();
            for (LifeCycleCallback c:tmp)
            {
                out.open("pre-destroy");
                out.tag("lifecycle-callback-class", c.getTargetClassName());
                out.tag("lifecycle-callback-method", c.getMethodName());
                out.close();
            }
        }

        out.literal(extraXML);
        
        out.close();
    }

    private void addContextParamFromAttribute(XmlAppendable out, String attribute) throws IOException
    {
        addContextParamFromAttribute(out,attribute,null);
    }
    
    private void addContextParamFromAttribute(XmlAppendable out, String attribute, String resourceBase) throws IOException
    {
        Object o=getAttribute(attribute);
        if (o==null)
            return;
                
        Collection<?> c =  (o instanceof Collection)? (Collection<?>)o:Collections.singletonList(o);
        StringBuilder v=new StringBuilder();
        for (Object i:c)
        {
            if (i!=null)
            {
                if (v.length()>0)
                    v.append(",\n    ");
                else
                    v.append("\n    ");
                if (resourceBase==null)
                    QuotedStringTokenizer.quote(v,i.toString());
                else
                    QuotedStringTokenizer.quote(v,i.toString().replace(resourceBase,"${WAR}/"));
            }
        }
        out.open("context-param")
        .tag("param-name",attribute)
        .tagCDATA("param-value",v.toString())
        .close();        
    }

    private static void outholder(XmlAppendable out, MetaData md, String tag, Holder<?> holder) throws IOException
    {
        out.open(tag,Collections.singletonMap("source",holder.getSource().toString()));
        String n = holder.getName();
        out.tag(tag + "-name",n);

        String ot = n + "." + tag + ".";

        out.tag(tag + "-class",origin(md,ot + tag + "-class"),holder.getClassName());

        for (String p : holder.getInitParameters().keySet())
        {
            if ("scratchdir".equalsIgnoreCase(p)) //don't preconfigure the temp dir for jsp output
                continue;
            out.open("init-param",origin(md,ot + "init-param." + p))
            .tag("param-name",p)
            .tag("param-value",holder.getInitParameter(p))
            .close();
        }

        if (holder instanceof ServletHolder)
        {
            ServletHolder s = (ServletHolder)holder;
            if (s.getForcedPath() != null)
                out.tag("jsp-file",s.getForcedPath());

            if (s.getInitOrder() != 0)
                out.tag("load-on-startup",Integer.toString(s.getInitOrder()));

            if (s.getRunAsRole() != null)
                out.open("run-as",origin(md,ot + "run-as"))
                .tag("role-name",s.getRunAsRole())
                .close();

            Map<String,String> roles = s.getRoleRefMap();
            if (roles!=null)
            {
                for (Map.Entry<String, String> e : roles.entrySet())
                {
                    out.open("security-role-ref",origin(md,ot+"role-name."+e.getKey()))
                    .tag("role-name",e.getKey())
                    .tag("role-link",e.getValue())
                    .close();
                }
            }
            
            if (!s.isEnabled())
                out.tag("enabled",origin(md,ot + "enabled"),"false");

            //multipart-config
            MultipartConfigElement multipartConfig = ((ServletHolder.Registration)s.getRegistration()).getMultipartConfig();
            if (multipartConfig != null)
            {
                out.open("multipart-config", origin(md, s.getName()+".servlet.multipart-config"));
                if (multipartConfig.getLocation() != null)
                    out.tag("location", multipartConfig.getLocation());
                out.tag("max-file-size", Long.toString(multipartConfig.getMaxFileSize()));
                out.tag("max-request-size", Long.toString(multipartConfig.getMaxRequestSize()));
                out.tag("file-size-threshold", Long.toString(multipartConfig.getFileSizeThreshold()));
                out.close();
            }
        }

        out.tag("async-supported",origin(md,ot + "async-supported"),holder.isAsyncSupported()?"true":"false");
        out.close();
    }

    public static Map<String, String> origin(MetaData md, String name)
    {
        if (!LOG.isDebugEnabled())
            return Collections.emptyMap();
        if (name == null)
            return Collections.emptyMap();
        OriginInfo origin = md.getOriginInfo(name);
        // System.err.println("origin of "+name+" is "+origin);
        if (origin == null)
            return Collections.emptyMap();
        return Collections.singletonMap("origin",origin.toString());

    }
    
}
