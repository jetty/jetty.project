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

package org.eclipse.jetty.osgi.boot;

/**
 * OSGiWebappConstants
 *
 *
 * Constants (MANIFEST headers, service properties etc) associated with deploying
 * webapps into OSGi via Jetty.
 */
public class OSGiWebappConstants
{
    /**
     * service property osgi.web.symbolicname. See OSGi r4
     */
    public static final String OSGI_WEB_SYMBOLICNAME = "osgi.web.symbolicname";

    /**
     * service property osgi.web.symbolicname. See OSGi r4
     */
    public static final String OSGI_WEB_VERSION = "osgi.web.version";

    /**
     * service property osgi.web.contextpath. See OSGi r4
     */
    public static final String OSGI_WEB_CONTEXTPATH = "osgi.web.contextpath";

    /**
     * See OSGi r4 p.427
     */
    public static final String OSGI_BUNDLECONTEXT = "osgi-bundlecontext";

    /**
     * url scheme to deploy war file as bundled webapp
     */
    public static final String RFC66_WAR_URL_SCHEME = "war";

    /**
     * Name of the header that defines the context path for the embedded webapp.
     */
    public static final String RFC66_WEB_CONTEXTPATH = "Web-ContextPath";

    /**
     * Name of the header that defines the path to the folder where the jsp
     * files are extracted.
     */
    public static final String RFC66_JSP_EXTRACT_LOCATION = "Jsp-ExtractLocation";

    /**
     * Name of the servlet context attribute that points to the bundle context.
     */
    public static final String RFC66_OSGI_BUNDLE_CONTEXT = "osgi-bundlecontext";

    /**
     * Name of the servlet context attribute that points to the bundle object.
     * We can't always rely on the bundle-context as there might be no such thing.
     */
    public static final String JETTY_OSGI_BUNDLE = "osgi-bundle";

    /**
     * List of relative pathes within the bundle to the jetty context files.
     */
    public static final String JETTY_CONTEXT_FILE_PATH = "Jetty-ContextFilePath";

    /**
     * path within the bundle to the folder that contains the basic resources.
     */
    @Deprecated
    public static final String JETTY_WAR_FOLDER_PATH = "Jetty-WarFolderPath";
    public static final String JETTY_WAR_RESOURCE_PATH = "Jetty-WarResourcePath";

    /**
     * path within a fragment hosted by a web-bundle to a folder that contains basic resources.
     * the path is appended to the lookup path where jetty locates static resources
     */
    @Deprecated
    public static final String JETTY_WAR_FRAGMENT_FOLDER_PATH = "Jetty-WarFragmentFolderPath";
    public static final String JETTY_WAR_FRAGMENT_RESOURCE_PATH = "Jetty-WarFragmentResourcePath";

    /**
     * path within a fragment hosted by a web-bundle to a folder that contains basic resources.
     * The path is prefixed to the lookup path where jetty locates static resources:
     * this will override static resources with the same name in the web-bundle.
     */
    @Deprecated
    public static final String JETTY_WAR_PATCH_FRAGMENT_FOLDER_PATH = "Jetty-WarPatchFragmentFolderPath";
    public static final String JETTY_WAR_PREPEND_FRAGMENT_RESOURCE_PATH = "Jetty-WarPrependFragmentResourcePath";

    /**
     * installation path of webapp bundle
     */
    public static final String JETTY_BUNDLE_ROOT = "bundle.root";
    /**
     * web app context path
     *
     * @deprecated see RFC66_WEB_CONTEXTPATH
     */
    public static final String SERVICE_PROP_CONTEXT_PATH = "contextPath";

    /**
     * Path to the web application base folder
     *
     * @deprecated see JETTY_WAR_FOLDER_PATH
     */
    public static final String SERVICE_PROP_WAR = "war";

    /**
     * Extra classpath
     *
     * @deprecated see JETTY_EXTRA_CLASSPATH
     */
    public static final String SERVICE_PROP_EXTRA_CLASSPATH = "extraClasspath";

    public static final String JETTY_EXTRA_CLASSPATH = "Jetty-extraClasspath";

    /**
     * jetty context file path
     *
     * @deprecated see JETTY_CONTEXT_FILE_PATH
     */
    public static final String SERVICE_PROP_CONTEXT_FILE_PATH = "contextFilePath";

    /**
     * web.xml file path
     *
     * @deprecated see JETTY_WEB_XML_PATH
     */
    public static final String SERVICE_PROP_WEB_XML_PATH = "webXmlFilePath";

    public static final String JETTY_WEB_XML_PATH = "Jetty-WebXmlFilePath";

    /**
     * defaultweb.xml file path
     *
     * @deprecated see JETTY_DEFAULT_WEB_XML_PATH
     */
    public static final String SERVICE_PROP_DEFAULT_WEB_XML_PATH = "defaultWebXmlFilePath";

    public static final String JETTY_DEFAULT_WEB_XML_PATH = "Jetty-defaultWebXmlFilePath";

    /**
     * path to the base folder that overrides the computed bundle installation
     * location if not null useful to install webapps or jetty context files
     * that are in fact not embedded in a bundle
     *
     * @deprecated see JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE
     */
    public static final String SERVICE_PROP_BUNDLE_INSTALL_LOCATION_OVERRIDE = "thisBundleInstall";

    public static final String JETTY_BUNDLE_INSTALL_LOCATION_OVERRIDE = "Jetty-bundleInstall";

    /**
     * Comma separated list of bundles that contain tld file used by the webapp.
     */
    public static final String REQUIRE_TLD_BUNDLE = "Require-TldBundle";
    /**
     * Comma separated list of bundles that contain tld file used by the webapp.
     * Both the name of the manifest header and the name of the service property.
     */
    public static final String SERVICE_PROP_REQUIRE_TLD_BUNDLE = REQUIRE_TLD_BUNDLE;

    public static final String WATERMARK = "o.e.j.o.b.watermark";

    /**
     * Set of extra dirs that must not be served by osgi webapps
     */
    public static final String[] DEFAULT_PROTECTED_OSGI_TARGETS = {"/osgi-inf", "/osgi-opts"};
}
