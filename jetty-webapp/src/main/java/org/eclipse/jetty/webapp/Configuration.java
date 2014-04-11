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

package org.eclipse.jetty.webapp;


/* ------------------------------------------------------------------------------- */
/** Base Class for WebApplicationContext Configuration.
 * This class can be extended to customize or extend the configuration
 * of the WebApplicationContext. 
 */
public interface Configuration 
{

    /* ------------------------------------------------------------------------------- */
    /** Set up for configuration.
     * <p>
     * Typically this step discovers configuration resources
     * @param context The context to configure
     * @throws Exception
     */
    public void preConfigure (WebAppContext context) throws Exception;
    
    
    /* ------------------------------------------------------------------------------- */
    /** Configure WebApp.
     * <p>
     * Typically this step applies the discovered configuration resources to
     * either the {@link WebAppContext} or the associated {@link MetaData}.
     * @param context The context to configure
     * @throws Exception
     */
    public void configure (WebAppContext context) throws Exception;
    
    
    /* ------------------------------------------------------------------------------- */
    /** Clear down after configuration.
     * @param context The context to configure
     * @throws Exception
     */
    public void postConfigure (WebAppContext context) throws Exception;
    
    /* ------------------------------------------------------------------------------- */
    /** DeConfigure WebApp.
     * This method is called to undo all configuration done. This is
     * called to allow the context to work correctly over a stop/start cycle
     * @param context The context to configure
     * @throws Exception
     */
    public void deconfigure (WebAppContext context) throws Exception;

    /* ------------------------------------------------------------------------------- */
    /** Destroy WebApp.
     * This method is called to destroy a webappcontext. It is typically called when a context 
     * is removed from a server handler hierarchy by the deployer.
     * @param context The context to configure
     * @throws Exception
     */
    public void destroy (WebAppContext context) throws Exception;
    

    /* ------------------------------------------------------------------------------- */
    /** Clone configuration instance.
     * <p>
     * Configure an instance of a WebAppContext, based on a template WebAppContext that 
     * has previously been configured by this Configuration.
     * @param template The template context
     * @param context The context to configure
     * @throws Exception
     */
    public void cloneConfigure (WebAppContext template, WebAppContext context) throws Exception;
}
