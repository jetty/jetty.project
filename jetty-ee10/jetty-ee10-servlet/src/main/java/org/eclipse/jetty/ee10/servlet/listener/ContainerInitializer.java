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

package org.eclipse.jetty.ee10.servlet.listener;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * Utility Methods for manual execution of {@link jakarta.servlet.ServletContainerInitializer} when
 * using Embedded Jetty.
 */
public final class ContainerInitializer
{
    /**
     * Utility Method to allow for manual execution of {@link jakarta.servlet.ServletContainerInitializer} when
     * using Embedded Jetty.
     *
     * <pre>
     * ServletContextHandler context = new ServletContextHandler();
     * ServletContainerInitializer corpSci = new MyCorporateSCI();
     * context.addEventListener(ContainerInitializer.asContextListener(corpSci));
     * </pre>
     *
     * <p>
     * The {@link ServletContainerInitializer} will have its {@link ServletContainerInitializer#onStartup(Set, ServletContext)}
     * method called with the manually configured list of {@code Set<Class<?>> c} set.
     * In other words, this usage does not perform bytecode or annotation scanning against the classes in
     * your {@code ServletContextHandler} or {@code WebAppContext}.
     * </p>
     *
     * @param sci the {@link ServletContainerInitializer} to call
     * @return the {@link ServletContextListener} wrapping the SCI
     * @see ServletContainerInitializerServletContextListener#addClasses(Class[])
     * @see ServletContainerInitializerServletContextListener#addClasses(String...)
     */
    public static ServletContainerInitializerServletContextListener asContextListener(ServletContainerInitializer sci)
    {
        return new ServletContainerInitializerServletContextListener(sci);
    }

    public static class ServletContainerInitializerServletContextListener implements ServletContextListener
    {
        private final ServletContainerInitializer sci;
        private Set<String> classNames;
        private Set<Class<?>> classes = new HashSet<>();
        private Consumer<ServletContext> afterStartupConsumer;

        public ServletContainerInitializerServletContextListener(ServletContainerInitializer sci)
        {
            this.sci = sci;
        }

        /**
         * Add classes to be passed to the {@link ServletContainerInitializer#onStartup(Set, ServletContext)}  call.
         * <p>
         * Note that these classes will be loaded using the context classloader for the ServletContext
         * initialization phase.
         * </p>
         *
         * @param classNames the class names to load and pass into the {@link ServletContainerInitializer#onStartup(Set, ServletContext)}  call
         * @return this configured {@link ServletContainerInitializerServletContextListener} instance.
         */
        public ServletContainerInitializerServletContextListener addClasses(String... classNames)
        {
            if (this.classNames == null)
            {
                this.classNames = new HashSet<>();
            }
            this.classNames.addAll(Arrays.asList(classNames));
            return this;
        }

        /**
         * Add classes to be passed to the {@link ServletContainerInitializer#onStartup(Set, ServletContext)}  call.
         * <p>
         * Note that these classes will exist on the classloader that was used to call this method.
         * If you want the classes to be loaded using the context classloader for the ServletContext
         * then use the String form of the classes via the {@link #addClasses(String...)} method.
         * </p>
         *
         * @param classes the classes to pass into the {@link ServletContainerInitializer#onStartup(Set, ServletContext)}  call
         * @return this configured {@link ServletContainerInitializerServletContextListener} instance.
         */
        public ServletContainerInitializerServletContextListener addClasses(Class<?>... classes)
        {
            this.classes.addAll(Arrays.asList(classes));
            return this;
        }

        /**
         * Add a optional consumer to execute once the {@link ServletContainerInitializer#onStartup(Set, ServletContext)} has
         * been called successfully.
         * <p>
         * This would be for actions to perform on a ServletContext once this specific SCI has completed
         * its execution.  Actions that would require specific configurations that the SCI provides to be present on the
         * ServletContext to function properly.
         * </p>
         * <p>
         * This consumer is typically used for Embedded Jetty users to configure Jetty for their specific needs.
         * </p>
         *
         * @param consumer the consumer to execute after the SCI has executed
         * @return this configured {@link ServletContainerInitializerServletContextListener} instance.
         */
        public ServletContainerInitializerServletContextListener afterStartup(Consumer<ServletContext> consumer)
        {
            this.afterStartupConsumer = consumer;
            return this;
        }

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            ServletContext servletContext = sce.getServletContext();
            try
            {
                sci.onStartup(getClasses(), servletContext);
                if (afterStartupConsumer != null)
                {
                    afterStartupConsumer.accept(servletContext);
                }
            }
            catch (RuntimeException rte)
            {
                throw rte;
            }
            catch (Throwable cause)
            {
                throw new RuntimeException(cause);
            }
        }

        public Set<Class<?>> getClasses()
        {
            if (classNames != null && !classNames.isEmpty())
            {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();

                for (String className : classNames)
                {
                    try
                    {
                        Class<?> clazz = cl.loadClass(className);
                        classes.add(clazz);
                    }
                    catch (ClassNotFoundException e)
                    {
                        throw new RuntimeException("Unable to find class: " + className, e);
                    }
                }
            }

            return classes;
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            // ignore
        }
    }
}
