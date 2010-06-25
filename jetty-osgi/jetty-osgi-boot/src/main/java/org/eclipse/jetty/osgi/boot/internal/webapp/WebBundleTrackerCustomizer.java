// ========================================================================
// Copyright (c) 2009-2010 Intalio, Inc.
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
package org.eclipse.jetty.osgi.boot.internal.webapp;

import java.net.URL;
import java.util.Dictionary;

import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.OSGiWebappConstants;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;


/**
 * Support bundles that declare the webapp directly through headers in their
 * manifest.
 * <p>
 * Those headers will define a new WebApplication:
 * <ul>
 * <li>Web-ContextPath</li>
 * <li>Jetty-WarFolderPath</li>
 * </ul>
 * </p>
 * <p>
 * Those headers will define a new app started via a jetty-context or a list of
 * them. ',' column is the separator between the various context files.
 * <ul>
 * <li>Jetty-ContextFilePath</li>
 * </ul>
 * </p>
 * And generate a jetty WebAppContext or another ContextHandler then registers
 * it as service. Kind of simpler than declarative services and their xml files.
 * Also avoid having the contributing bundle depend on jetty's package for
 * WebApp.
 * 
 * @author hmalphettes
 */
public class WebBundleTrackerCustomizer implements BundleTrackerCustomizer {
	

	/**
	 * A bundle is being added to the <code>BundleTracker</code>.
	 * 
	 * <p>
	 * This method is called before a bundle which matched the search parameters
	 * of the <code>BundleTracker</code> is added to the
	 * <code>BundleTracker</code>. This method should return the object to be
	 * tracked for the specified <code>Bundle</code>. The returned object is
	 * stored in the <code>BundleTracker</code> and is available from the
	 * {@link BundleTracker#getObject(Bundle) getObject} method.
	 * 
	 * @param bundle The <code>Bundle</code> being added to the
	 *        <code>BundleTracker</code>.
	 * @param event The bundle event which caused this customizer method to be
	 *        called or <code>null</code> if there is no bundle event associated
	 *        with the call to this method.
	 * @return The object to be tracked for the specified <code>Bundle</code>
	 *         object or <code>null</code> if the specified <code>Bundle</code>
	 *         object should not be tracked.
	 */
	public Object addingBundle(Bundle bundle, BundleEvent event)
	{
		if (bundle.getState() == Bundle.ACTIVE)
		{
			boolean isWebBundle = register(bundle);
			return isWebBundle ? bundle : null;
		}
		else if (bundle.getState() == Bundle.STOPPING)
		{
			unregister(bundle);
		}
		else
		{
			//we should not be called in that state as
			//we are registered only for ACTIVE and STOPPING
		}
		return null;
	}

	/**
	 * A bundle tracked by the <code>BundleTracker</code> has been modified.
	 * 
	 * <p>
	 * This method is called when a bundle being tracked by the
	 * <code>BundleTracker</code> has had its state modified.
	 * 
	 * @param bundle The <code>Bundle</code> whose state has been modified.
	 * @param event The bundle event which caused this customizer method to be
	 *        called or <code>null</code> if there is no bundle event associated
	 *        with the call to this method.
	 * @param object The tracked object for the specified bundle.
	 */
	public void modifiedBundle(Bundle bundle, BundleEvent event,
			Object object)
	{
		//nothing the web-bundle was already track. something changed.
		//we only reload the webapps if the bundle is stopped and restarted.
//		System.err.println(bundle.getSymbolicName());
		if (bundle.getState() == Bundle.STOPPING || bundle.getState() == Bundle.ACTIVE)
		{
			unregister(bundle);
		}
		if (bundle.getState() == Bundle.ACTIVE)
		{
			register(bundle);
		}
	}

