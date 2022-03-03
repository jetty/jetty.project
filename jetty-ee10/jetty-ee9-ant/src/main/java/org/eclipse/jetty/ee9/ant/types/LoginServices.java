//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings and others.
//  ------------------------------------------------------------------------
//  This program and the accompanying materials are made available under the
//  terms of the Eclipse Public License v. 2.0 which is available at
//  https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
//  which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
//  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//  ========================================================================
//

package org.eclipse.jetty.ee9.ant.types;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.ee9.security.LoginService;

/**
 * Specifies a jetty configuration &lt;loginServices/&gt; element for Ant build file.
 */
public class LoginServices
{
    private List<LoginService> loginServices = new ArrayList<LoginService>();

    public void add(LoginService service)
    {
        loginServices.add(service);
    }

    public List<LoginService> getLoginServices()
    {
        return loginServices;
    }
}
