// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
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
package org.eclipse.jetty.osgi.boot.logback;

import java.io.File;
import java.util.Map;

import org.eclipse.jetty.osgi.boot.internal.webapp.LibExtClassLoaderHelper;
import org.eclipse.jetty.osgi.boot.internal.webapp.OSGiWebappClassLoader;
import org.eclipse.jetty.osgi.boot.internal.webapp.LibExtClassLoaderHelper.IFilesInJettyHomeResourcesProcessor;
import org.eclipse.jetty.osgi.boot.logback.internal.LogbackInitializer;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;


/**
 * Pseudo fragment activator.
 * Called by the main org.eclipse.jetty.osgi.boot bundle.
 * Please note: this is not a real BundleActivator. Simply something called back by
 * the host bundle.
 * The fragment is in charge of placing a hook to configure logback
 * when the files inside jettyhome/resources are parsed.
 */
public class FragmentActivator implements BundleActivator, IFilesInJettyHomeResourcesProcessor
{
    /**
     * 
     */
    public void start(BundleContext context) throws Exception
    {
        LibExtClassLoaderHelper.registeredFilesInJettyHomeResourcesProcessors.add(this);
        
        //now let's make sure no log4j, no slf4j and no commons.logging
        //get inserted as a library that is not an osgi library
        OSGiWebappClassLoader.addClassThatIdentifiesAJarThatMustBeRejected("org.apache.commons.logging.Log");
        OSGiWebappClassLoader.addClassThatIdentifiesAJarThatMustBeRejected("org.apache.log4j.Logger");
        OSGiWebappClassLoader.addClassThatIdentifiesAJarThatMustBeRejected("org.slf4j.Logger");
        //OSGiWebappClassLoader.addClassThatIdentifiesAJarThatMustBeRejected(java.util.logging.Logger.class);
        
    }

    /**
     * Called when this bundle is stopped so the Framework can perform the
     * bundle-specific activities necessary to stop the bundle. In general, this
     * method should undo the work that the <code>BundleActivator.start</code>
     * method started. There should be no active threads that were started by
     * this bundle when this bundle returns. A stopped bundle must not call any
     * Framework objects.
     * 
     * <p>
     * This method must complete and return to its caller in a timely manner.
     * 
     * @param context The execution context of the bundle being stopped.
     * @throws Exception If this method throws an exception, the
     *         bundle is still marked as stopped, and the Framework will remove
     *         the bundle's listeners, unregister all services registered by the
     *         bundle, and release all services used by the bundle.
     */
    public void stop(BundleContext context) throws Exception
    {
    	LibExtClassLoaderHelper.registeredFilesInJettyHomeResourcesProcessors.remove(this);
    }
    
    public void processFilesInResourcesFolder(File jettyHome, Map<String,File> files)
    {
    	try
    	{
    		LogbackInitializer.processFilesInResourcesFolder(jettyHome, files);
    	}
    	catch (Throwable t)
    	{
    		t.printStackTrace();
    	}
    }
    
}
