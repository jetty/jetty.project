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

package org.eclipse.jetty.osgi.boot.utils.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.jetty.osgi.boot.utils.BundleClassLoaderHelper;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.osgi.framework.Bundle;

/**
 * DefaultBundleClassLoaderHelper
 * 
 * 
 * Default implementation of the BundleClassLoaderHelper. Uses introspection to
 * support equinox-3.5 and felix-2.0.0
 */
public class DefaultBundleClassLoaderHelper implements BundleClassLoaderHelper
{
    private static final Logger LOG = Log.getLogger(BundleClassLoaderHelper.class);
    
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
    }

    /**
     * Assuming the bundle is started.
     * 
     * @param bundle
     * @return classloader object
     */
    public ClassLoader getBundleClassLoader(Bundle bundle)
    {
        String bundleActivator = (String) bundle.getHeaders().get("Bundle-Activator");
       
        if (bundleActivator == null)
        {
            bundleActivator = (String) bundle.getHeaders().get("Jetty-ClassInBundle");
        }
        if (bundleActivator != null)
        {
            try
            {
                return bundle.loadClass(bundleActivator).getClassLoader();
            }
            catch (ClassNotFoundException e)
            {
                LOG.warn(e);
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
        
        LOG.warn("No classloader found for bundle "+bundle.getSymbolicName());
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
                Equinox_BundleHost_getBundleLoader_method = 
                    bundle.getClass().getClassLoader().loadClass("org.eclipse.osgi.framework.internal.core.BundleHost").getDeclaredMethod("getBundleLoader", new Class[] {});
                Equinox_BundleHost_getBundleLoader_method.setAccessible(true);
            }
            Object bundleLoader = Equinox_BundleHost_getBundleLoader_method.invoke(bundle, new Object[] {});
            if (Equinox_BundleLoader_createClassLoader_method == null && bundleLoader != null)
            {
                Equinox_BundleLoader_createClassLoader_method = 
                    bundleLoader.getClass().getClassLoader().loadClass("org.eclipse.osgi.internal.loader.BundleLoader").getDeclaredMethod("createClassLoader", new Class[] {});
                Equinox_BundleLoader_createClassLoader_method.setAccessible(true);
            }
            return (ClassLoader) Equinox_BundleLoader_createClassLoader_method.invoke(bundleLoader, new Object[] {});
        }
        catch (Throwable t)
        {
            LOG.warn(t);
        }
        LOG.warn("No classloader for equinox platform for bundle "+bundle.getSymbolicName());
        return null;
    }

    private static Field Felix_BundleImpl_m_modules_field;

    private static Field Felix_ModuleImpl_m_classLoader_field;
    
    private static Method Felix_adapt_method;
    
    private static Method Felix_bundle_wiring_getClassLoader_method;
    
    private static Class Felix_bundleWiringClazz;

    private static Boolean isFelix403 = null;

    private static ClassLoader internalGetFelixBundleClassLoader(Bundle bundle)
    {
        //firstly, try to find classes matching a newer version of felix
        initFelix403(bundle);
      
        if (isFelix403.booleanValue())
        {
            try
            {
                Object wiring = Felix_adapt_method.invoke(bundle, new Object[] {Felix_bundleWiringClazz});
                ClassLoader cl = (ClassLoader)Felix_bundle_wiring_getClassLoader_method.invoke(wiring);
                return cl;
            }
            catch (Exception e)
            {
                LOG.warn(e);
                return null;
            }
        }


        // Fallback to trying earlier versions of felix.     
        if (Felix_BundleImpl_m_modules_field == null)
        {
            try
            {
                Class bundleImplClazz = bundle.getClass().getClassLoader().loadClass("org.apache.felix.framework.BundleImpl");  
                Felix_BundleImpl_m_modules_field = bundleImplClazz.getDeclaredField("m_modules");
                Felix_BundleImpl_m_modules_field.setAccessible(true);
            }
            catch (ClassNotFoundException e)
            {
                LOG.warn(e);
            }
            catch (NoSuchFieldException e)
            {
                LOG.warn(e);
            }
        }

        // Figure out which version of the modules is exported
        Object currentModuleImpl;
        try
        {
            Object[] moduleArray = (Object[]) Felix_BundleImpl_m_modules_field.get(bundle);
            currentModuleImpl = moduleArray[moduleArray.length - 1];
        }
        catch (Throwable t2)
        {
            try
            {
                List<Object> moduleArray = (List<Object>) Felix_BundleImpl_m_modules_field.get(bundle);
                currentModuleImpl = moduleArray.get(moduleArray.size() - 1);
            }
            catch (Exception e)
            {
                LOG.warn(e);
                return null;
            }
        }

        if (Felix_ModuleImpl_m_classLoader_field == null && currentModuleImpl != null)
        {
            try
            {
                Felix_ModuleImpl_m_classLoader_field = bundle.getClass().getClassLoader().loadClass("org.apache.felix.framework.ModuleImpl").getDeclaredField("m_classLoader");
                Felix_ModuleImpl_m_classLoader_field.setAccessible(true);
            }
            catch (ClassNotFoundException e)
            {
                LOG.warn(e);
                return null;
            }   
            catch (NoSuchFieldException e)
            {
                LOG.warn(e);
                return null;
            }
        }
        // first make sure that the classloader is ready:
        // the m_classLoader field must be initialized by the
        // ModuleImpl.getClassLoader() private method.
        ClassLoader cl = null;
        try
        {
            cl = (ClassLoader) Felix_ModuleImpl_m_classLoader_field.get(currentModuleImpl);
            if (cl != null)
                return cl;
        }
        catch (Exception e)
        {
            LOG.warn(e);
            return null;
        }
        
        // looks like it was not ready:
        // the m_classLoader field must be initialized by the
        // ModuleImpl.getClassLoader() private method.
        // this call will do that.
        try
        {
            bundle.loadClass("java.lang.Object");
            cl = (ClassLoader) Felix_ModuleImpl_m_classLoader_field.get(currentModuleImpl);
            return cl;
        }
        catch (Exception e)
        {
            LOG.warn(e);
            return null;
        }
    }


    private static void initFelix403 (Bundle bundle)
    {
        //see if the version of Felix is a new one
        if (isFelix403 == null)
        {
            try
            {
                Class bundleImplClazz = bundle.getClass().getClassLoader().loadClass("org.apache.felix.framework.BundleImpl");
                Felix_bundleWiringClazz = bundle.getClass().getClassLoader().loadClass("org.osgi.framework.wiring.BundleWiring");
                Felix_adapt_method = bundleImplClazz.getDeclaredMethod("adapt", new Class[] {Class.class});
                Felix_adapt_method.setAccessible(true);
                Felix_bundle_wiring_getClassLoader_method = Felix_bundleWiringClazz.getDeclaredMethod("getClassLoader");
                Felix_bundle_wiring_getClassLoader_method.setAccessible(true);
                isFelix403 = Boolean.TRUE;
            }
            catch (ClassNotFoundException e)
            {
                LOG.warn("Felix 4.x classes not found in environment");
                isFelix403 = Boolean.FALSE;
            }
            catch (NoSuchMethodException e)
            { 
                LOG.warn("Felix 4.x classes not found in environment");
                isFelix403 = Boolean.FALSE;
            }           
        }
    }
}
