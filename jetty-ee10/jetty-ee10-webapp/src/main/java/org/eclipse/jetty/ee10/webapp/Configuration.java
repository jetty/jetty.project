//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.webapp;

import java.util.Collection;
import java.util.Collections;
import java.util.ServiceLoader;

import org.eclipse.jetty.util.TopologicalSort;

/**
 * A pluggable Configuration for {@link WebAppContext}s.
 * <p>
 * A {@link WebAppContext} is configured by the application of one or more {@link Configuration}
 * instances.  Typically each implemented Configuration is responsible for an aspect of the
 * servlet specification (eg {@link WebXmlConfiguration}, {@link FragmentConfiguration}, etc.)
 * or feature (eg {@code JakartaWebSocketConfiguration}, {@code JmxConfiguration} etc.)
 * </p>
 * <p>Configuration instances are discovered by the {@link Configurations} class using either the
 * {@link ServiceLoader} mechanism or by an explicit call to {@link Configurations#setKnown(String...)}.
 * By default, all Configurations that do not return false from {@link #isEnabledByDefault()}
 * are applied to all {@link WebAppContext}s within the JVM.  However a Server wide default {@link Configurations}
 * collection may also be defined with {@link Configurations#setServerDefault(org.eclipse.jetty.server.Server)}.
 * Furthermore, each individual Context may have its Configurations list explicitly set and/or amended with
 * {@link WebAppContext#setConfigurations(Configuration[])}, {@link WebAppContext#addConfiguration(Configuration...)}
 * or {@link WebAppContext#getConfigurations()}.
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
 */
public interface Configuration
{
    String ATTR = "org.eclipse.jetty.webapp.configuration";

    /**
     * @return True if the feature this configuration represents is available and has all its dependencies.
     */
    default boolean isAvailable()
    {
        return true;
    }

    /**
     * Get a class that this class replaces/extends.
     * If this is added to {@link Configurations} collection that already contains a
     * configuration of the replaced class or that reports to replace the same class, then
     * it is replaced with this instance.
     *
     * @return The class this Configuration replaces/extends or null if it replaces no other configuration
     */
    default Class<? extends Configuration> replaces()
    {
        return null;
    }

    /**
     * Get known Configuration Dependencies.
     *
     * @return The names of Configurations that {@link TopologicalSort} must order
     * before this configuration.
     */
    default Collection<String> getDependencies()
    {
        return Collections.emptyList();
    }

    /**
     * Get known Configuration Dependents.
     *
     * @return The names of Configurations that {@link TopologicalSort} must order
     * after this configuration.
     */
    default Collection<String> getDependents()
    {
        return Collections.emptyList();
    }

    /**
     * Get the system classes associated with this Configuration.
     *
     * @return ClassMatcher of system classes.
     */
    default ClassMatcher getSystemClasses()
    {
        return new ClassMatcher();
    }

    /**
     * Get the system classes associated with this Configuration.
     *
     * @return ClassMatcher of server classes.
     */
    default ClassMatcher getServerClasses()
    {
        return new ClassMatcher();
    }

    /**
     * Set up for configuration.
     * <p>
     * Typically this step discovers configuration resources.
     * Calls to preConfigure may alter the Configurations configured on the
     * WebAppContext, so long as configurations prior to this configuration
     * are not altered.
     *
     * @param context The context to configure
     * @throws Exception if unable to pre configure
     */
    void preConfigure(WebAppContext context) throws Exception;

    /**
     * Configure WebApp.
     * <p>
     * Typically this step applies the discovered configuration resources to
     * either the {@link WebAppContext} or the associated {@link MetaData}.
     *
     * @param context The context to configure
     * @throws Exception if unable to configure
     */
    void configure(WebAppContext context) throws Exception;

    /**
     * Clear down after configuration.
     *
     * @param context The context to configure
     * @throws Exception if unable to post configure
     */
    void postConfigure(WebAppContext context) throws Exception;

    /**
     * DeConfigure WebApp.
     * This method is called to undo all configuration done. This is
     * called to allow the context to work correctly over a stop/start cycle
     *
     * @param context The context to configure
     * @throws Exception if unable to deconfigure
     */
    void deconfigure(WebAppContext context) throws Exception;

    /**
     * Destroy WebApp.
     * This method is called to destroy a webappcontext. It is typically called when a context
     * is removed from a server handler hierarchy by the deployer.
     *
     * @param context The context to configure
     * @throws Exception if unable to destroy
     */
    void destroy(WebAppContext context) throws Exception;

    /**
     * @return true if configuration is enabled by default
     */
    boolean isEnabledByDefault();

    /**
     * @return true if configuration should be aborted
     */
    boolean abort(WebAppContext context);

    /**
     * Experimental Wrapper mechanism for WebApp Configuration components.
     * <p>
     * Beans in WebAppContext that implement this interface
     * will be called to optionally wrap any newly created {@link Configuration}
     * objects before they are used for the first time.
     * </p>
     */
    interface WrapperFunction
    {
        Configuration wrapConfiguration(Configuration configuration);
    }

    class Wrapper implements Configuration
    {
        private Configuration delegate;

        public Wrapper(Configuration delegate)
        {
            this.delegate = delegate;
        }

        public Configuration getWrapped()
        {
            return delegate;
        }

        @Override
        public void preConfigure(WebAppContext context) throws Exception
        {
            delegate.preConfigure(context);
        }

        @Override
        public void configure(WebAppContext context) throws Exception
        {
            delegate.configure(context);
        }

        @Override
        public void postConfigure(WebAppContext context) throws Exception
        {
            delegate.postConfigure(context);
        }

        @Override
        public void deconfigure(WebAppContext context) throws Exception
        {
            delegate.deconfigure(context);
        }

        @Override
        public void destroy(WebAppContext context) throws Exception
        {
            delegate.destroy(context);
        }

        @Override
        public boolean isEnabledByDefault()
        {
            return delegate.isEnabledByDefault();
        }

        @Override
        public boolean abort(WebAppContext context)
        {
            return delegate.abort(context);
        }
    }
}
