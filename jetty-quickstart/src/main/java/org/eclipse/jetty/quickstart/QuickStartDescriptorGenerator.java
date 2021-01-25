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

package org.eclipse.jetty.quickstart;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Collections;
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
import org.eclipse.jetty.servlet.ListenerHolder;
import org.eclipse.jetty.servlet.ServletContextHandler.JspConfig;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.MetaData.OriginInfo;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlAppendable;

/**
 * QuickStartDescriptorGenerator
 * <p>
 * Generate an effective web.xml from a WebAppContext, including all components
 * from web.xml, web-fragment.xmls annotations etc.
 * <p>
 * If generating quickstart for a different java platform than the current running
 * platform, then the org.eclipse.jetty.annotations.javaTargetPlatform attribute
 * should be set on the Context with the platform number of the target JVM (eg 8).
 */
public class QuickStartDescriptorGenerator
{
    private static final Logger LOG = Log.getLogger(QuickStartDescriptorGenerator.class);

    public static final String ORIGIN = "org.eclipse.jetty.originAttribute";
    public static final String DEFAULT_QUICKSTART_DESCRIPTOR_NAME = "quickstart-web.xml";
    public static final String DEFAULT_ORIGIN_ATTRIBUTE_NAME = "origin";

    protected WebAppContext _webApp;
    protected String _extraXML;
    protected String _originAttribute;
    protected boolean _generateOrigin;
    protected int _count;

    /**
     * @param w the source WebAppContext
     * @param extraXML any extra xml snippet to append
     * @param originAttribute param value to use for the context param origin attribute
     * @param generateOrigin <code>true</code> to generate the origin attribute
     */
    public QuickStartDescriptorGenerator(WebAppContext w, String extraXML, String originAttribute, boolean generateOrigin)
    {
        _webApp = w;
        _extraXML = extraXML;
        _originAttribute = (StringUtil.isBlank(originAttribute) ? DEFAULT_ORIGIN_ATTRIBUTE_NAME : originAttribute);
        _generateOrigin = generateOrigin || LOG.isDebugEnabled();
        _count = 0;
    }

