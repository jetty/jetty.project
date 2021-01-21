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

package org.eclipse.jetty.session.infinispan;

import org.infinispan.commons.marshall.jboss.AbstractJBossMarshaller;
import org.jboss.marshalling.ContextClassResolver;

/**
 * WebAppMarshaller
 *
 * An implementation of the AbstractJBossMarshaller code that is just
 * enough to provide a ContextClassResolver that will use the Thread Context Classloader
 * in order to deserialize session attribute classes.
 *
 * This is necessary because the standard infinispan marshaller (GenericJBossMarshaller) uses the
 * classloader of the loader that loaded itself. When using the infinispan module in Jetty, all of
 * the infinispan classes will be on the container classpath. That means that the GenericJBossMarshaller
 * returns the container classloader which is unable to load any webapp classes. This class ensures
 * that it is always the webapp's classloader that will be used.
 *
 * In order to use this class, you should put a hotrod-client.properties file into the
 * ${jetty.base}/resources directory that contains this line:
 *
 * infinispan.client.hotrod.marshaller=org.eclipse.jetty.session.infinispan.WebAppMarshaller
 *
 * You will also need to add the following lines to a context xml file for your webapp to
 * permit the webapp's classloader to see the org.eclipse.jetty.session.infinispan classes for
 * the deserialization to work correctly:
 *
 * &lt;Call name="prependServerClass"&gt;
 * &lt;Arg&gt;-org.eclipse.jetty.session.infinispan.&lt;/Arg&gt;
 * &lt;/Call&gt;
 */
@Deprecated
public class WebAppMarshaller extends AbstractJBossMarshaller
{

    /**
     * WebAppContextClassResolver
     *
     * Provides the Thread Context Classloader to use for deserializing.
     */
    public static class WebAppContextClassResolver extends ContextClassResolver
    {
        public WebAppContextClassResolver()
        {
            super();
        }

        @Override
        protected ClassLoader getClassLoader()
        {
            return Thread.currentThread().getContextClassLoader();
        }
    }

    public WebAppMarshaller()
    {
        super();
        baseCfg.setClassResolver(new WebAppContextClassResolver());
    }
}
