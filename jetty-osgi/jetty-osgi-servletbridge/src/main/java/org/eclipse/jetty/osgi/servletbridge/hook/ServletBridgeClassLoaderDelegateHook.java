// ========================================================================
// Copyright (c) 2010-2011 Mort Bay Consulting Pty. Ltd.
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
package org.eclipse.jetty.osgi.servletbridge.hook;

import java.io.FileNotFoundException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.runtime.adaptor.EclipseStarter;
import org.eclipse.osgi.baseadaptor.HookConfigurator;
import org.eclipse.osgi.baseadaptor.HookRegistry;
import org.eclipse.osgi.framework.adaptor.BundleClassLoader;
import org.eclipse.osgi.framework.adaptor.BundleData;
import org.eclipse.osgi.framework.adaptor.ClassLoaderDelegateHook;
import org.eclipse.osgi.internal.loader.BundleLoader;

/**
 * With some complex osgi products, experience shows that using a system bundle
 * extension to pass certain packages from the bootstrapping server to equinox
 * fails. The bundles keep loading javax.servlet.http from the javax.servlet
 * bundle. This class is in fact copied into the servletbridge.extensionbundle;
 * it is not loaded by the webapp.
 */
public class ServletBridgeClassLoaderDelegateHook implements ClassLoaderDelegateHook, HookConfigurator
{

    private static Set<String> packagesInBootstrapClassLoader = new HashSet<String>();
    static
    {
        packagesInBootstrapClassLoader.add("javax.servlet");
        packagesInBootstrapClassLoader.add("javax.servlet.http");
    }

    public void addHooks(HookRegistry hookRegistry)
    {
        hookRegistry.addClassLoaderDelegateHook(this);
    }

    public Class preFindClass(String name, BundleClassLoader classLoader, BundleData data) throws ClassNotFoundException
    {
        String pkgName = BundleLoader.getPackageName(name);
        if (packagesInBootstrapClassLoader.contains(pkgName)) { return EclipseStarter.class.getClassLoader().loadClass(name); }
        return null;
    }

    public Class postFindClass(String name, BundleClassLoader classLoader, BundleData data) throws ClassNotFoundException
    {
        return null;
    }

    public URL preFindResource(String name, BundleClassLoader classLoader, BundleData data) throws FileNotFoundException
    {
        return null;
    }

    public URL postFindResource(String name, BundleClassLoader classLoader, BundleData data) throws FileNotFoundException
    {
        return null;
    }

    public Enumeration preFindResources(String name, BundleClassLoader classLoader, BundleData data) throws FileNotFoundException
    {
        return null;
    }

    public Enumeration postFindResources(String name, BundleClassLoader classLoader, BundleData data) throws FileNotFoundException
    {
        return null;
    }

    public String preFindLibrary(String name, BundleClassLoader classLoader, BundleData data) throws FileNotFoundException
    {
        return null;
    }

    public String postFindLibrary(String name, BundleClassLoader classLoader, BundleData data)
    {
        return null;
    }

}
