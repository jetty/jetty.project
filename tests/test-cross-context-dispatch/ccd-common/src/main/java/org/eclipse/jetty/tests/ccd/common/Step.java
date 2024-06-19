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

package org.eclipse.jetty.tests.ccd.common;

public interface Step
{
    static Step parse(String line)
    {
        String[] parts = line.split("\\|");
        switch (parts[1])
        {
            case "CONTEXT_FORWARD" ->
            {
                ContextRedispatch step = new ContextRedispatch();
                step.setDispatchType(DispatchType.FORWARD);
                step.setContextPath(parts[2]);
                step.setDispatchPath(parts[3]);
                return step;
            }
            case "CONTEXT_INCLUDE" ->
            {
                ContextRedispatch step = new ContextRedispatch();
                step.setDispatchType(DispatchType.INCLUDE);
                step.setContextPath(parts[2]);
                step.setDispatchPath(parts[3]);
                return step;
            }
            case "REQUEST_FORWARD" ->
            {
                RequestDispatch step = new RequestDispatch();
                step.setDispatchType(DispatchType.FORWARD);
                step.setDispatchPath(parts[2]);
                return step;
            }
            case "REQUEST_INCLUDE" ->
            {
                RequestDispatch step = new RequestDispatch();
                step.setDispatchType(DispatchType.INCLUDE);
                step.setDispatchPath(parts[2]);
                return step;
            }
            case "GET_HTTP_SESSION_ATTRIBUTE" ->
            {
                GetHttpSession step = new GetHttpSession();
                step.setName(parts[2]);
                return step;
            }
            case "SET_HTTP_SESSION_ATTRIBUTE" ->
            {
                String name = parts[2];
                String value = parts[3];
                Property prop = new Property(name, value);
                HttpSessionSetAttribute step = new HttpSessionSetAttribute(prop);
                return step;
            }
        }
        throw new RuntimeException("Unknown STEP type [" + parts[1] + "]");
    }

    /**
     * Will cause an Attribute to be set on the HttpSession via {@code HttpSession.setAttribute(String, Object)}
     */
    class HttpSessionSetAttribute implements Step
    {
        private Property property;

        public HttpSessionSetAttribute(Property property)
        {
            this.property = property;
        }

        public Property getProperty()
        {
            return property;
        }
    }

    /**
     * Will cause the HttpSession to be fetched via {@code HttpServletRequest#getHttpSession(false)}
     * and report the state of the HttpSession in the events (even if null).
     */
    class GetHttpSession implements Step
    {
        private String name;

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }
    }

    /**
     * Performs a Redispatch with FORWARD or INCLUDE types using the {@code ServletContext}.
     * Uses the {@code ServletContext.getContext(contextPath)} to obtain the
     * {@code ServletContext} to then use {@code ServletContext.getRequestDispatcher(dispatchPath)}
     * against, which then results in a {@code RequestDispatcher.include} or {@code RequestDispatcher.forward}
     * call.
     */
    class ContextRedispatch implements Step
    {
        private DispatchType dispatchType;
        private String contextPath;
        private String dispatchPath;

        public DispatchType getDispatchType()
        {
            return dispatchType;
        }

        public void setDispatchType(DispatchType dispatchType)
        {
            this.dispatchType = dispatchType;
        }

        public String getContextPath()
        {
            return contextPath;
        }

        public void setContextPath(String contextPath)
        {
            this.contextPath = contextPath;
        }

        public String getDispatchPath()
        {
            return dispatchPath;
        }

        public void setDispatchPath(String dispatchPath)
        {
            this.dispatchPath = dispatchPath;
        }
    }

    /**
     * Performs a Redispatch with FORWARD or INCLUDE types using the {@code HttpServletRequest}.
     * Uses the {@code HttpServletRequest.getRequestDispatcher(dispatchPath)} which then
     * results in a {@code RequestDispatcher.include} or {@code RequestDispatcher.forward}
     * call.
     */
    class RequestDispatch implements Step
    {
        private DispatchType dispatchType;
        private String dispatchPath;

        public DispatchType getDispatchType()
        {
            return dispatchType;
        }

        public void setDispatchType(DispatchType dispatchType)
        {
            this.dispatchType = dispatchType;
        }

        public String getDispatchPath()
        {
            return dispatchPath;
        }

        public void setDispatchPath(String dispatchPath)
        {
            this.dispatchPath = dispatchPath;
        }
    }
}
