//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.cdi;

import java.util.Objects;
import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.util.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link ServletContainerInitializer} that introspects for a CDI API
 * implementation within a web application and applies an integration
 * mode if CDI is found.  CDI integration modes can be selected per webapp with
 * the "org.eclipse.jetty.ee10.cdi" init parameter or default to the mode set by the
 * "org.eclipse.jetty.ee10.cdi" server attribute.  Supported modes are:</p>
 * <dl>
 * <dt>CdiSpiDecorator</dt>
 *     <dd>Jetty will call the CDI SPI within the webapp to decorate objects (default).</dd>
 * <dt>CdiDecoratingLister</dt>
 *     <dd>The webapp may register a decorator on the context attribute
 *     "org.eclipse.jetty.ee10.cdi.decorator".</dd>
 * </dl>
 *
 * @see AnnotationConfiguration.ServletContainerInitializerOrdering
 */
public class CdiServletContainerInitializer implements ServletContainerInitializer
{
    public static final String CDI_INTEGRATION_ATTRIBUTE = "org.eclipse.jetty.ee10.cdi";
    private static final Logger LOG = LoggerFactory.getLogger(CdiServletContainerInitializer.class);

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx)
    {
        try
        {
            ServletContextHandler context = ServletContextHandler.getServletContextHandler(ctx);
            Objects.requireNonNull(context);

            // Test if CDI is in the webapp by trying to load the CDI class.
            ClassLoader loader  = context.getClassLoader();
            if (loader == null)
                Loader.loadClass("jakarta.enterprise.inject.spi.CDI");
            else
                loader.loadClass("jakarta.enterprise.inject.spi.CDI");

            String mode = ctx.getInitParameter(CDI_INTEGRATION_ATTRIBUTE);
            if (mode == null)
            {
                mode = (String)context.getServer().getAttribute(CDI_INTEGRATION_ATTRIBUTE);
                if (mode == null)
                    mode = CdiSpiDecorator.MODE;
            }

            switch (mode)
            {
                case CdiSpiDecorator.MODE:
                    context.getObjectFactory().addDecorator(new CdiSpiDecorator(context));
                    break;

                case CdiDecoratingListener.MODE:
                    context.addEventListener(new CdiDecoratingListener(context));
                    break;

                default:
                    throw new IllegalStateException(mode);
            }
            LOG.info("{} enabled in {}", mode, ctx);
        }
        catch (UnsupportedOperationException | ClassNotFoundException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("CDI not found in {}", ctx, e);
        }
    }
}
