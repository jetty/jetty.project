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

package org.eclipse.jetty.xml;

import java.net.URL;

import org.eclipse.jetty.util.Loader;

/**
 * Centralized location for common XML Entity registration methods used throughout Jetty.
 */
public final class XmlEntities
{
    /**
     * Register common XML Entities needed for generic XML parsing needs.
     * <p>
     * This includes {@code XMLSchema.dtd}, {@code xml.xsd}, and {@code datatypes.dtd}
     * </p>
     *
     * @param xmlParser the XmlParser to configure
     */
    public static void registerXmlDefaults(XmlParser xmlParser)
    {
        final URL schemadtd = getRequiredResource(XmlParser.class, "XMLSchema.dtd");
        xmlParser.redirectEntity("XMLSchema.dtd", schemadtd);
        xmlParser.redirectEntity("-//W3C//DTD XMLSCHEMA 200102//EN", schemadtd);
        xmlParser.redirectEntity("http://www.w3.org/2001/XMLSchema.dtd", schemadtd);
        xmlParser.redirectEntity("https://www.w3.org/2001/XMLSchema.dtd", schemadtd);

        final URL xmlxsd = getRequiredResource(XmlParser.class, "xml.xsd");
        xmlParser.redirectEntity("xml.xsd", xmlxsd);
        xmlParser.redirectEntity("http://www.w3.org/2001/xml.xsd", xmlxsd);
        xmlParser.redirectEntity("https://www.w3.org/2001/xml.xsd", xmlxsd);

        final URL datatypesdtd = getRequiredResource(XmlParser.class, "datatypes.dtd");
        xmlParser.redirectEntity("datatypes.dtd", datatypesdtd);
        xmlParser.redirectEntity("http://www.w3.org/2001/datatypes.dtd", datatypesdtd);
        xmlParser.redirectEntity("https://www.w3.org/2001/datatypes.dtd", datatypesdtd);
    }

