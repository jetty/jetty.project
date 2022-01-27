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

package org.eclipse.jetty.annotations;

import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.Source;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.resource.EmptyResource;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebDescriptor;
import org.eclipse.jetty.xml.XmlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestAnnotationDecorator
{
    public class TestWebDescriptor extends WebDescriptor
    {
        public TestWebDescriptor(MetaData.Complete metadata)
        {
            super(EmptyResource.INSTANCE);
            _metaDataComplete = metadata;
        }

        @Override
        public void parse(XmlParser parser) throws Exception
        {
        }

        @Override
        public void processVersion()
        {
        }

        @Override
        public void processOrdering()
        {
        }

        @Override
        public void processDistributable()
        {
        }

        @Override
        public int getMajorVersion()
        {
            return 4;
        }

        @Override
        public int getMinorVersion()
        {
            return 0;
        }
    }

    @Test
    public void testAnnotationDecorator() throws Exception
    {
        assertThrows(NullPointerException.class, () ->
        {
            new AnnotationDecorator(null);
        });

        WebAppContext context = new WebAppContext();
        AnnotationDecorator decorator = new AnnotationDecorator(context);
        ServletE servlet = new ServletE();
        //test without BaseHolder metadata
        decorator.decorate(servlet);
        LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
        assertNotNull(callbacks);
        assertFalse(callbacks.getPreDestroyCallbacks().isEmpty());

        //reset
        context.removeAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);

        //test with BaseHolder metadata, should not introspect with metdata-complete==true
        context.getMetaData().setWebDescriptor(new TestWebDescriptor(MetaData.Complete.True));
        assertTrue(context.getMetaData().isMetaDataComplete());
        ServletHolder holder = new ServletHolder(new Source(Source.Origin.DESCRIPTOR, ""));
        holder.setHeldClass(ServletE.class);
        context.getServletHandler().addServlet(holder);
        DecoratedObjectFactory.associateInfo(holder);
        decorator = new AnnotationDecorator(context);
        decorator.decorate(servlet);
        DecoratedObjectFactory.disassociateInfo();
        callbacks = (LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
        assertNull(callbacks);

        //reset
        context.removeAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);

        //test with BaseHolder metadata, should introspect with metadata-complete==false
        context.getMetaData().setWebDescriptor(new TestWebDescriptor(MetaData.Complete.False));
        DecoratedObjectFactory.associateInfo(holder);
        decorator = new AnnotationDecorator(context);
        decorator.decorate(servlet);
        DecoratedObjectFactory.disassociateInfo();
        callbacks = (LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
        assertNotNull(callbacks);
        assertFalse(callbacks.getPreDestroyCallbacks().isEmpty());
    }
}
