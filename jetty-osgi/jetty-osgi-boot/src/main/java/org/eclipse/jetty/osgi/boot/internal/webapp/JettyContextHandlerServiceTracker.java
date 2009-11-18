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
package org.eclipse.jetty.osgi.boot.internal.webapp;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.osgi.boot.JettyBootstrapActivator;
import org.eclipse.jetty.osgi.boot.OSGiWebappConstants;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;

/**
 * When a {@link ContextHandler} service is activated we look into it and if
 * the corresponding webapp is actually not configured then we go and register it.
 * <p>
 * The idea is to always go through this class when we deploy a new webapp
 * on jetty.
 * </p>
 * <p>
 * We are exposing each web-application as an OSGi service.
 * This lets us update the webapps and stop/start them directly at the OSGi layer.
 * It also give us many ways to declare those services: Declarative Services for
 * example.
 * <br/>
 * It is a bit different from the way the HttpService works where we would have
 * a WebappService and we woud register a webapp onto it.
 * <br/>
 * It does not go against RFC-66 nor does it prevent us from supporting the
 * WebappContainer semantics.
 * </p>
 */
public class JettyContextHandlerServiceTracker implements ServiceListener 
{
	
	private final WebappRegistrationHelper _helper;
	
	/** The context-handler to deactivate indexed by context handler */
	private Map<ServiceReference, ContextHandler> _indexByServiceReference = new HashMap<ServiceReference, ContextHandler>();
	
	/** The index is the bundle-symbolic-name/paht/to/context/file when there is such thing */
	private Map<String, ServiceReference> _indexByContextFile = new HashMap<String, ServiceReference>();
	
	/** or null when  */
	private String _osgiContextHomeFolderCanonicalPath;
	/** in charge of detecting changes in the osgi contexts home folder. */
	private Scanner _scanner;

	/**
	 * @param context
	 * @param server
	 */
	public JettyContextHandlerServiceTracker(BundleContext context, Server server)
	throws Exception
	{
		_helper = new WebappRegistrationHelper(server);
		_helper.setup(context, new HashMap<String, String>());
		File contextHome = _helper.getOSGiContextsHome();
		if (contextHome != null)
		{
			_osgiContextHomeFolderCanonicalPath = contextHome.getCanonicalPath();
			_scanner = new Scanner();
			_scanner.setRecursive(true);
			_scanner.setReportExistingFilesOnStartup(false);
			_scanner.addListener(new Scanner.DiscreteListener() {
				public void fileAdded(String filename) throws Exception 
				{
					//adding a file does not create a new app,
					//it just reloads it with the new custom file.
					//well, if the file does not define a context handler,
					//then in fact it does remove it.
					reloadJettyContextHandler(filename);
				}
				public void fileChanged(String filename) throws Exception 
				{
					reloadJettyContextHandler(filename);
				}
				public void fileRemoved(String filename) throws Exception 
				{
					//removing a file does not remove the app:
					//it just goes back to the default embedded in the bundle.
					//well, if there was no default then it does remove it.
					reloadJettyContextHandler(filename);
				}
			});
		}
	}
	
	
	public void stop() 
	{
		if (_scanner != null) 
		{
			_scanner.stop();
		}
		//the class that created the server is also in charge of stopping it.
		//nothing to stop in the WebappRegistrationHelper
		
	}
	
