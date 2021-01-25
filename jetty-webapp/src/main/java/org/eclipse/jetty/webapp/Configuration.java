//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.annotation.Name;

/**
 * Base Class for WebApplicationContext Configuration.
 * This class can be extended to customize or extend the configuration
 * of the WebApplicationContext.
 */
public interface Configuration
{
    String ATTR = "org.eclipse.jetty.webapp.configuration";

    /**
     * Set up for configuration.
     * <p>
     * Typically this step discovers configuration resources
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
     * Clone configuration instance.
     * <p>
     * Configure an instance of a WebAppContext, based on a template WebAppContext that
     * has previously been configured by this Configuration.
     *
     * @param template The template context
     * @param context The context to configure
     * @throws Exception if unable to clone
     */
    void cloneConfigure(WebAppContext template, WebAppContext context) throws Exception;

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
        public void cloneConfigure(WebAppContext template, WebAppContext context) throws Exception
        {
            delegate.cloneConfigure(template, context);
        }
    }

    class ClassList extends ArrayList<String>
    {

        /**
         * Get/Set/Create the server default Configuration ClassList.
         * <p>Get the class list from: a Server bean; or the attribute (which can
         * either be a ClassList instance or an String[] of class names); or a new instance
         * with default configuration classes.</p>
         * <p>This method also adds the obtained ClassList instance as a dependent bean
         * on the server and clears the attribute</p>
         *
         * @param server The server the default is for
         * @return the server default ClassList instance of the configuration classes for this server. Changes to this list will change the server default instance.
         */
        public static ClassList setServerDefault(Server server)
        {
            ClassList cl = server.getBean(ClassList.class);
            if (cl != null)
                return cl;
            cl = serverDefault(server);
            server.addBean(cl);
            server.setAttribute(ATTR, null);
            return cl;
        }

        /**
         * Get/Create the server default Configuration ClassList.
         * <p>Get the class list from: a Server bean; or the attribute (which can
         * either be a ClassList instance or an String[] of class names); or a new instance
         * with default configuration classes.
         *
         * @param server The server the default is for
         * @return A copy of the server default ClassList instance of the configuration classes for this server. Changes to the returned list will not change the server default.
         */
        public static ClassList serverDefault(Server server)
        {
            ClassList cl = null;
            if (server != null)
            {
                cl = server.getBean(ClassList.class);
                if (cl != null)
                    return new ClassList(cl);
                Object attr = server.getAttribute(ATTR);
                if (attr instanceof ClassList)
                    return new ClassList((ClassList)attr);
                if (attr instanceof String[])
                    return new ClassList((String[])attr);
            }
            return new ClassList();
        }

        public ClassList()
        {
            this(WebAppContext.DEFAULT_CONFIGURATION_CLASSES);
        }

        public ClassList(String[] classes)
        {
            addAll(Arrays.asList(classes));
        }

        public ClassList(List<String> classes)
        {
            addAll(classes);
        }

        public void addAfter(@Name("afterClass") String afterClass, @Name("configClass") String... configClass)
        {
            if (configClass != null && afterClass != null)
            {
                ListIterator<String> iter = listIterator();
                while (iter.hasNext())
                {
                    String cc = iter.next();
                    if (afterClass.equals(cc))
                    {
                        for (int i = 0; i < configClass.length; i++)
                        {
                            iter.add(configClass[i]);
                        }
                        return;
                    }
                }
            }
            throw new IllegalArgumentException("afterClass '" + afterClass + "' not found in " + this);
        }

        public void addBefore(@Name("beforeClass") String beforeClass, @Name("configClass") String... configClass)
        {
            if (configClass != null && beforeClass != null)
            {
                ListIterator<String> iter = listIterator();
                while (iter.hasNext())
                {
                    String cc = iter.next();
                    if (beforeClass.equals(cc))
                    {
                        iter.previous();
                        for (int i = 0; i < configClass.length; i++)
                        {
                            iter.add(configClass[i]);
                        }
                        return;
                    }
                }
            }
            throw new IllegalArgumentException("beforeClass '" + beforeClass + "' not found in " + this);
        }

        public void replace(@Name("replaceClass") String replaceClass, @Name("configClass") String configClass)
        {
            if (replaceClass != null && configClass != null)
            {
                ListIterator<String> iter = listIterator();
                while (iter.hasNext())
                {

                    String cc = iter.next();
                    if (replaceClass.equals(cc))
                    {
                        iter.set(configClass);
                        return;
                    }
                }
            }
            throw new IllegalArgumentException("replaceClass '" + replaceClass + "' not found in " + this);
        }
    }
}
