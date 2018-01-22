//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.util.Collection;
import java.util.Collections;
import java.util.ListIterator;
import java.util.ServiceLoader;

import org.eclipse.jetty.util.TopologicalSort;
import org.eclipse.jetty.util.annotation.Name;

/* ------------------------------------------------------------------------------- */
/** A pluggable Configuration for {@link WebAppContext}s.
 * <p>
 * A {@link WebAppContext} is configured by the application of one or more {@link Configuration}
 * instances.  Typically each implemented Configuration is responsible for an aspect of the 
 * servlet specification (eg {@link WebXmlConfiguration}, {@link FragmentConfiguration}, etc.)
 * or feature (eg {@link WebSocketConfiguration}, {@link JmxConfiguration} etc.)
 * </p>
 * <p>Configuration instances are discovered by the {@link Configurations} class using either the 
 * {@link ServiceLoader} mechanism or by an explicit call to {@link Configurations#setKnown(String...)}.
 * By default, all Configurations that do not implement the {@link #isDisabledByDefault()} interface
 * are applied to all {@link WebAppContext}s within the JVM.  However a Server wide default {@link Configurations}
 * collection may also be defined with {@link Configurations#setServerDefault(org.eclipse.jetty.server.Server)}.
 * Furthermore, each individual Context may have its Configurations list explicitly set and/or amended with
 * {@link WebAppContext#setConfigurations(Configuration[])}, {@link WebAppContext#addConfiguration(Configuration...)}
 * or {@link WebAppContext#getWebAppConfigurations()}.
 * </p>
 * <p>Since Jetty-9.4, Configurations are self ordering using the {@link #getDependencies()} and
 * {@link #getDependents()} methods for a {@link TopologicalSort} initiated by {@link Configurations#sort()}
 * when a {@link WebAppContext} is started.  This means that feature configurations 
 * (eg {@link JndiConfiguration}, {@link JaasConfiguration}} etc.) can be added or removed without concern 
 * for ordering.
 * </p>
 * <p>Also since Jetty-9.4, Configurations are responsible for providing {@link #getServerClasses()} and
 * {@link #getSystemClasses()} to configure the {@link WebAppClassLoader} for each context.
 * </p> 
 *  
 */
public interface Configuration 
{
    public final static String ATTR="org.eclipse.jetty.webapp.configuration";
    
    /** Get a class that this class replaces/extends.
     * If this is added to {@link Configurations} collection that already contains a 
     * configuration of the replaced class or that reports to replace the same class, then
     * it is replaced with this instance. 
     * @return The class this Configuration replaces/extends or null if it replaces no other configuration
     */
    public default Class<? extends Configuration> replaces() { return null; } 

    /** Get known Configuration Dependencies.
     * @return The names of Configurations that {@link TopologicalSort} must order 
     * before this configuration.
     */
    public default Collection<String> getDependencies() { return Collections.emptyList(); }

    /** Get known Configuration Dependents.
     * @return The names of Configurations that {@link TopologicalSort} must order 
     * after this configuration.
     */
    public default Collection<String> getDependents(){ return Collections.emptyList(); }

    /** Get the system classes associated with this Configuration.
     * @return ClasspathPattern of system classes.
     */
    public default ClasspathPattern getSystemClasses() { return new ClasspathPattern();  }

    /** Get the system classes associated with this Configuration.
     * @return ClasspathPattern of server classes.
     */
    public default ClasspathPattern getServerClasses() { return new ClasspathPattern();  }
    
    /** Set up for configuration.
     * <p>
     * Typically this step discovers configuration resources.
     * Calls to preConfigure may alter the Configurations configured on the
     * WebAppContext, so long as configurations prior to this configuration
     * are not altered.
     * @param context The context to configure
     * @throws Exception if unable to pre configure
     */
    public void preConfigure (WebAppContext context) throws Exception;
    
    /** Configure WebApp.
     * <p>
     * Typically this step applies the discovered configuration resources to
     * either the {@link WebAppContext} or the associated {@link MetaData}.
     * @param context The context to configure
     * @throws Exception if unable to configure
     */
    public void configure (WebAppContext context) throws Exception;
    
    /** Clear down after configuration.
     * @param context The context to configure
     * @throws Exception if unable to post configure
     */
    public void postConfigure (WebAppContext context) throws Exception;
    
    /** DeConfigure WebApp.
     * This method is called to undo all configuration done. This is
     * called to allow the context to work correctly over a stop/start cycle
     * @param context The context to configure
     * @throws Exception if unable to deconfigure
     */
    public void deconfigure (WebAppContext context) throws Exception;

    /** Destroy WebApp.
     * This method is called to destroy a webappcontext. It is typically called when a context 
     * is removed from a server handler hierarchy by the deployer.
     * @param context The context to configure
     * @throws Exception if unable to destroy
     */
    public void destroy (WebAppContext context) throws Exception;

    /**
     * @return true if configuration is disabled by default
     */
    public boolean isDisabledByDefault();

    /**
     * @return true if configuration should be aborted
     */
    public boolean abort(WebAppContext context);

    /**
     * @deprecated Use {@link Configurations}
     */
    @Deprecated
    public class ClassList extends Configurations
    {
        @Deprecated
        public void addAfter(@Name("afterClass") String afterClass,@Name("configClass")String... configClass)
        {
            if (configClass!=null && afterClass!=null)
            {
                ListIterator<Configuration> iter = _configurations.listIterator();
                while (iter.hasNext())
                {
                    Configuration c=iter.next();
                    
                    if (afterClass.equals(c.getClass().getName()) || afterClass.equals(c.replaces().getName()))
                    {
                        for (String cc: configClass)
                            iter.add(newConfiguration(cc));
                        return;
                    }
                }
            }
            throw new IllegalArgumentException("afterClass '"+afterClass+"' not found in "+this);
        }

        @Deprecated
        public void addBefore(@Name("beforeClass") String beforeClass,@Name("configClass")String... configClass)
        {
            if (configClass!=null && beforeClass!=null)
            {
                ListIterator<Configuration> iter = _configurations.listIterator();
                while (iter.hasNext())
                {
                    Configuration c=iter.next();
                    
                    if (beforeClass.equals(c.getClass().getName()) || beforeClass.equals(c.replaces().getName()))
                    {
                        iter.previous();
                        for (String cc: configClass)
                            iter.add(newConfiguration(cc));
                        return;
                    }
                }
            }
            
            throw new IllegalArgumentException("beforeClass '"+beforeClass+"' not found in "+this);
        }
    }
}
