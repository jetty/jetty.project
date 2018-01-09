//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.gateway;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.rhttp.gateway.HostTargetIdRetriever;

import junit.framework.TestCase;

/**
 * @version $Revision$ $Date$
 */
public class HostTargetIdRetrieverTest extends TestCase
{
    public void testHostTargetIdRetrieverNoSuffix()
    {
        String host = "test";
        Class<HttpServletRequest> klass = HttpServletRequest.class;
        HttpServletRequest request = (HttpServletRequest)Proxy.newProxyInstance(klass.getClassLoader(), new Class<?>[]{klass}, new Request(host));

        HostTargetIdRetriever retriever = new HostTargetIdRetriever(null);
        String result = retriever.retrieveTargetId(request);

        assertEquals(host, result);
    }

    public void testHostTargetIdRetrieverWithSuffix()
    {
        String suffix = ".rhttp.example.com";
        String host = "test";
        Class<HttpServletRequest> klass = HttpServletRequest.class;
        HttpServletRequest request = (HttpServletRequest)Proxy.newProxyInstance(klass.getClassLoader(), new Class<?>[]{klass}, new Request(host + suffix));

        HostTargetIdRetriever retriever = new HostTargetIdRetriever(suffix);
        String result = retriever.retrieveTargetId(request);

        assertEquals(host, result);
    }

    public void testHostTargetIdRetrieverWithSuffixAndPort()
    {
        String suffix = ".rhttp.example.com";
        String host = "test";
        Class<HttpServletRequest> klass = HttpServletRequest.class;
        HttpServletRequest request = (HttpServletRequest)Proxy.newProxyInstance(klass.getClassLoader(), new Class<?>[]{klass}, new Request(host + suffix + ":8080"));

        HostTargetIdRetriever retriever = new HostTargetIdRetriever(suffix);
        String result = retriever.retrieveTargetId(request);

        assertEquals(host, result);
    }

    public void testHostTargetIdRetrieverNullHost()
    {
        Class<HttpServletRequest> klass = HttpServletRequest.class;
        HttpServletRequest request = (HttpServletRequest)Proxy.newProxyInstance(klass.getClassLoader(), new Class<?>[]{klass}, new Request(null));

        HostTargetIdRetriever retriever = new HostTargetIdRetriever(".rhttp.example.com");
        String result = retriever.retrieveTargetId(request);

        assertNull(result);
    }

    private static class Request implements InvocationHandler
    {
        private final String host;

        private Request(String host)
        {
            this.host = host;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            if ("getHeader".equals(method.getName()))
            {
                if (args.length == 1 && "Host".equals(args[0]))
                {
                    return host;
                }
            }
            return null;
        }
    }

}
