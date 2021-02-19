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

package org.eclipse.jetty.security;

import java.util.Collection;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.util.security.Constraint;

public class ConstraintMapping extends PathSpec.Mapping
{
    String _method;
    String[] _methodOmissions;

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
        this._method = method;
    }

    /**
     * @param omissions The http-method-omission
     */
    public void setMethodOmissions(String[] omissions)
    {
        _methodOmissions = omissions;
    }

    public String[] getMethodOmissions()
    {
        return _methodOmissions;
    }

    @Override
    public void addPathSpec(String pathSpec)
    {
        if (hasPathSpecs())
            throw new IllegalArgumentException("> 1 pathSpec");
        super.addPathSpec(pathSpec);
    }

    @Override
    public void addPathSpec(PathSpec pathSpec)
    {
        if (hasPathSpecs())
            throw new IllegalArgumentException("> 1 pathSpec");
        super.addPathSpec(pathSpec);
    }

    @Override
    public void setPathSpecs(String[] pathSpecs)
    {
        if (pathSpecs != null && pathSpecs.length > 1)
            throw new IllegalArgumentException("> 1 pathSpec");
        super.setPathSpecs(pathSpecs);
    }

    @Override
    public void setPathSpecs(PathSpec[] pathSpecs)
    {
        if (pathSpecs != null && pathSpecs.length > 1)
            throw new IllegalArgumentException("> 1 pathSpec");
        super.setPathSpecs(pathSpecs);
    }

    @Override
    public void setPathSpecs(Collection<PathSpec> pathSpecs)
    {
        if (pathSpecs != null && pathSpecs.size() > 1)
            throw new IllegalArgumentException("> 1 pathSpec");
        super.setPathSpecs(pathSpecs);
    }

    @Override
    public void setServletPathSpecs(String[] pathSpecs)
    {
        if (pathSpecs != null && pathSpecs.length > 1)
            throw new IllegalArgumentException("> 1 pathSpec");
        super.setServletPathSpecs(pathSpecs);
    }

    /**
     * @param pathSpec The pathSpecs to set, which are assumed to be {@link ServletPathSpec}s
     */
    public void setServletPathSpec(String pathSpec)
    {
        super.setServletPathSpecs(pathSpec == null ? new String[]{} : new String[]{pathSpec});
    }

    public String getServletPathSpec()
    {
        return stream().filter(ServletPathSpec.class::isInstance).findFirst().map(PathSpec::getDeclaration).orElse(null);
    }

    public PathSpec toPathSpec()
    {
        return stream().findFirst().orElse(null);
    }
}
