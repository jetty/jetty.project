// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.webapp;


/* ------------------------------------------------------------------------------- */
/** Base Class for WebApplicationContext Configuration.
 * This class can be extended to customize or extend the configuration
 * of the WebApplicationContext.  If WebApplicationContext.setConfiguration is not
 * called, then an XMLConfiguration instance is created.
 * 
 * 
 */
public interface Configuration 
{
    /* ------------------------------------------------------------------------------- */
    /** Set up a context on which to perform the configuration.
     * @param context
     */
    public void setWebAppContext (WebAppContext context);
    
    /* ------------------------------------------------------------------------------- */
    /** Get the context on which the configuration is performed.
     */
    public WebAppContext getWebAppContext();
    
    /* ------------------------------------------------------------------------------- */
    /** Configure ClassPath.
     * This method is called to configure the context ClassLoader.  It is called just
     * after a new WebAppClassLoader is constructed and before it has been used.
     * Class paths may be added, options changed or the loader totally replaced. 
     * @throws Exception
     */
    public void configureClassLoader()
    throws Exception;
    
    /* ------------------------------------------------------------------------------- */
    /** Configure Defaults.
     * This method is called to intialize the context to the containers default configuration.
     * Typically this would mean application of the webdefault.xml file. 
     * @throws Exception
     */
    public  void configureDefaults()
    throws Exception;
    
    
    /* ------------------------------------------------------------------------------- */
    /** Configure WebApp.
     * This method is called to apply the standard and vendor deployment descriptors.
     * Typically this is web.xml and jetty-web.xml.  
     * @throws Exception
     */
    public  void configureWebApp()
    throws Exception;

    /* ------------------------------------------------------------------------------- */
    /** DeConfigure WebApp.
     * This method is called to undo all configuration done to this webapphandler. This is
     * called to allow the context to work correctly over a stop/start cycle
     * @throws Exception
     */
    public  void deconfigureWebApp()
    throws Exception;
    
    
}
