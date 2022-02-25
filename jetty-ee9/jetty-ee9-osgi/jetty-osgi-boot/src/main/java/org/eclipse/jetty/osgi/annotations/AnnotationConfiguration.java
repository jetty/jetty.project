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

package org.eclipse.jetty.osgi.annotations;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashSet;
import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import org.eclipse.jetty.annotations.AnnotationParser.Handler;
import org.eclipse.jetty.osgi.boot.OSGiMetaInfConfiguration;
import org.eclipse.jetty.osgi.boot.OSGiWebappConstants;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extend the AnnotationConfiguration to support OSGi:
 * Look for annotations inside WEB-INF/lib and also in the fragments and required bundles.
 * Discover them using a scanner adapted to OSGi instead of the jarscanner.
 */
public class AnnotationConfiguration extends org.eclipse.jetty.annotations.AnnotationConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(org.eclipse.jetty.annotations.AnnotationConfiguration.class);

    public class BundleParserTask extends ParserTask
    {

        public BundleParserTask(AnnotationParser parser, Set<? extends Handler> handlers, Resource resource)
        {
            super(parser, handlers, resource);
        }

        @Override
        public Void call() throws Exception
        {
            if (_parser != null)
            {
                org.eclipse.jetty.osgi.annotations.AnnotationParser osgiAnnotationParser = (org.eclipse.jetty.osgi.annotations.AnnotationParser)_parser;
                Bundle bundle = osgiAnnotationParser.getBundle(_resource);
                if (_stat != null)
                    _stat.start();
                osgiAnnotationParser.parse(_handlers, bundle);
                if (_stat != null)
                    _stat.end();
            }
            return null;
        }
    }

    public AnnotationConfiguration()
    {
    }

    @Override
    public Class<? extends Configuration> replaces()
    {
        return org.eclipse.jetty.annotations.AnnotationConfiguration.class;
    }

    /**
     * This parser scans the bundles using the OSGi APIs instead of assuming a jar.
     */
    @Override
    protected org.eclipse.jetty.annotations.AnnotationParser createAnnotationParser(int javaTargetVersion)
    {
        return new AnnotationParser(javaTargetVersion);
    }

    @Override
    public Resource getJarFor(ServletContainerInitializer service) throws MalformedURLException, IOException
    {
        Resource resource = super.getJarFor(service);
        // TODO This is not correct, but implemented like this to be bug for bug compatible
        // with previous implementation that could only handle actual jars and not bundles.
        if (resource != null && !resource.toString().endsWith(".jar"))
            return null;
        return resource;
    }

    /**
     * Here is the order in which jars and osgi artifacts are scanned for discoverable annotations.
     * <ol>
     * <li>The container jars are scanned.</li>
     * <li>The WEB-INF/classes are scanned</li>
     * <li>The osgi fragment to the web bundle are parsed.</li>
     * <li>The WEB-INF/lib are scanned</li>
     * <li>The required bundles are parsed</li>
     * </ol>
     */
    @Override
    public void parseWebInfLib(WebAppContext context, org.eclipse.jetty.annotations.AnnotationParser parser)
        throws Exception
    {
        AnnotationParser oparser = (AnnotationParser)parser;

        if (_webInfLibStats == null)
            _webInfLibStats = new CounterStatistic();

        Bundle webbundle = (Bundle)context.getAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE);
        @SuppressWarnings("unchecked")
        Set<Bundle> fragAndRequiredBundles = (Set<Bundle>)context.getAttribute(OSGiMetaInfConfiguration.FRAGMENT_AND_REQUIRED_BUNDLES);
        if (fragAndRequiredBundles != null)
        {
            //index and scan fragments
            for (Bundle bundle : fragAndRequiredBundles)
            {
                //skip bundles that have been uninstalled since we discovered them
                if (bundle.getState() == Bundle.UNINSTALLED)
                    continue;

                Resource bundleRes = oparser.indexBundle(bundle);
                if (!context.getMetaData().getWebInfResources(false).contains(bundleRes))
                {
                    context.getMetaData().addWebInfResource(bundleRes);
                }

                if (bundle.getHeaders().get(Constants.FRAGMENT_HOST) != null)
                {
                    //a fragment indeed:
                    parseFragmentBundle(context, oparser, webbundle, bundle);
                    _webInfLibStats.increment();
                }
            }
        }
        //scan ourselves
        oparser.indexBundle(webbundle);
        parseWebBundle(context, oparser, webbundle);
        _webInfLibStats.increment();

        //scan the WEB-INF/lib
        super.parseWebInfLib(context, parser);
        if (fragAndRequiredBundles != null)
        {
            //scan the required bundles
            for (Bundle requiredBundle : fragAndRequiredBundles)
            {
                //skip bundles that have been uninstalled since we discovered them
                if (requiredBundle.getState() == Bundle.UNINSTALLED)
                    continue;

                if (requiredBundle.getHeaders().get(Constants.FRAGMENT_HOST) == null)
                {
                    //a bundle indeed:
                    parseRequiredBundle(context, oparser, webbundle, requiredBundle);
                    _webInfLibStats.increment();
                }
            }
        }
    }

    /**
     * Scan a fragment bundle for servlet annotations
     *
     * @param context The webapp context
     * @param parser The parser
     * @param webbundle The current webbundle
     * @param fragmentBundle The OSGi fragment bundle to scan
     * @throws Exception if unable to parse fragment bundle
     */
    protected void parseFragmentBundle(WebAppContext context, AnnotationParser parser,
                                       Bundle webbundle, Bundle fragmentBundle) throws Exception
    {
        parseBundle(context, parser, webbundle, fragmentBundle);
    }

    /**
     * Scan a bundle required by the webbundle for servlet annotations
     *
     * @param context The webapp context
     * @param parser The parser
     * @param webbundle The current webbundle
     * @throws Exception if unable to parse the web bundle
     */
    protected void parseWebBundle(WebAppContext context, AnnotationParser parser, Bundle webbundle)
        throws Exception
    {
        parseBundle(context, parser, webbundle, webbundle);
    }

    @Override
    public void parseWebInfClasses(WebAppContext context, org.eclipse.jetty.annotations.AnnotationParser parser)
        throws Exception
    {
        Bundle webbundle = (Bundle)context.getAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE);
        String bundleClasspath = (String)webbundle.getHeaders().get(Constants.BUNDLE_CLASSPATH);
        //only scan WEB-INF/classes if we didn't already scan it with parseWebBundle
        if (StringUtil.isBlank(bundleClasspath) || !bundleClasspath.contains("WEB-INF/classes"))
            super.parseWebInfClasses(context, parser);
    }

    /**
     * Scan a bundle required by the webbundle for servlet annotations
     *
     * @param context The webapp context
     * @param parser The parser
     * @param webbundle The current webbundle
     * @param requiredBundle The OSGi required bundle to scan
     * @throws Exception if unable to parse the required bundle
     */
    protected void parseRequiredBundle(WebAppContext context, AnnotationParser parser,
                                       Bundle webbundle, Bundle requiredBundle) throws Exception
    {
        parseBundle(context, parser, webbundle, requiredBundle);
    }

    protected void parseBundle(WebAppContext context, AnnotationParser parser,
                               Bundle webbundle, Bundle bundle) throws Exception
    {

        Resource bundleRes = parser.getResource(bundle);
        Set<Handler> handlers = new HashSet<>();
        handlers.addAll(_discoverableAnnotationHandlers);
        if (_classInheritanceHandler != null)
            handlers.add(_classInheritanceHandler);
        handlers.addAll(_containerInitializerAnnotationHandlers);

        if (_parserTasks != null)
        {
            BundleParserTask task = new BundleParserTask(parser, handlers, bundleRes);
            _parserTasks.add(task);
            if (LOG.isDebugEnabled())
                task.setStatistic(new TimeStatistic());
        }
    }
}
