//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.osgi.annotations;

import java.util.HashSet;
import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import org.eclipse.jetty.ee10.annotations.AnnotationParser.Handler;
import org.eclipse.jetty.ee10.osgi.boot.OSGiMetaInfConfiguration;
import org.eclipse.jetty.ee10.webapp.Configuration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.osgi.OSGiWebappConstants;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.statistic.CounterStatistic;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extend the AnnotationConfiguration to support OSGi:
 * Look for annotations inside WEB-INF/lib and also in the fragments and required bundles.
 * Discover them using a scanner adapted to OSGi instead of the jarscanner.
 */
public class AnnotationConfiguration extends org.eclipse.jetty.ee10.annotations.AnnotationConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(org.eclipse.jetty.ee10.annotations.AnnotationConfiguration.class);

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
                org.eclipse.jetty.ee10.osgi.annotations.AnnotationParser osgiAnnotationParser = (org.eclipse.jetty.ee10.osgi.annotations.AnnotationParser)_parser;
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
        return org.eclipse.jetty.ee10.annotations.AnnotationConfiguration.class;
    }

    /**
     * This parser scans the bundles using the OSGi APIs instead of assuming a jar.
     */
    @Override
    protected org.eclipse.jetty.ee10.annotations.AnnotationParser createAnnotationParser(int platform)
    {
        return new AnnotationParser(platform);
    }

    @Override
    protected Resource getJarFor(WebAppContext context, ServletContainerInitializer service)
    {
        Resource resource = super.getJarFor(context, service);
        // TODO This is not correct, but implemented like this to be bug for bug compatible
        // with previous implementation that could only handle actual jars and not bundles.
        if (resource != null && !FileID.isJavaArchive(resource.getURI()))
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
    public void parseWebInfLib(State state, org.eclipse.jetty.ee10.annotations.AnnotationParser parser)
        throws Exception
    {
        AnnotationParser oparser = (AnnotationParser)parser;

        if (state._webInfLibStats == null)
            state._webInfLibStats = new CounterStatistic();

        WebAppContext context = state._context;
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

                Resource bundleRes = oparser.indexBundle(ResourceFactory.of(context), bundle);
                if (!context.getMetaData().getWebInfResources(false).contains(bundleRes))
                {
                    context.getMetaData().addWebInfResource(bundleRes);
                }

                if (bundle.getHeaders().get(Constants.FRAGMENT_HOST) != null)
                {
                    //a fragment indeed:
                    parseFragmentBundle(state, oparser, webbundle, bundle);
                    state._webInfLibStats.increment();
                }
            }
        }
        //scan ourselves
        oparser.indexBundle(ResourceFactory.of(context), webbundle);
        parseWebBundle(state, oparser, webbundle);
        state._webInfLibStats.increment();

        //scan the WEB-INF/lib
        super.parseWebInfLib(state, parser);
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
                    parseRequiredBundle(state, oparser, webbundle, requiredBundle);
                    state._webInfLibStats.increment();
                }
            }
        }
    }

    /**
     * Scan a fragment bundle for servlet annotations
     *
     * @param state The webapp context state
     * @param parser The parser
     * @param webbundle The current webbundle
     * @param fragmentBundle The OSGi fragment bundle to scan
     * @throws Exception if unable to parse fragment bundle
     */
    protected void parseFragmentBundle(State state, AnnotationParser parser,
                                       Bundle webbundle, Bundle fragmentBundle) throws Exception
    {
        parseBundle(state, parser, webbundle, fragmentBundle);
    }

    /**
     * Scan a bundle required by the webbundle for servlet annotations
     *
     * @param state The webapp context state
     * @param parser The parser
     * @param webbundle The current webbundle
     * @throws Exception if unable to parse the web bundle
     */
    protected void parseWebBundle(State state, AnnotationParser parser, Bundle webbundle)
        throws Exception
    {
        parseBundle(state, parser, webbundle, webbundle);
    }

    @Override
    public void parseWebInfClasses(State state, org.eclipse.jetty.ee10.annotations.AnnotationParser parser)
    {
        WebAppContext context = state._context;
        Bundle webbundle = (Bundle)context.getAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE);
        String bundleClasspath = webbundle.getHeaders().get(Constants.BUNDLE_CLASSPATH);
        //only scan WEB-INF/classes if we didn't already scan it with parseWebBundle
        if (StringUtil.isBlank(bundleClasspath) || !bundleClasspath.contains("WEB-INF/classes"))
            super.parseWebInfClasses(state, parser);
    }

    /**
     * Scan a bundle required by the webbundle for servlet annotations
     *
     * @param state The webapp annotation parse state
     * @param parser The parser
     * @param webbundle The current webbundle
     * @param requiredBundle The OSGi required bundle to scan
     * @throws Exception if unable to parse the required bundle
     */
    protected void parseRequiredBundle(State state, AnnotationParser parser,
                                       Bundle webbundle, Bundle requiredBundle) throws Exception
    {
        parseBundle(state, parser, webbundle, requiredBundle);
    }

    protected void parseBundle(State state, AnnotationParser parser, Bundle webbundle, Bundle bundle)
    {

        Resource bundleRes = parser.getResource(bundle);
        Set<Handler> handlers = new HashSet<>(state._discoverableAnnotationHandlers);
        if (state._classInheritanceHandler != null)
            handlers.add(state._classInheritanceHandler);
        handlers.addAll(state._containerInitializerAnnotationHandlers);

        if (state._parserTasks != null)
        {
            BundleParserTask task = new BundleParserTask(parser, handlers, bundleRes);
            state._parserTasks.add(task);
            if (LOG.isDebugEnabled())
                task.setStatistic(new TimeStatistic());
        }
    }
}
