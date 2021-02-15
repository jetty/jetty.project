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

package org.eclipse.jetty.util;

import java.net.URL;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * ClassLoader Helper.
 * This helper class allows classes to be loaded either from the
 * Thread's ContextClassLoader, the classloader of the derived class
 * or the system ClassLoader.
 *
 * <B>Usage:</B><PRE>
 * public class MyClass {
 * void myMethod() {
 * ...
 * Class c=Loader.loadClass(this.getClass(),classname);
 * ...
 * }
 * </PRE>
 */
public class Loader
{

    public static URL getResource(String name)
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? ClassLoader.getSystemResource(name) : loader.getResource(name);
    }

    /**
     * Load a class.
     * <p>Load a class either from the thread context classloader or if none, the system
     * loader</p>
     *
     * @param name the name of the new class to load
     * @return Class
     * @throws ClassNotFoundException if not able to find the class
     */
    @SuppressWarnings("rawtypes")
    public static Class loadClass(String name)
        throws ClassNotFoundException
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return (loader == null) ? Class.forName(name) : loader.loadClass(name);
    }

    /**
     * Load a class.
     * Load a class from the same classloader as the passed  <code>loadClass</code>, or if none
     * then use {@link #loadClass(String)}
     *
     * @param loaderClass a similar class, belong in the same classloader of the desired class to load
     * @param name the name of the new class to load
     * @return Class
     * @throws ClassNotFoundException if not able to find the class
     */
    @SuppressWarnings("rawtypes")
    public static Class loadClass(Class loaderClass, String name)
        throws ClassNotFoundException
    {
        if (loaderClass != null && loaderClass.getClassLoader() != null)
            return loaderClass.getClassLoader().loadClass(name);
        return loadClass(name);
    }

    public static ResourceBundle getResourceBundle(String name, boolean checkParents, Locale locale)
        throws MissingResourceException
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? ResourceBundle.getBundle(name, locale) : ResourceBundle.getBundle(name, locale, loader);
    }
}