    /**
     * Register the mandatory XML Entities needed for successful operation for an EE Servlet Environment.
     *
     * <p>
     * This includes all of the {@code web-app}, {@code jsp}, and dependent entities.
     * </p>
     *
     * @param xmlParser the XmlParser to configure
     * @param scope the class / package scope to look for the resources
     * @param servletMajorVersion the servlet major version to register against.
     */
    public static void registerWebEntities(XmlParser xmlParser, Class<?> scope, int servletMajorVersion)
    {
        String prefix = scope.getPackageName().replace('.', '/');

        final URL dtd22 = getRequiredResource(prefix + "/resources/web-app_2_2.dtd");
        final URL dtd23 = getRequiredResource(prefix + "/resources/web-app_2_3.dtd");

        final URL j2ee14xsd = getRequiredResource(prefix + "/resources/j2ee_1_4.xsd");
        final URL javaee5 = getRequiredResource(prefix + "/resources/javaee_5.xsd");
        final URL javaee6 = getRequiredResource(prefix + "/resources/javaee_6.xsd");
        final URL javaee7 = getRequiredResource(prefix + "/resources/javaee_7.xsd");
        final URL javaee8 = getRequiredResource(prefix + "/resources/javaee_8.xsd");

        final URL webapp24xsd = getRequiredResource(prefix + "/resources/web-app_2_4.xsd");
        final URL webapp25xsd = getRequiredResource(prefix + "/resources/web-app_2_5.xsd");
        final URL webapp30xsd = getRequiredResource(prefix + "/resources/web-app_3_0.xsd");
        final URL webapp31xsd = getRequiredResource(prefix + "/resources/web-app_3_1.xsd");
        final URL webapp40xsd = getRequiredResource(prefix + "/resources/web-app_4_0.xsd");

        final URL webcommon30xsd = getRequiredResource(prefix + "/resources/web-common_3_0.xsd");
        final URL webcommon31xsd = getRequiredResource(prefix + "/resources/web-common_3_1.xsd");
        final URL webcommon40xsd = getRequiredResource(prefix + "/resources/web-common_4_0.xsd");

        final URL webfragment30xsd = getRequiredResource(prefix + "/resources/web-fragment_3_0.xsd");
        final URL webfragment31xsd = getRequiredResource(prefix + "/resources/web-fragment_3_1.xsd");
        final URL webfragment40xsd = getRequiredResource(prefix + "/resources/web-fragment_4_0.xsd");

        final URL webservice11xsd = getRequiredResource(prefix + "/resources/j2ee_web_services_client_1_1.xsd");
        final URL webservice12xsd = getRequiredResource(prefix + "/resources/javaee_web_services_client_1_2.xsd");
        final URL webservice13xsd = getRequiredResource(prefix + "/resources/javaee_web_services_client_1_3.xsd");
        final URL webservice14xsd = getRequiredResource(prefix + "/resources/javaee_web_services_client_1_4.xsd");

        // Servlet 5 Support
        if (servletMajorVersion >= 5)
        {
            // TODO: These JSP resources do not exist in jetty-servlet-api-<ver>.jar (where do we get them from?)
            // They only exist in the jakarta-servlet-api-5.jar (or newer)

            // try both <servlet>/resources and <servlet>/jsp/resources for these
            final URL jsp20xsd = findRequiredResource(prefix + "/resources/jsp_2_0.xsd", prefix + "/jsp/resources/jsp_2_0.xsd");
            final URL jsp21xsd = findRequiredResource(prefix + "/resources/jsp_2_1.xsd", prefix + "/jsp/resources/jsp_2_1.xsd");
            final URL jsp22xsd = findRequiredResource(prefix + "/resources/jsp_2_2.xsd", prefix + "/jsp/resources/jsp_2_2.xsd");
            final URL jsp23xsd = findRequiredResource(prefix + "/resources/jsp_2_3.xsd", prefix + "/jsp/resources/jsp_2_3.xsd");
            final URL jsp30xsd = findRequiredResource(prefix + "/resources/jsp_3_0.xsd", prefix + "/jsp/resources/jsp_3_0.xsd");

            xmlParser.redirectEntity("jsp_2_0.xsd", jsp20xsd);
            xmlParser.redirectEntity("http://java.sun.com/xml/ns/j2ee/jsp_2_0.xsd", jsp20xsd);
            xmlParser.redirectEntity("http://java.sun.com/xml/ns/javaee/jsp_2_1.xsd", jsp21xsd);
            xmlParser.redirectEntity("jsp_2_2.xsd", jsp22xsd);
            xmlParser.redirectEntity("http://java.sun.com/xml/ns/javaee/jsp_2_2.xsd", jsp22xsd);
            xmlParser.redirectEntity("jsp_2_3.xsd", jsp23xsd);
            xmlParser.redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/jsp_2_3.xsd", jsp23xsd);
            xmlParser.redirectEntity("jsp_3_0.xsd", jsp30xsd);
            xmlParser.redirectEntity("https://jakarta.ee/xml/ns/jakartaee/jsp_3_0.xsd", jsp30xsd);

            // Servlet 5+ resources are always in scope "jakarta/servlet"
            final URL jakartaee9 = Loader.getResource("jakarta/servlet/resources/jakartaee_9.xsd");
            xmlParser.redirectEntity("https://javax.ee/xml/ns/javaxee/javaee_9.xsd", jakartaee9);

            final URL webapp50xsd = Loader.getResource("jakarta/servlet/resources/web-app_5_0.xsd");
            xmlParser.redirectEntity("webapp_5_0.xsd", webapp50xsd);
            xmlParser.redirectEntity("https://jakarta.ee/xml/ns/jakartaee/webapp_5_0.xsd", webapp50xsd);
            xmlParser.redirectEntity("web-app_5_0.xsd", webapp50xsd);
            xmlParser.redirectEntity("https://jakarta.ee/xml/ns/jakartaee/web-app_5_0.xsd", webapp50xsd);

            final URL webcommon50xsd = Loader.getResource("jakarta/servlet/resources/web-common_5_0.xsd");
            xmlParser.redirectEntity("web-common_5_0.xsd", webcommon50xsd);
            xmlParser.redirectEntity("https://jakarta.ee/xml/ns/jakartaee/web-common_5_0.xsd", webcommon50xsd);

            final URL webfragment50xsd = Loader.getResource("jakarta/servlet/resources/web-fragment_5_0.xsd");
            xmlParser.redirectEntity("web-fragment_5_0.xsd", webfragment50xsd);
            xmlParser.redirectEntity("https://jakarta.ee/xml/ns/jakartaee/web-fragment_5_0.xsd", webfragment50xsd);

            final URL webservice20xsd = Loader.getResource("jakarta/servlet/resources/jakartaee_web_services_client_2_0.xsd");
            xmlParser.redirectEntity("jakartaee_web_services_client_2_0.xsd", webservice20xsd);
            xmlParser.redirectEntity("https://jakarta.ee/xml/ns/jakartaee/jakartaee_web_services_client_2_0.xsd", webservice20xsd);
        }

        // Servlet 6 Support
        if (servletMajorVersion >= 6)
        {
            // Servlet 6+ resources are always in scope "jakarta/servlet"

            // TODO: verify if this is needed, as it seems to be missing from servlet-api-6.jar (the ee9 version was in servlet-api-5.jar)
            /*
            final URL jakartaee10 = Loader.getResource("jakarta/servlet/resources/jakartaee_10.xsd");
            xmlParser.redirectEntity("https://javax.ee/xml/ns/javaxee/javaee_10.xsd", jakartaee10);
             */

            final URL webapp60xsd = Loader.getResource("jakarta/servlet/resources/web-app_6_0.xsd");
            xmlParser.redirectEntity("webapp_6_0.xsd", webapp60xsd);
            xmlParser.redirectEntity("https://jakarta.ee/xml/ns/jakartaee/webapp_6_0.xsd", webapp60xsd);
            xmlParser.redirectEntity("web-app_6_0.xsd", webapp60xsd);
            xmlParser.redirectEntity("https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd", webapp60xsd);

            final URL webcommon60xsd = Loader.getResource("jakarta/servlet/resources/web-common_6_0.xsd");
            xmlParser.redirectEntity("web-common_6_0.xsd", webcommon60xsd);
            xmlParser.redirectEntity("https://jakarta.ee/xml/ns/jakartaee/web-common_6_0.xsd", webcommon60xsd);

            final URL webfragment60xsd = Loader.getResource("jakarta/servlet/resources/web-fragment_6_0.xsd");
            xmlParser.redirectEntity("web-fragment_6_0.xsd", webfragment60xsd);
            xmlParser.redirectEntity("https://jakarta.ee/xml/ns/jakartaee/web-fragment_6_0.xsd", webfragment60xsd);
        }

        // Now map the found resources to their public id / system ids
        xmlParser.redirectEntity("web-app_2_2.dtd", dtd22);
        xmlParser.redirectEntity("-//Sun Microsystems, Inc.//DTD Web Application 2.2//EN", dtd22);
        xmlParser.redirectEntity("web.dtd", dtd23);
        xmlParser.redirectEntity("web-app_2_3.dtd", dtd23);
        xmlParser.redirectEntity("-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN", dtd23);

        xmlParser.redirectEntity("j2ee_1_4.xsd", j2ee14xsd);
        xmlParser.redirectEntity("http://java.sun.com/xml/ns/j2ee/j2ee_1_4.xsd", j2ee14xsd);
        xmlParser.redirectEntity("http://java.sun.com/xml/ns/javaee/javaee_5.xsd", javaee5);
        xmlParser.redirectEntity("http://java.sun.com/xml/ns/javaee/javaee_6.xsd", javaee6);
        xmlParser.redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/javaee_7.xsd", javaee7);
        xmlParser.redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/javaee_8.xsd", javaee8);

        xmlParser.redirectEntity("web-common_3_0.xsd", webcommon30xsd);
        xmlParser.redirectEntity("http://java.sun.com/xml/ns/javaee/web-common_3_0.xsd", webcommon30xsd);
        xmlParser.redirectEntity("web-common_3_1.xsd", webcommon31xsd);
        xmlParser.redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-common_3_1.xsd", webcommon31xsd);
        xmlParser.redirectEntity("web-common_4_0.xsd", webcommon40xsd);
        xmlParser.redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-common_4_0.xsd", webcommon40xsd);

        xmlParser.redirectEntity("web-app_2_4.xsd", webapp24xsd);
        xmlParser.redirectEntity("http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd", webapp24xsd);
        xmlParser.redirectEntity("web-app_2_5.xsd", webapp25xsd);
        xmlParser.redirectEntity("http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd", webapp25xsd);
        xmlParser.redirectEntity("web-app_3_0.xsd", webapp30xsd);
        xmlParser.redirectEntity("http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd", webapp30xsd);
        xmlParser.redirectEntity("web-app_3_1.xsd", webapp31xsd);
        xmlParser.redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd", webapp31xsd);
        xmlParser.redirectEntity("web-app_4_0.xsd", webapp40xsd);
        xmlParser.redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd", webapp40xsd);

        // Handle linewrap hyphen error in PDF spec
        xmlParser.redirectEntity("webapp_4_0.xsd", webapp40xsd);
        xmlParser.redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/webapp_4_0.xsd", webapp40xsd);

        // handle jakartaee coordinates
        xmlParser.redirectEntity("http://xmlns.eclipse.org/xml/ns/jakartaee/web-app_4_0.xsd", webapp40xsd);

        xmlParser.redirectEntity("web-fragment_3_0.xsd", webfragment30xsd);
        xmlParser.redirectEntity("http://java.sun.com/xml/ns/javaee/web-fragment_3_0.xsd", webfragment30xsd);
        xmlParser.redirectEntity("web-fragment_3_1.xsd", webfragment31xsd);
        xmlParser.redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-fragment_3_1.xsd", webfragment31xsd);
        xmlParser.redirectEntity("web-fragment_4_0.xsd", webfragment40xsd);
        xmlParser.redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/web-fragment_4_0.xsd", webfragment40xsd);

        xmlParser.redirectEntity("j2ee_web_services_client_1_1.xsd", webservice11xsd);
        xmlParser.redirectEntity("http://www.ibm.com/webservices/xsd/j2ee_web_services_client_1_1.xsd", webservice11xsd);
        xmlParser.redirectEntity("javaee_web_services_client_1_2.xsd", webservice12xsd);
        xmlParser.redirectEntity("http://www.ibm.com/webservices/xsd/javaee_web_services_client_1_2.xsd", webservice12xsd);
        xmlParser.redirectEntity("javaee_web_services_client_1_3.xsd", webservice13xsd);
        xmlParser.redirectEntity("http://java.sun.com/xml/ns/javaee/javaee_web_services_client_1_3.xsd", webservice13xsd);
        xmlParser.redirectEntity("javaee_web_services_client_1_4.xsd", webservice14xsd);
        xmlParser.redirectEntity("http://xmlns.jcp.org/xml/ns/javaee/javaee_web_services_client_1_4.xsd", webservice14xsd);
    }

