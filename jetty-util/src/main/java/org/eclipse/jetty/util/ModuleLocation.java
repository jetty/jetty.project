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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Optional;
import java.util.function.Function;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import static java.lang.invoke.MethodType.methodType;

/**
 * Equivalent of ...
 *
 * <pre>
 * Module module = clazz.getModule();
 * if (module != null)
 * {
 *     Configuration configuration = module.getLayer().configuration();
 *     Optional<ResolvedModule> resolvedModule = configuration.findModule(module.getName());
 *     if (resolvedModule.isPresent())
 *     {
 *         ModuleReference moduleReference = resolvedModule.get().reference();
 *         Optional<URI> location = moduleReference.location();
 *         if (location.isPresent())
 *         {
 *             return location.get();
 *         }
 *     }
 * }
 * return null;
 * </pre>
 *
 * In Jetty 10, this entire class can be moved to direct calls to java.lang.Module in TypeUtil.getModuleLocation()
 */
class ModuleLocation implements Function<Class<?>, URI>
{
    private static final Logger LOG = Log.getLogger(ModuleLocation.class);

    private final Class<?> classModule;
    private final MethodHandle handleGetModule;
    private final MethodHandle handleGetLayer;
    private final MethodHandle handleConfiguration;
    private final MethodHandle handleGetName;
    private final MethodHandle handleOptionalResolvedModule;
    private final MethodHandle handleReference;
    private final MethodHandle handleLocation;

    public ModuleLocation()
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        ClassLoader loader = ClassLoader.getSystemClassLoader();

        try
        {
            classModule = loader.loadClass("java.lang.Module");
            handleGetModule = lookup.findVirtual(Class.class, "getModule", methodType(classModule));

            Class<?> classLayer = loader.loadClass("java.lang.ModuleLayer");
            handleGetLayer = lookup.findVirtual(classModule, "getLayer", methodType(classLayer));

            Class<?> classConfiguration = loader.loadClass("java.lang.module.Configuration");
            handleConfiguration = lookup.findVirtual(classLayer, "configuration", methodType(classConfiguration));

            handleGetName = lookup.findVirtual(classModule, "getName", methodType(String.class));

            Method findModuleMethod = classConfiguration.getMethod("findModule", String.class);
            handleOptionalResolvedModule = lookup.findVirtual(classConfiguration, "findModule", methodType(findModuleMethod.getReturnType(), String.class));

            Class<?> classResolvedModule = loader.loadClass("java.lang.module.ResolvedModule");
            Class<?> classReference = loader.loadClass("java.lang.module.ModuleReference");
            handleReference = lookup.findVirtual(classResolvedModule, "reference", methodType(classReference));

            Method locationMethod = classReference.getMethod("location");
            handleLocation = lookup.findVirtual(classReference, "location", methodType(locationMethod.getReturnType()));
        }
        catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e)
        {
            throw new UnsupportedOperationException("Not supported on this runtime", e);
        }
    }

    @Override
    public URI apply(Class<?> clazz)
    {
        try
        {
            // Module module = clazz.getModule();
            Object module = handleGetModule.invoke(clazz);
            if (module == null)
            {
                return null;
            }

            // ModuleLayer layer = module.getLayer();
            Object layer = handleGetLayer.invoke(module);
            if (layer == null)
            {
                return null;
            }

            // Configuration configuration = layer.configuration();
            Object configuration = handleConfiguration.invoke(layer);
            if (configuration == null)
            {
                return null;
            }

            // String moduleName = module.getName();
            String moduleName = (String)handleGetName.invoke(module);
            if (moduleName == null)
            {
                return null;
            }

            // Optional<ResolvedModule> optionalResolvedModule = configuration.findModule(moduleName);
            Optional<?> optionalResolvedModule = (Optional<?>)handleOptionalResolvedModule.invoke(configuration, moduleName);
            if (!optionalResolvedModule.isPresent())
            {
                return null;
            }

            // ResolveModule resolved = optionalResolvedModule.get();
            Object resolved = optionalResolvedModule.get();

            // ModuleReference moduleReference = resolved.reference();
            Object moduleReference = handleReference.invoke(resolved);

            // Optional<URI> location = moduleReference.location();
            Optional<URI> location = (Optional<URI>)handleLocation.invoke(moduleReference);
            if (location != null || location.isPresent())
            {
                return location.get();
            }
        }
        catch (Throwable ignored)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.ignore(ignored);
            }
        }
        return null;
    }
}
