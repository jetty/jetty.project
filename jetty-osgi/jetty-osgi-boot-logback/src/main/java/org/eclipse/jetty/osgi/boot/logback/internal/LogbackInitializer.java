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
package org.eclipse.jetty.osgi.boot.logback.internal;

import java.io.File;
import java.util.Map;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.JoranConfiguratorBase;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;

/**
 * Setup logback eventually located in the config file inside jettyhome/resources
 * All logback related code is done in this separate class for better debug
 * and isolation when it does not load.
 */
public class LogbackInitializer {

    /**
     * @return true when we are currently being run by the pde in development mode.
     */
    private static boolean isPDEDevelopment()
    {
        String eclipseCommands = System.getProperty("eclipse.commands");
        // detect if we are being run from the pde: ie during development.
        return eclipseCommands != null && eclipseCommands.indexOf("-dev") != -1
                && (eclipseCommands.indexOf("-dev\n") != -1
                        || eclipseCommands.indexOf("-dev\r") != -1
                        || eclipseCommands.indexOf("-dev ") != -1);
    }

    
    /**
     * Follow the configuration for logback.
     * unless the system propery was set in which case it
     * was assume it was already setup.
     */
    public static void processFilesInResourcesFolder(File jettyHome, Map<String,File> files)
    {
    	String logbackConf = System.getProperty("logback.configurationFile");
    	if (logbackConf != null)
    	{
    		File confFile = new File(logbackConf);
    		if (confFile.exists())
    		{
    			//assume logback was configured by this one?
    			return;
    		}
    	}
    	
    	File logConf = isPDEDevelopment() ? files.get("logback-dev.xml") : null;
	    if (logConf == null)
	    {
	        logConf = files.get("logback-test.xml");
	    }
	    if (logConf == null)
	    {
	        logConf = files.get("logback.xml");
	    }
	    if (logConf == null)
	    {
	        return;
	    }
	 // assume SLF4J is bound to logback in the current environment
	    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
	    
	    try
	    {
	      JoranConfiguratorBase configurator = new JoranConfigurator();
	      configurator.setContext(lc);
	      lc.reset(); 
	      configurator.doConfigure(logConf.getAbsoluteFile().getAbsolutePath());
	    }
	    catch (JoranException je)
	    {
	       je.printStackTrace();
	    }
	    StatusPrinter.printIfErrorsOccured(lc);
	    
    }

}