    /**
     * Perform the generation of the xml file
     *
     * @param stream the stream to generate the quickstart-web.xml to
     * @throws IOException if unable to generate the quickstart-web.xml
     * @throws FileNotFoundException if unable to find the file
     */
    public void generateQuickStartWebXml(OutputStream stream) throws FileNotFoundException, IOException
    {
        if (_webApp == null)
            throw new IllegalStateException("No webapp for quickstart generation");
        if (stream == null)
            throw new IllegalStateException("No output for quickstart generation");

        _webApp.getMetaData().getOrigins();

        if (_webApp.getBaseResource() == null)
            throw new IllegalArgumentException("No base resource for " + this);

        LOG.info("Quickstart generating");

        XmlAppendable out = new XmlAppendable(stream, "UTF-8");

        MetaData md = _webApp.getMetaData();

        Map<String, String> webappAttr = new HashMap<>();
        webappAttr.put("xmlns", "http://xmlns.jcp.org/xml/ns/javaee");
        webappAttr.put("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
        webappAttr.put("xsi:schemaLocation", "http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd");
        webappAttr.put("metadata-complete", "true");
        webappAttr.put("version", "3.1");

        out.openTag("web-app", webappAttr);
        if (_webApp.getDisplayName() != null)
            out.tag("display-name", _webApp.getDisplayName());

        // Set some special context parameters

        // The location of the war file on disk
        AttributeNormalizer normalizer = new AttributeNormalizer(_webApp.getBaseResource());

        // The library order
        addContextParamFromAttribute(out, ServletContext.ORDERED_LIBS);
        //the servlet container initializers
        addContextParamFromAttribute(out, AnnotationConfiguration.CONTAINER_INITIALIZERS);
        //the tlds discovered
        addContextParamFromAttribute(out, MetaInfConfiguration.METAINF_TLDS, normalizer);
        //the META-INF/resources discovered
        addContextParamFromAttribute(out, MetaInfConfiguration.METAINF_RESOURCES, normalizer);

        //add the name of the origin attribute, if it is being used
        if (_generateOrigin)
        {
            out.openTag("context-param")
                .tag("param-name", ORIGIN)
                .tag("param-value", _originAttribute)
                .closeTag();
        }

        // init params
        for (String p : _webApp.getInitParams().keySet())
        {
            out.openTag("context-param", origin(md, "context-param." + p))
                .tag("param-name", p)
                .tag("param-value", _webApp.getInitParameter(p))
                .closeTag();
        }

        if (_webApp.getServletHandler().getListeners() != null)
            for (ListenerHolder e : _webApp.getServletHandler().getListeners())
            {
                out.openTag("listener", origin(md, e.getClassName() + ".listener"))
                    .tag("listener-class", e.getClassName())
                    .closeTag();
            }

        ServletHandler servlets = _webApp.getServletHandler();

        if (servlets.getFilters() != null)
        {
            for (FilterHolder holder : servlets.getFilters())
            {
                outholder(out, md, holder);
            }
        }

        if (servlets.getFilterMappings() != null)
        {
            for (FilterMapping mapping : servlets.getFilterMappings())
            {
                out.openTag("filter-mapping");
                out.tag("filter-name", mapping.getFilterName());
                if (mapping.getPathSpecs() != null)
                    for (String s : mapping.getPathSpecs())
                    {
                        out.tag("url-pattern", s);
                    }
                if (mapping.getServletNames() != null)
                    for (String n : mapping.getServletNames())
                    {
                        out.tag("servlet-name", n);
                    }

                if (!mapping.isDefaultDispatches())
                {
                    if (mapping.appliesTo(DispatcherType.REQUEST))
                        out.tag("dispatcher", "REQUEST");
                    if (mapping.appliesTo(DispatcherType.ASYNC))
                        out.tag("dispatcher", "ASYNC");
                    if (mapping.appliesTo(DispatcherType.ERROR))
                        out.tag("dispatcher", "ERROR");
                    if (mapping.appliesTo(DispatcherType.FORWARD))
                        out.tag("dispatcher", "FORWARD");
                    if (mapping.appliesTo(DispatcherType.INCLUDE))
                        out.tag("dispatcher", "INCLUDE");
                }
                out.closeTag();
            }
        }

        if (servlets.getServlets() != null)
        {
            for (ServletHolder holder : servlets.getServlets())
            {
                outholder(out, md, holder);
            }
        }

        if (servlets.getServletMappings() != null)
        {
            for (ServletMapping mapping : servlets.getServletMappings())
            {
                out.openTag("servlet-mapping", origin(md, mapping.getServletName() + ".servlet.mappings"));
                out.tag("servlet-name", mapping.getServletName());
                if (mapping.getPathSpecs() != null)
                    for (String s : mapping.getPathSpecs())
                    {
                        out.tag("url-pattern", s);
                    }
                out.closeTag();
            }
        }

        // Security elements
        SecurityHandler security = _webApp.getSecurityHandler();

        if (security != null && (security.getRealmName() != null || security.getAuthMethod() != null))
        {
            out.openTag("login-config");
            if (security.getAuthMethod() != null)
                out.tag("auth-method", origin(md, "auth-method"), security.getAuthMethod());
            if (security.getRealmName() != null)
                out.tag("realm-name", origin(md, "realm-name"), security.getRealmName());

            if (Constraint.__FORM_AUTH.equalsIgnoreCase(security.getAuthMethod()))
            {
                out.openTag("form-login-config");
                out.tag("form-login-page", origin(md, "form-login-page"), security.getInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE));
                out.tag("form-error-page", origin(md, "form-error-page"), security.getInitParameter(FormAuthenticator.__FORM_ERROR_PAGE));
                out.closeTag();
            }

            out.closeTag();
        }

        if (security instanceof ConstraintAware)
        {
            ConstraintAware ca = (ConstraintAware)security;
            for (String r : ca.getRoles())
            {
                out.openTag("security-role")
                    .tag("role-name", r)
                    .closeTag();
            }

            for (ConstraintMapping m : ca.getConstraintMappings())
            {
                out.openTag("security-constraint");

                out.openTag("web-resource-collection");
                {
                    if (m.getConstraint().getName() != null)
                        out.tag("web-resource-name", m.getConstraint().getName());
                    if (m.getPathSpec() != null)
                        out.tag("url-pattern", origin(md, "constraint.url." + m.getPathSpec()), m.getPathSpec());
                    if (m.getMethod() != null)
                        out.tag("http-method", m.getMethod());

                    if (m.getMethodOmissions() != null)
                        for (String o : m.getMethodOmissions())
                        {
                            out.tag("http-method-omission", o);
                        }

                    out.closeTag();
                }

                if (m.getConstraint().getAuthenticate())
                {
                    String[] roles = m.getConstraint().getRoles();
                    if (roles != null && roles.length > 0)
                    {
                        out.openTag("auth-constraint");
                        if (m.getConstraint().getRoles() != null)
                            for (String r : m.getConstraint().getRoles())
                            {
                                out.tag("role-name", r);
                            }
                        out.closeTag();
                    }
                    else
                        out.tag("auth-constraint");
                }

                switch (m.getConstraint().getDataConstraint())
                {
                    case Constraint.DC_NONE:
                        out.openTag("user-data-constraint").tag("transport-guarantee", "NONE").closeTag();
                        break;

                    case Constraint.DC_INTEGRAL:
                        out.openTag("user-data-constraint").tag("transport-guarantee", "INTEGRAL").closeTag();
                        break;

                    case Constraint.DC_CONFIDENTIAL:
                        out.openTag("user-data-constraint").tag("transport-guarantee", "CONFIDENTIAL").closeTag();
                        break;

                    default:
                        break;
                }

                out.closeTag();
            }
        }

        if (_webApp.getWelcomeFiles() != null)
        {
            out.openTag("welcome-file-list");
            for (String welcomeFile : _webApp.getWelcomeFiles())
            {
                out.tag("welcome-file", welcomeFile);
            }
            out.closeTag();
        }

        Map<String, String> localeEncodings = _webApp.getLocaleEncodings();
        if (localeEncodings != null && !localeEncodings.isEmpty())
        {
            out.openTag("locale-encoding-mapping-list");
            for (Map.Entry<String, String> entry : localeEncodings.entrySet())
            {
                out.openTag("locale-encoding-mapping", origin(md, "locale-encoding." + entry.getKey()));
                out.tag("locale", entry.getKey());
                out.tag("encoding", entry.getValue());
                out.closeTag();
            }
            out.closeTag();
        }

        //session-config
        if (_webApp.getSessionHandler() != null)
        {
            out.openTag("session-config");
            int maxInactiveSec = _webApp.getSessionHandler().getMaxInactiveInterval();
            out.tag("session-timeout", (maxInactiveSec == 0 ? "0" : Integer.toString(maxInactiveSec / 60)));

            //cookie-config
            SessionCookieConfig cookieConfig = _webApp.getSessionHandler().getSessionCookieConfig();
            if (cookieConfig != null)
            {
                out.openTag("cookie-config");
                if (cookieConfig.getName() != null)
                    out.tag("name", origin(md, "cookie-config.name"), cookieConfig.getName());

                if (cookieConfig.getDomain() != null)
                    out.tag("domain", origin(md, "cookie-config.domain"), cookieConfig.getDomain());

                if (cookieConfig.getPath() != null)
                    out.tag("path", origin(md, "cookie-config.path"), cookieConfig.getPath());

                if (cookieConfig.getComment() != null)
                    out.tag("comment", origin(md, "cookie-config.comment"), cookieConfig.getComment());

                out.tag("http-only", origin(md, "cookie-config.http-only"), Boolean.toString(cookieConfig.isHttpOnly()));
                out.tag("secure", origin(md, "cookie-config.secure"), Boolean.toString(cookieConfig.isSecure()));
                out.tag("max-age", origin(md, "cookie-config.max-age"), Integer.toString(cookieConfig.getMaxAge()));
                out.closeTag();
            }

            // tracking-modes
            Set<SessionTrackingMode> modes = _webApp.getSessionHandler().getEffectiveSessionTrackingModes();
            if (modes != null)
            {
                for (SessionTrackingMode mode : modes)
                {
                    out.tag("tracking-mode", mode.toString());
                }
            }

            out.closeTag();
        }

        //error-pages
        Map<String, String> errorPages = ((ErrorPageErrorHandler)_webApp.getErrorHandler()).getErrorPages();
        if (errorPages != null)
        {
            for (Map.Entry<String, String> entry : errorPages.entrySet())
            {
                out.openTag("error-page", origin(md, "error." + entry.getKey()));
                //a global or default error page has no code or exception               
                if (!ErrorPageErrorHandler.GLOBAL_ERROR_PAGE.equals(entry.getKey()))
                {
                    if (entry.getKey().matches("\\d{3}"))
                        out.tag("error-code", entry.getKey());
                    else
                        out.tag("exception-type", entry.getKey());
                }
                out.tag("location", entry.getValue());
                out.closeTag();
            }
        }

        //mime-types
        MimeTypes mimeTypes = _webApp.getMimeTypes();
        if (mimeTypes != null)
        {
            for (Map.Entry<String, String> entry : mimeTypes.getMimeMap().entrySet())
            {
                out.openTag("mime-mapping");
                out.tag("extension", origin(md, "extension." + entry.getKey()), entry.getKey());
                out.tag("mime-type", entry.getValue());
                out.closeTag();
            }
        }

        //jsp-config
        JspConfig jspConfig = (JspConfig)_webApp.getServletContext().getJspConfigDescriptor();
        if (jspConfig != null)
        {
            out.openTag("jsp-config");
            Collection<TaglibDescriptor> tlds = jspConfig.getTaglibs();
            if (tlds != null && !tlds.isEmpty())
            {
                for (TaglibDescriptor tld : tlds)
                {
                    out.openTag("taglib");
                    out.tag("taglib-uri", tld.getTaglibURI());
                    out.tag("taglib-location", tld.getTaglibLocation());
                    out.closeTag();
                }
            }

            Collection<JspPropertyGroupDescriptor> jspPropertyGroups = jspConfig.getJspPropertyGroups();
            if (jspPropertyGroups != null && !jspPropertyGroups.isEmpty())
            {
                for (JspPropertyGroupDescriptor jspPropertyGroup : jspPropertyGroups)
                {
                    out.openTag("jsp-property-group");
                    Collection<String> strings = jspPropertyGroup.getUrlPatterns();
                    if (strings != null && !strings.isEmpty())
                    {
                        for (String urlPattern : strings)
                        {
                            out.tag("url-pattern", urlPattern);
                        }
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
                        for (String prelude : strings)
                        {
                            out.tag("include-prelude", prelude);
                        }
                    }

                    strings = jspPropertyGroup.getIncludeCodas();
                    if (strings != null && !strings.isEmpty())
                    {
                        for (String coda : strings)
                        {
                            out.tag("include-coda", coda);
                        }
                    }

                    out.closeTag();
                }
            }

            out.closeTag();
        }

        //lifecycle: post-construct, pre-destroy
        LifeCycleCallbackCollection lifecycles = ((LifeCycleCallbackCollection)_webApp.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION));
        if (lifecycles != null)
        {
            Collection<LifeCycleCallback> tmp = lifecycles.getPostConstructCallbacks();

            for (LifeCycleCallback c : tmp)
            {
                out.openTag("post-construct");
                out.tag("lifecycle-callback-class", c.getTargetClassName());
                out.tag("lifecycle-callback-method", c.getMethodName());
                out.closeTag();
            }

            tmp = lifecycles.getPreDestroyCallbacks();
            for (LifeCycleCallback c : tmp)
            {
                out.openTag("pre-destroy");
                out.tag("lifecycle-callback-class", c.getTargetClassName());
                out.tag("lifecycle-callback-method", c.getMethodName());
                out.closeTag();
            }
        }

