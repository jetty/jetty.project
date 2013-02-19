//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.osgi.nested;

import java.lang.reflect.Method;

import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.nested.NestedConnector;
import org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener;
import org.eclipse.jetty.util.component.LifeCycle;
import org.osgi.framework.FrameworkUtil;

/**
 * Listens to the start and stop of the NestedConnector to register and
 * unregister the NestedConnector with the BridgeServlet.
 * <p>
 * All interactions with the BridgeServlet are done via introspection to avoid
 * depending on it directly. The BridgeServlet lives in the bootstrap-webapp;
 * not inside equinox.
 * </p>
 */
public class NestedConnectorListener extends AbstractLifeCycleListener
{

    /**
     * Name of the BridgeServlet class. By default
     * org.eclipse.equinox.servletbridge.BridgeServlet
     */
    private String bridgeServletClassName = "org.eclipse.equinox.servletbridge.BridgeServlet";

    /**
     * Name of the static method on the BridgeServlet class to register the
     * servlet delegate. By default 'registerServletDelegate'
     */
    private String registerServletDelegateMethodName = "registerServletDelegate";

    /**
     * Name of the static method on the BridgeServlet class to register the
     * servlet delegate. By default 'unregisterServletDelegate'
     */
    private String unregisterServletDelegateMethodName = "unregisterServletDelegate";

    /**
     * servlet that wraps this NestedConnector and uses the NestedConnector to
     * service the requests.
     */
    private NestedConnectorServletDelegate _servletDelegate;

    /**
     * The NestedConnector listened to.
     */
    private NestedConnector nestedConnector;

    /**
     * @param bridgeServletClassName Name of the class that is the
     *            BridgeServlet. By default
     *            org.eclipse.equinox.servletbridge.BridgeServlet
     */
    public void setBridgeServletClassName(String bridgeServletClassName)
    {
        this.bridgeServletClassName = bridgeServletClassName;
    }

    public String getBridgeServletClassName()
    {
        return this.bridgeServletClassName;
    }

    public String getRegisterServletDelegateMethodName()
    {
        return this.registerServletDelegateMethodName;
    }

    public String getUnregisterServletDelegateMethodName()
    {
        return this.unregisterServletDelegateMethodName;
    }

    /**
     * @param registerServletDelegateMethodName Name of the static method on the
     *            BridgeServlet class to register the servlet delegate.
     */
    public void setRegisterServletDelegateMethodName(String registerServletDelegateMethodName)
    {
        this.registerServletDelegateMethodName = registerServletDelegateMethodName;
    }

    /**
     * @param unregisterServletDelegateMethodName Name of the static method on
     *            the BridgeServlet class to unregister the servlet delegate.
     */
    public void setUnregisterServletDelegateMethodName(String unregisterServletDelegateMethodName)
    {
        this.unregisterServletDelegateMethodName = unregisterServletDelegateMethodName;
    }

    /**
     * @param nestedConnector The NestedConnector that we are listening to here.
     */
    public void setNestedConnector(NestedConnector nestedConnector)
    {
        this.nestedConnector = nestedConnector;
    }

    /**
     * @return The NestedConnector that we are listening to here.
     */
    public NestedConnector getNestedConnector()
    {
        return this.nestedConnector;
    }

    @Override
    public void lifeCycleStarted(LifeCycle event)
    {
        try
        {
            registerWithBridgeServlet();
        }
        catch (Exception e)
        {
            if (e instanceof RuntimeException) { throw (RuntimeException) e; }
            throw new RuntimeException("Unable to register the servlet delegate into the BridgeServlet.", e);
        }
    }

    @Override
    public void lifeCycleStopping(LifeCycle event)
    {
        try
        {
            unregisterWithBridgeServlet();
        }
        catch (Exception e)
        {
            if (e instanceof RuntimeException) { throw (RuntimeException) e; }
            throw new RuntimeException("Unable to unregister the servlet delegate into the BridgeServlet.", e);
        }
    }

    /**
     * Hook into the BridgeServlet
     */
    protected void registerWithBridgeServlet() throws Exception
    {
        _servletDelegate = new NestedConnectorServletDelegate(getNestedConnector());
        try
        {
            invokeStaticMethod(getBridgeServletClassName(), getRegisterServletDelegateMethodName(), new Class[] { HttpServlet.class }, _servletDelegate);
        }
        catch (Throwable t)
        {
            _servletDelegate.destroy();
            _servletDelegate = null;
            if (t instanceof Exception) { throw (Exception) t; }
            throw new RuntimeException("Unable to register the servlet delegate into the BridgeServlet.", t);
        }
    }

    /**
     * Unhook into the BridgeServlet
     */
    protected void unregisterWithBridgeServlet() throws Exception
    {
        if (_servletDelegate != null)
        {
            try
            {
                invokeStaticMethod(getBridgeServletClassName(), getUnregisterServletDelegateMethodName(), new Class[] { HttpServlet.class }, _servletDelegate);
            }
            catch (Throwable t)
            {
                if (t instanceof Exception) { throw (Exception) t; }
                throw new RuntimeException("Unable to unregister the servlet delegate from the BridgeServlet.", t);
            }
            finally
            {
                _servletDelegate.destroy();
                _servletDelegate = null;
            }
        }
    }

    /**
     * 
     * @param clName
     * @param methName
     * @param argType
     * @throws Exception
     */
    private static void invokeStaticMethod(String clName, String methName, Class[] argType, Object... args) throws Exception
    {
        Method m = getMethod(clName, methName, argType);
        m.invoke(null, args);
    }

    /**
     * 
     * @param clName Class that belongs to the parent classloader of the OSGi
     *            framework.
     * @param methName Name of the method to find.
     * @param argType Argument types of the method to find.
     * @throws Exception
     */
    private static Method getMethod(String clName, String methName, Class... argType) throws Exception
    {
        Class bridgeServletClass = FrameworkUtil.class.getClassLoader().loadClass(clName);
        return getMethod(bridgeServletClass, methName, argType);
    }

    private static Method getMethod(Class cl, String methName, Class... argType) throws Exception
    {
        Method meth = null;
        try
        {
            meth = cl.getMethod(methName, argType);
            return meth;
        }
        catch (Exception e)
        {
            for (Method m : cl.getMethods())
            {
                if (m.getName().equals(methName) && m.getParameterTypes().length == argType.length)
                {
                    int i = 0;
                    for (Class p : m.getParameterTypes())
                    {
                        Class ap = argType[i];
                        if (p.getName().equals(ap.getName()) && !p.equals(ap)) 
                        { 
                            throw new IllegalStateException("The method \"" + m.toGenericString()
                                                            + "\" was found. but the parameter class "
                                                            + p.getName()
                                                            + " is not the same "
                                                            + " inside OSGi classloader ("
                                                            + ap.getClassLoader()
                                                            + ") and inside the "
                                                            + cl.getName()
                                                            + " classloader ("
                                                            + p.getClassLoader()
                                                            + ")."
                                                            + " Are the ExtensionBundles correctly defined?");

                        }
                    }
                }
            }
            throw e;
        }
    }
}
