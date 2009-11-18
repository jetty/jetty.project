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
package org.eclipse.jetty.osgi.boot.utils.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.jetty.osgi.boot.utils.BundleClassLoaderHelper;
import org.osgi.framework.Bundle;

/**
 * Default implementation of the BundleClassLoaderHelper.
 * Uses introspection to support equinox-3.5 and felix-2.0.0
 */
public class DefaultBundleClassLoaderHelper implements BundleClassLoaderHelper
{

    private static boolean identifiedOsgiImpl = false;
    private static boolean isEquinox = false;
    private static boolean isFelix = false;

    private static void init(Bundle bundle)
    {
        identifiedOsgiImpl = true;
        try
        {
            isEquinox = bundle.getClass().getClassLoader().loadClass("org.eclipse.osgi.framework.internal.core.BundleHost") != null;
        }
        catch (Throwable t)
        {
            isEquinox = false;
        }
        if (!isEquinox)
        {
            try
            {
                isFelix = bundle.getClass().getClassLoader().loadClass("org.apache.felix.framework.BundleImpl") != null;
            }
            catch (Throwable t2)
            {
                isFelix = false;
            }
        }
//        System.err.println("isEquinox=" + isEquinox);
//        System.err.println("isFelix=" + isFelix);
    }

    /**
     * Assuming the bundle is started.
     * 
     * @param bundle
     * @return
     */
    public ClassLoader getBundleClassLoader(Bundle bundle)
    {
        String bundleActivator = (String)bundle.getHeaders().get("Bundle-Activator");
        if (bundleActivator == null)
        {
            bundleActivator = (String)bundle.getHeaders().get("Jetty-ClassInBundle");
        }
        if (bundleActivator != null)
        {
            try
            {
                return bundle.loadClass(bundleActivator).getClassLoader();
            }
            catch (ClassNotFoundException e)
            {
                // should not happen as we are called if the bundle is started anyways.
                e.printStackTrace();
            }
        }
        // resort to introspection
        if (!identifiedOsgiImpl)
        {
            init(bundle);
        }
        if (isEquinox)
        {
            return internalGetEquinoxBundleClassLoader(bundle);
        }
        else if (isFelix)
        {
            return internalGetFelixBundleClassLoader(bundle);
        }
        return null;
    }

    private static Method Equinox_BundleHost_getBundleLoader_method;
    private static Method Equinox_BundleLoader_createClassLoader_method;

    private static ClassLoader internalGetEquinoxBundleClassLoader(Bundle bundle)
    {
        // assume equinox:
        try
        {
            if (Equinox_BundleHost_getBundleLoader_method == null)
            {
                Equinox_BundleHost_getBundleLoader_method = bundle.getClass().getClassLoader().loadClass("org.eclipse.osgi.framework.internal.core.BundleHost")
                        .getDeclaredMethod("getBundleLoader",new Class[] {});
                Equinox_BundleHost_getBundleLoader_method.setAccessible(true);
            }
            Object bundleLoader = Equinox_BundleHost_getBundleLoader_method.invoke(bundle,new Object[] {});
            if (Equinox_BundleLoader_createClassLoader_method == null && bundleLoader != null)
            {
                Equinox_BundleLoader_createClassLoader_method = bundleLoader.getClass().getClassLoader().loadClass(
                        "org.eclipse.osgi.internal.loader.BundleLoader").getDeclaredMethod("createClassLoader",new Class[] {});
                Equinox_BundleLoader_createClassLoader_method.setAccessible(true);
            }
            return (ClassLoader)Equinox_BundleLoader_createClassLoader_method.invoke(bundleLoader,new Object[] {});
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        return null;
    }

    private static Field Felix_BundleImpl_m_modules_field;
    private static Field Felix_ModuleImpl_m_classLoader_field;

    private static ClassLoader internalGetFelixBundleClassLoader(Bundle bundle)
    {
        // assume felix:
        try
        {
            // now get the current module from the bundle.
            // and return the private field m_classLoader of ModuleImpl
            if (Felix_BundleImpl_m_modules_field == null)
            {
                Felix_BundleImpl_m_modules_field = bundle.getClass().getClassLoader()
                    .loadClass("org.apache.felix.framework.BundleImpl").getDeclaredField(
                        "m_modules");
                Felix_BundleImpl_m_modules_field.setAccessible(true);
            }
            Object[] moduleArray = (Object[])Felix_BundleImpl_m_modules_field.get(bundle);
            Object currentModuleImpl = moduleArray[moduleArray.length - 1];
            if (Felix_ModuleImpl_m_classLoader_field == null && currentModuleImpl != null)
            {
                Felix_ModuleImpl_m_classLoader_field = bundle.getClass().getClassLoader().loadClass("org.apache.felix.framework.ModuleImpl").getDeclaredField(
                        "m_classLoader");
                Felix_ModuleImpl_m_classLoader_field.setAccessible(true);
            }
            // first make sure that the classloader is ready:
            // the m_classLoader field must be initialized by the ModuleImpl.getClassLoader() private method.
            ClassLoader cl = (ClassLoader)Felix_ModuleImpl_m_classLoader_field.get(currentModuleImpl);
            if (cl == null)
            {
                // looks like it was not ready:
                // the m_classLoader field must be initialized by the ModuleImpl.getClassLoader() private method.
                // this call will do that.
                bundle.loadClass("java.lang.Object");
                cl = (ClassLoader)Felix_ModuleImpl_m_classLoader_field.get(currentModuleImpl);
                // System.err.println("Got the bundle class loader of felix_: " + cl);
                return cl;
            }
            else
            {
                // System.err.println("Got the bundle class loader of felix: " + cl);
                return cl;
            }
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        return null;
    }
}
