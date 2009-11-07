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
package org.eclipse.jetty.osgi.boot.utils;

import java.net.URL;


/**
 * Fix various shortcomings with the way jasper parses the tld files.
 */
public interface WebappRegistrationCustomizer
{
    /** we could do something a lot more pluggable with
     * a custom header in the manifest or some customer declarative services
     * let's keep it simple for now. hopefully the rest of the world
     * won't need to customize this. */
    public static final String CLASS_NAME = "org.eclipse.jetty.osgi.boot.jasper.WebappRegistrationCustomizerImpl";
	
    /**
     * TODO: right now only the jetty-jsp bundle is scanned for common taglibs. Should support a way to plug more bundles that contain taglibs.
     * 
     * The jasper TldScanner expects a URLClassloader to parse a jar for the /META-INF/*.tld it may contain. We place the bundles that we know contain such
     * tag-libraries. Please note that it will work if and only if the bundle is a jar (!) Currently we just hardcode the bundle that contains the jstl
     * implemenation.
     * 
     * A workaround when the tld cannot be parsed with this method is to copy and paste it inside the WEB-INF of the webapplication where it is used.
     * 
     * Support only 2 types of packaging for the bundle: - the bundle is a jar (recommended for runtime.) - the bundle is a folder and contain jars in the root
     * and/or in the lib folder (nice for PDE developement situations) Unsupported: the bundle is a jar that embeds more jars.
     * 
     * @return
     * @throws Exception
     */
    URL[] getJarsWithTlds(BundleFileLocatorHelper fileLocator) throws Exception;
	
}