	/**
	 * A bundle tracked by the <code>BundleTracker</code> has been removed.
	 * 
	 * <p>
	 * This method is called after a bundle is no longer being tracked by the
	 * <code>BundleTracker</code>.
	 * 
	 * @param bundle The <code>Bundle</code> that has been removed.
	 * @param event The bundle event which caused this customizer method to be
	 *        called or <code>null</code> if there is no bundle event associated
	 *        with the call to this method.
	 * @param object The tracked object for the specified bundle.
	 */
	public void removedBundle(Bundle bundle, BundleEvent event,
			Object object)
	{
		unregister(bundle);
	}
	
	
	/**
	 * @param bundle
	 * @return true if this bundle in indeed a web-bundle.
	 */
    private boolean register(Bundle bundle)
    {
        Dictionary<?, ?> dic = bundle.getHeaders();
        String warFolderRelativePath = (String)dic.get(OSGiWebappConstants.JETTY_WAR_FOLDER_PATH);
        if (warFolderRelativePath != null)
        {
            String contextPath = (String)dic.get(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH);
            if (contextPath == null || !contextPath.startsWith("/"))
            {
                throw new IllegalArgumentException();
            }
            // create the corresponding service and publish it in the context of
            // the contributor bundle.
            try
            {
                JettyBootstrapActivator.registerWebapplication(bundle,warFolderRelativePath,contextPath);
                return true;
            }
            catch (Throwable e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
                return true;//maybe it did not work maybe it did. safer to track this bundle.
            }
        }
        else if (dic.get(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH) != null)
        {
            String contextFileRelativePath = (String)dic.get(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH);
            if (contextFileRelativePath == null)
            {
                // nothing to register here.
                return false;
            }
            // support for multiple webapps in the same bundle:
            String[] pathes = contextFileRelativePath.split(",;");
            for (String path : pathes)
            {
                try
                {
                    JettyBootstrapActivator.registerContext(bundle,path.trim());
                }
                catch (Throwable e)
                {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            return true;
        }
        else
        {
            // support for OSGi-RFC66; disclaimer, no access to the actual
            // (draft) of the spec: just a couple of posts on the
            // world-wide-web.
            URL rfc66Webxml = bundle.getEntry("/WEB-INF/web.xml");
            if (rfc66Webxml == null)
            {
                return false;// no webapp in here
            }
            // this is risky: should we make sure that there is no classes and
            // jars directly available
            // at the root of the of the bundle: otherwise they are accessible
            // through the browser. we should enforce that the whole classpath
            // is
            // pointing to files and folders inside WEB-INF. We should
            // filter-out
            // META-INF too
            String rfc66ContextPath = getWebContextPath(bundle,dic);
            try
            {
                JettyBootstrapActivator.registerWebapplication(bundle,".",rfc66ContextPath);
                return true;
            }
            catch (Throwable e)
            {
                // TODO Auto-generated catch block
                e.printStackTrace();
                return true;//maybe it did not work maybe it did. safer to track this bundle.
            }
        }
    }

    private String getWebContextPath(Bundle bundle, Dictionary<?, ?> dic)
    {
        String rfc66ContextPath = (String)dic.get(OSGiWebappConstants.RFC66_WEB_CONTEXTPATH);
        if (rfc66ContextPath == null)
        {
            // extract from the last token of the bundle's location:
            // (really ?
            // could consider processing the symbolic name as an alternative
            // the location will often reflect the version.
            // maybe this is relevant when the file is a war)
            String location = bundle.getLocation();
            String toks[] = location.replace('\\','/').split("/");
            rfc66ContextPath = toks[toks.length - 1];
            // remove .jar, .war etc:
            int lastDot = rfc66ContextPath.lastIndexOf('.');
            if (lastDot != -1)
            {
                rfc66ContextPath = rfc66ContextPath.substring(0,lastDot);
            }
        }
        if (!rfc66ContextPath.startsWith("/"))
        {
            rfc66ContextPath = "/" + rfc66ContextPath;
        }
        return rfc66ContextPath;
    }

    private void unregister(Bundle bundle)
    {
        // nothing to do: when the bundle is stopped, each one of its service
        // reference is also stopped and that is what we use to stop the
        // corresponding
        // webapps registered in that bundle.
    }


	
	
}