        out.literal(_extraXML);

        out.closeTag();
    }

    /**
     * Turn attribute into context-param to store.
     */
    private void addContextParamFromAttribute(XmlAppendable out, String attribute) throws IOException
    {
        Object o = _webApp.getAttribute(attribute);
        if (o == null)
            return;

        Collection<?> c = (o instanceof Collection) ? (Collection<?>)o : Collections.singletonList(o);
        StringBuilder v = new StringBuilder();
        for (Object i : c)
        {
            if (i != null)
            {
                if (v.length() > 0)
                    v.append(",\n    ");
                else
                    v.append("\n    ");
                QuotedStringTokenizer.quote(v, i.toString());
            }
        }
        out.openTag("context-param")
            .tag("param-name", attribute)
            .tagCDATA("param-value", v.toString())
            .closeTag();
    }

    /**
     * Turn context attribute into context-param to store.
     */
    private void addContextParamFromAttribute(XmlAppendable out, String attribute, AttributeNormalizer normalizer) throws IOException
    {
        Object o = _webApp.getAttribute(attribute);
        if (o == null)
            return;

        Collection<?> c = (o instanceof Collection) ? (Collection<?>)o : Collections.singletonList(o);
        StringBuilder v = new StringBuilder();
        for (Object i : c)
        {
            if (i != null)
            {
                if (v.length() > 0)
                    v.append(",\n    ");
                else
                    v.append("\n    ");
                QuotedStringTokenizer.quote(v, normalizer.normalize(i));
            }
        }
        out.openTag("context-param")
            .tag("param-name", attribute)
            .tagCDATA("param-value", v.toString())
            .closeTag();
    }

    /**
     * Generate xml for a Holder (Filter/Servlet)
     */
    private void outholder(XmlAppendable out, MetaData md, FilterHolder holder) throws IOException
    {
        if (LOG.isDebugEnabled())
            out.openTag("filter", Collections.singletonMap("source", holder.getSource().toString()));
        else
            out.openTag("filter");

        String n = holder.getName();
        out.tag("filter-name", n);

        String ot = n + ".filter.";

        if (holder instanceof FilterHolder)
        {
            out.tag("filter-class", origin(md, ot + "filter-class"), holder.getClassName());
            out.tag("async-supported", origin(md, ot + "async-supported"), holder.isAsyncSupported() ? "true" : "false");
        }

        for (String p : holder.getInitParameters().keySet())
        {
            out.openTag("init-param", origin(md, ot + "init-param." + p))
                .tag("param-name", p)
                .tag("param-value", holder.getInitParameter(p))
                .closeTag();
        }

        out.closeTag();
    }

    private void outholder(XmlAppendable out, MetaData md, ServletHolder holder) throws IOException
    {

        if (LOG.isDebugEnabled())
            out.openTag("servlet", Collections.singletonMap("source", holder.getSource().toString()));
        else
            out.openTag("servlet");

        String n = holder.getName();
        out.tag("servlet-name", n);

        String ot = n + ".servlet.";

        ServletHolder s = holder;
        if (s.getForcedPath() != null && s.getClassName() == null)
            out.tag("jsp-file", s.getForcedPath());
        else
            out.tag("servlet-class", origin(md, ot + "servlet-class"), s.getClassName());

        for (String p : holder.getInitParameters().keySet())
        {
            if ("jsp".equalsIgnoreCase(n) && "scratchdir".equalsIgnoreCase(p)) //don't preconfigure the temp dir for jsp output
                continue;
            out.openTag("init-param", origin(md, ot + "init-param." + p))
                .tag("param-name", p)
                .tag("param-value", holder.getInitParameter(p))
                .closeTag();
        }

        if (s.getInitOrder() >= 0)
            out.tag("load-on-startup", Integer.toString(s.getInitOrder()));

        if (!s.isEnabled())
            out.tag("enabled", origin(md, ot + "enabled"), "false");

        out.tag("async-supported", origin(md, ot + "async-supported"), holder.isAsyncSupported() ? "true" : "false");

        if (s.getRunAsRole() != null)
            out.openTag("run-as", origin(md, ot + "run-as"))
                .tag("role-name", s.getRunAsRole())
                .closeTag();

        Map<String, String> roles = s.getRoleRefMap();
        if (roles != null)
        {
            for (Map.Entry<String, String> e : roles.entrySet())
            {
                out.openTag("security-role-ref", origin(md, ot + "role-name." + e.getKey()))
                    .tag("role-name", e.getKey())
                    .tag("role-link", e.getValue())
                    .closeTag();
            }
        }

        //multipart-config
        MultipartConfigElement multipartConfig = ((ServletHolder.Registration)s.getRegistration()).getMultipartConfig();
        if (multipartConfig != null)
        {
            out.openTag("multipart-config", origin(md, s.getName() + ".servlet.multipart-config"));
            if (multipartConfig.getLocation() != null)
                out.tag("location", multipartConfig.getLocation());
            out.tag("max-file-size", Long.toString(multipartConfig.getMaxFileSize()));
            out.tag("max-request-size", Long.toString(multipartConfig.getMaxRequestSize()));
            out.tag("file-size-threshold", Long.toString(multipartConfig.getFileSizeThreshold()));
            out.closeTag();
        }

        out.closeTag();
    }

    /**
     * Find the origin (web.xml, fragment, annotation etc) of a web artifact from MetaData.
     *
     * @param md the metadata
     * @param name the name
     * @return the origin map
     */
    public Map<String, String> origin(MetaData md, String name)
    {
        if (!_generateOrigin)
            return Collections.emptyMap();
        if (name == null)
            return Collections.emptyMap();
        OriginInfo origin = md.getOriginInfo(name);
        if (LOG.isDebugEnabled())
            LOG.debug("origin of " + name + " is " + origin);
        if (origin == null)
            return Collections.emptyMap();
        return Collections.singletonMap(_originAttribute, origin.toString() + ":" + (_count++));
    }
}