	/**
	 * Receives notification that a service has had a lifecycle change.
	 * 
	 * @param ev The <code>ServiceEvent</code> object.
	 */
	public void serviceChanged(ServiceEvent ev) 
	{
		ServiceReference sr = ev.getServiceReference();
		switch(ev.getType()) 
		{
		case ServiceEvent.MODIFIED:
		case ServiceEvent.UNREGISTERING: {
			ContextHandler ctxtHandler = unregisterInIndex(ev.getServiceReference());
			if (ctxtHandler != null && !ctxtHandler.isStopped()) 
			{
				try 
				{
					_helper.unregister(ctxtHandler);
				}
				catch (Exception e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		if (ev.getType() == ServiceEvent.UNREGISTERING)
		{
			break;
		}
		else 
		{
			//modified, meaning: we reload it. now that we stopped it; we can register it.
		}
		case ServiceEvent.REGISTERED: 
		{
			Bundle contributor = sr.getBundle();
			BundleContext context = FrameworkUtil
				.getBundle(JettyBootstrapActivator.class).getBundleContext();
			ContextHandler contextHandler = (ContextHandler) context.getService(sr);
			if (contextHandler.getServer() != null) 
			{
				//is configured elsewhere.
				return;
			}
			if (contextHandler instanceof WebAppContext) 
			{
				WebAppContext webapp = (WebAppContext)contextHandler;
				String contextPath = (String)sr.getProperty("contextPath");
				if (contextPath == null) 
				{
					contextPath = webapp.getContextPath();
				}
				String war = (String)sr.getProperty("war");
				try 
				{
					ContextHandler handler = _helper.registerWebapplication(contributor, war, contextPath,
                            (String)sr.getProperty(OSGiWebappConstants.SERVICE_PROP_EXTRA_CLASSPATH),
                            (String)sr.getProperty(OSGiWebappConstants.SERVICE_PROP_BUNDLE_INSTALL_LOCATION_OVERRIDE));
					if (handler != null) 
					{
						registerInIndex(handler, sr);
					}
				} 
				catch (Throwable e) 
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			else
			{
				//consider this just an empty skeleton:
				String contextFilePath = (String)sr.getProperty(OSGiWebappConstants.SERVICE_PROP_CONTEXT_FILE_PATH);
				if (contextFilePath == null) 
				{
					throw new IllegalArgumentException("the property contextFilePath is required");
				}
				try 
				{
					ContextHandler handler = _helper.registerContext(contributor, contextFilePath,
                            (String)sr.getProperty(OSGiWebappConstants.SERVICE_PROP_EXTRA_CLASSPATH),
                            (String)sr.getProperty(OSGiWebappConstants.SERVICE_PROP_BUNDLE_INSTALL_LOCATION_OVERRIDE));
					if (handler != null) 
					{
						registerInIndex(handler, sr);
					}
				}
				catch (Throwable e) 
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		break;
		}
	}
	
	private void registerInIndex(ContextHandler handler, ServiceReference sr) 
	{
		_indexByServiceReference.put(sr, handler);
		String key = getSymbolicNameAndContextFileKey(sr);
		if (key != null) 
		{
			_indexByContextFile.put(key, sr);
		}
	}
	
	/**
	 * Returns the ContextHandler to stop.
	 * @param reg
	 * @return the ContextHandler to stop.
	 */
	private ContextHandler unregisterInIndex(ServiceReference sr) 
	{
		ContextHandler handler = _indexByServiceReference.remove(sr);
		String key = getSymbolicNameAndContextFileKey(sr);
		if (key != null)
		{
			_indexByContextFile.remove(key);
		}
		if (handler == null) 
		{
			//a warning?
			return null;
		}
		return handler;
	}
	
	/**
	 * @param sr
	 * @return The key for a context file within the osgi contexts home folder.
	 */
	private String getSymbolicNameAndContextFileKey(ServiceReference sr) 
	{
		String contextFilePath = (String)sr.getProperty(OSGiWebappConstants.SERVICE_PROP_CONTEXT_FILE_PATH);
		if (contextFilePath != null) 
		{
			return sr.getBundle().getSymbolicName() + "/" + contextFilePath;
		}
		return null;
	}
	
	
	/**
	 * Called by the scanner when one of the context files is changed.
	 * @param contextFileFully
	 */
	void reloadJettyContextHandler(String canonicalNameOfFileChanged) 
	{
		String key = getNormalizedRelativePath(canonicalNameOfFileChanged);
		if (key == null) 
		{
			return;
		}
		ServiceReference sr = _indexByContextFile.get(key);
		if (sr == null) 
		{
			//nothing to do?
			return;
		}
		serviceChanged(new ServiceEvent(ServiceEvent.MODIFIED, sr));
	}
	
	/**
	 * @param canFilename
	 * @return
	 */
	private String getNormalizedRelativePath(String canFilename) 
	{
		if (!canFilename.startsWith(_osgiContextHomeFolderCanonicalPath)) 
		{
			//why are we here: this does not look like a child of the osgi contexts home.
			//warning?
			return null;
		}
		return canFilename.substring(
				_osgiContextHomeFolderCanonicalPath.length()).replace('\\', '/');
	}

	
}
