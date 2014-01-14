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

package org.eclipse.jetty.osgi.annotations;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.annotations.AnnotationParser.Handler;
import org.eclipse.jetty.annotations.ClassNameResolver;
import org.eclipse.jetty.osgi.boot.OSGiWebappConstants;
import org.eclipse.jetty.osgi.boot.utils.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;

/**
 * Extend the AnnotationConfiguration to support OSGi:
 * Look for annotations inside WEB-INF/lib and also in the fragments and required bundles.
 * Discover them using a scanner adapted to OSGi instead of the jarscanner. 
 */
public class AnnotationConfiguration extends org.eclipse.jetty.annotations.AnnotationConfiguration
{
    private static final Logger LOG = Log.getLogger(org.eclipse.jetty.annotations.AnnotationConfiguration.class);
    
    public class BundleParserTask extends ParserTask
    {
        
        public BundleParserTask (AnnotationParser parser, Set<? extends Handler>handlers, Resource resource, ClassNameResolver resolver)
        {
            super(parser, handlers, resource, resolver);
        }

        public Void call() throws Exception
        {
            if (_parser != null)
            {
                org.eclipse.jetty.osgi.annotations.AnnotationParser osgiAnnotationParser = (org.eclipse.jetty.osgi.annotations.AnnotationParser)_parser;
                Bundle bundle = osgiAnnotationParser.getBundle(_resource);
                if (_stat != null)
                    _stat.start();
                osgiAnnotationParser.parse(_handlers, bundle,  _resolver); 
                if (_stat != null)
                    _stat.end();
            }
            return null;
        }
    }
    
    
    /**
     * This parser scans the bundles using the OSGi APIs instead of assuming a jar.
     */
    @Override
    protected org.eclipse.jetty.annotations.AnnotationParser createAnnotationParser()
    {
        return new AnnotationParser();
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
    public void parseWebInfLib (WebAppContext context, org.eclipse.jetty.annotations.AnnotationParser parser)
    throws Exception
    {
        AnnotationParser oparser = (AnnotationParser)parser;
        
        Bundle webbundle = (Bundle) context.getAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE);
        Bundle[] fragAndRequiredBundles = PackageAdminServiceTracker.INSTANCE.getFragmentsAndRequiredBundles(webbundle);
        if (fragAndRequiredBundles != null)
        {
            //index and scan fragments
            for (Bundle bundle : fragAndRequiredBundles)
            {
                Resource bundleRes = oparser.indexBundle(bundle);
                if (!context.getMetaData().getWebInfJars().contains(bundleRes))
                {
                    context.getMetaData().addWebInfJar(bundleRes);
                }
                
                if (bundle.getHeaders().get(Constants.FRAGMENT_HOST) != null)
                {
                    //a fragment indeed:
                    parseFragmentBundle(context,oparser,webbundle,bundle);
                }
            }
        }
        //scan ourselves
        oparser.indexBundle(webbundle);
        parseWebBundle(context,oparser,webbundle);
        
        //scan the WEB-INF/lib
        super.parseWebInfLib(context,parser);
        if (fragAndRequiredBundles != null)
        {
            //scan the required bundles
            for (Bundle requiredBundle : fragAndRequiredBundles)
            {
                if (requiredBundle.getHeaders().get(Constants.FRAGMENT_HOST) == null)
                {
                    //a bundle indeed:
                    parseRequiredBundle(context,oparser,webbundle,requiredBundle);
                }
            }
        }
    }
    
    /**
     * Scan a fragment bundle for servlet annotations
     * @param context The webapp context
     * @param parser The parser
     * @param webbundle The current webbundle
     * @param fragmentBundle The OSGi fragment bundle to scan
     * @throws Exception
     */
    protected void parseFragmentBundle(WebAppContext context, AnnotationParser parser,
            Bundle webbundle, Bundle fragmentBundle) throws Exception
    {
        parseBundle(context,parser,webbundle,fragmentBundle);
    }
    
    /**
     * Scan a bundle required by the webbundle for servlet annotations
     * @param context The webapp context
     * @param parser The parser
     * @param webbundle The current webbundle
     * @param fragmentBundle The OSGi required bundle to scan
     * @throws Exception
     */
    protected void parseWebBundle(WebAppContext context, AnnotationParser parser, Bundle webbundle)
    throws Exception
    {
        parseBundle(context,parser,webbundle,webbundle);
    }
    
    /**
     * Scan a bundle required by the webbundle for servlet annotations
     * @param context The webapp context
     * @param parser The parser
     * @param webbundle The current webbundle
     * @param fragmentBundle The OSGi required bundle to scan
     * @throws Exception
     */
    protected void parseRequiredBundle(WebAppContext context, AnnotationParser parser,
            Bundle webbundle, Bundle requiredBundle) throws Exception
    {
        parseBundle(context,parser,webbundle,requiredBundle);
    }
    
    protected void parseBundle(WebAppContext context, AnnotationParser parser,
                               Bundle webbundle, Bundle bundle) throws Exception
                               {

        Resource bundleRes = parser.getResource(bundle);  
        Set<Handler> handlers = new HashSet<Handler>();
        handlers.addAll(_discoverableAnnotationHandlers);
        if (_classInheritanceHandler != null)
            handlers.add(_classInheritanceHandler);
        handlers.addAll(_containerInitializerAnnotationHandlers);

        ClassNameResolver classNameResolver = createClassNameResolver(context);
        if (_parserTasks != null)
        {
            BundleParserTask task = new BundleParserTask(parser, handlers, bundleRes, classNameResolver);
            _parserTasks.add(task);
            if (LOG.isDebugEnabled())
                task.setStatistic(new TimeStatistic());
        }
    }
    
    /**
     * Returns the same classname resolver than for the webInfjar scanner
     * @param context
     * @return
     */
    protected ClassNameResolver createClassNameResolver(final WebAppContext context)
    {
        return createClassNameResolver(context,true,false,false,false);
    }
    
    protected ClassNameResolver createClassNameResolver(final WebAppContext context,
            final boolean excludeSysClass, final boolean excludeServerClass, final boolean excludeEverythingElse,
            final boolean overrideIsParenLoaderIsPriority)
    {
        return new ClassNameResolver ()
        {
            public boolean isExcluded (String name)
            {
                if (context.isSystemClass(name)) return excludeSysClass;
                if (context.isServerClass(name)) return excludeServerClass;
                return excludeEverythingElse;
            }

            public boolean shouldOverride (String name)
            { 
                //looking at system classpath
                if (context.isParentLoaderPriority())
                    return overrideIsParenLoaderIsPriority;
                return !overrideIsParenLoaderIsPriority;
            }
        };
    }

}
