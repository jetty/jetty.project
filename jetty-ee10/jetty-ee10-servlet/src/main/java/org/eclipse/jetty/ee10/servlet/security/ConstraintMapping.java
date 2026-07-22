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

package org.eclipse.jetty.ee10.servlet.security;

import java.util.Arrays;

import org.eclipse.jetty.http.Syntax;
import org.eclipse.jetty.security.Constraint;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;

public class ConstraintMapping
{
    String _method;
    String[] _methodOmissions;
    String _pathSpec;
    Constraint _constraint;

    /**
     * @return Returns the constraint.
     */
    public Constraint getConstraint()
    {
        return _constraint;
    }

    /**
     * @param constraint The constraint to set.
     */
    public void setConstraint(Constraint constraint)
    {
        this._constraint = constraint;
    }

    /**
     * @return Returns the method.
     */
    public String getMethod()
    {
        return _method;
    }

    /**
     * @param method The method to set.
     */
    public void setMethod(String method)
    {
        if (method != null)
            checkHttpMethodName(method, "Servlet Constraint HTTP Method");

        this._method = method;
    }

    /**
     * @return Returns the pathSpec.
     */
    public String getPathSpec()
    {
        return _pathSpec;
    }

    /**
     * @param pathSpec The pathSpec to set.
     */
    public void setPathSpec(String pathSpec)
    {
        this._pathSpec = pathSpec;
    }

    /**
     * @param omissions The http-method-omission
     */
    public void setMethodOmissions(String[] omissions)
    {
        if (omissions != null)
        {
            for (String omission: omissions)
                checkHttpMethodName(omission, "Servlet Constraint HTTP Method Omission");
        }

        _methodOmissions = omissions;
    }

    public String[] getMethodOmissions()
    {
        return _methodOmissions;
    }

    public boolean hasMethodOmissions()
    {
        return _methodOmissions != null && _methodOmissions.length > 0;
    }

    private void checkHttpMethodName(String methodName, String msg)
    {
        if (StringUtil.isBlank(methodName))
            throw new IllegalArgumentException("Blank HTTP Method Name: " + msg);
        if ("*".equals(methodName))
            throw new IllegalArgumentException("Invalid HTTP Method Name '*': " + msg);
        Syntax.requireValidRFC2616Token(methodName, msg);
    }

    @Override
    public String toString()
    {
        return "%s@%x{method=%s,omissions=%s,pathSpec=%s -> %s}".formatted(
            TypeUtil.toShortName(getClass()),
            hashCode(),
            _method,
            _methodOmissions == null ? null : Arrays.asList(_methodOmissions),
            _pathSpec,
            _constraint);
    }
}
