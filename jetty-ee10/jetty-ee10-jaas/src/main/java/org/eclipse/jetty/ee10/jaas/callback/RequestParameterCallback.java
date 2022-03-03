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

package org.eclipse.jetty.ee10.jaas.callback;

import java.util.List;
import javax.security.auth.callback.Callback;

/**
 * RequestParameterCallback
 * <p>
 * Allows a JAAS callback handler to access any parameter from the j_security_check FORM.
 * This means that a LoginModule can access form fields other than the j_username and j_password
 * fields, and use it, for example, to authenticate a user.
 */
public class RequestParameterCallback implements Callback
{
    private String _paramName;
    private List<?> _paramValues;

    public void setParameterName(String name)
    {
        _paramName = name;
    }

    public String getParameterName()
    {
        return _paramName;
    }

    public void setParameterValues(List<?> values)
    {
        _paramValues = values;
    }

    public List<?> getParameterValues()
    {
        return _paramValues;
    }
}
