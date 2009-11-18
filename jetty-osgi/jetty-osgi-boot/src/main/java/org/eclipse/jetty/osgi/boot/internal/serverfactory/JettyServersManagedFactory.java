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
// Contributors:
//    Hugues Malphettes - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.boot.internal.serverfactory;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.server.Server;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;

/**
 * This is a work in progress.
 * <br/>
 * In particular there is a lot of work required during the update of the configuration
 * of a server. It might not be practical to in fact support that and re-deploy 
 * the webapps in the same state than before the server was stopped.
 * <p>
 * jetty servers are managed as OSGi services registered here.
 * try to find out if a configuration will fail (ports already opened etc).
 * </p>
 * <p>
 * Try to enable the creation and configuration of jetty servers in all the usual standard ways.
 * The configuration of the server is defined by the properties passed to the service:
 * <ol>
 * <li>First look for jettyfactory. If the value is a jetty server, use that server</li>
 * <li>Then look for jettyhome key. The value should be a java.io.File or a String that is a path to the folder
 * It is required that a etc/jetty.xml file will be loated from that folder.</li>
 * <li>Then look for a jettyxml key. The value should be a java.io.File or an InputStream
 * that contains a jetty configuration file.</li>
 * <li>TODO: More ways to configure a jetty server?
 * (other IOCs like spring, equinox properties...)</li>
 * <li>Throw an exception if none of the relevant parameters are found</li>
 * </ol>
 * </p>
 * 
 * @author hmalphettes
 */
public class JettyServersManagedFactory implements ManagedServiceFactory
{
	
	/** key to configure the server according to a jetty home folder.
	 * the value is the corresponding java.io.File */
	public static final String JETTY_HOME = "jettyhome";
	/** key to configure the server according to a jetty.xml file */
	public static final String JETTY_CONFIG_XML = "jettyxml";
	
	/** invoke jetty-factory class. the value of this property is the
	 * instance of that class to call back. */ 
	public static final String JETTY_FACTORY = "jettyfactory";
	
	/** default property in jetty.xml that is used as the value of the http port. */
	public static final String JETTY_HTTP_PORT = "jetty.http.port";
	/** default property in jetty.xml that is used as the value of the https port. */
	public static final String JETTY_HTTPS_PORT = "jetty.http.port";
	
	private Map<String, Server> _servers = new HashMap<String, Server>();
	
	/**
	 * Return a descriptive name of this factory.
	 * 
	 * @return the name for the factory, which might be localized
	 */
	public String getName()
	{
		return getClass().getName();
	}
	

	public void updated(String pid, Dictionary properties)
			throws ConfigurationException
	{
		Server server = _servers.get(pid);
		deleted(pid);
		//do we need to collect the currently deployed http services and webapps
		//to be able to re-deploy them later?
		//probably not. simply restart and see the various service trackers
		//do everything that is needed.
		
		
		
	}
	
	
	public synchronized void deleted(String pid)
	{
        Server server = (Server)_servers.remove(pid);
        if (server != null)
        {
            try
            {
                server.stop();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
	}
	
}