    /**
     * Find the RequiredResource from the provided list of potential names.
     *
     * @param names the list of possible names of the resource
     * @return the resource as a URL
     * @throws IllegalStateException if unable to find the resource on any name
     */
    private static URL findRequiredResource(String... names)
    {
        URL url;

        for (String name : names)
        {
            url = Loader.getResource(name);
            if (url != null)
                return url;
        }

        throw new IllegalStateException("Unable to find required resource: names=[" + String.join(", ", names) + "]");
    }

    /**
     * Get the RequiredResource from the provided Scope.
     * Using the same scope's JPMS rules and Package namespace.
     *
     * @param scope the scope to use for Resource lookup
     * @param name the name of the resource within the scope to get
     * @return the resource as a URL
     * @throws IllegalStateException if unable to find the resource
     */
    private static URL getRequiredResource(Class<?> scope, String name)
    {
        // using Class.getResource(String) here to satisfy JPMS rules
        URL url = scope.getResource(name);
        if (url == null)
            throw new IllegalStateException("Missing required resource: " + name);
        return url;
    }

    /**
     * Similar to {@link Loader#getResource(String)}, but throws an {@link IllegalStateException} if the requested resource
     * is not found in the classloaders.
     *
     * @param name the resource to look up
     * @return the URL to the resource
     * @throws IllegalStateException if unable to find the resource
     */
    public static URL getRequiredResource(String name)
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        URL url = null;

        if (loader != null)
            url = loader.getResource(name);

        if (url == null)
            url = ClassLoader.getSystemResource(name);

        if (url == null)
            throw new IllegalStateException("Missing required resource: " + name);

        return url;
    }
}
