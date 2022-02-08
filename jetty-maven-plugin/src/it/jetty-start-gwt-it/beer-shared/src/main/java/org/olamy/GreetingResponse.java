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

package org.olamy;

import java.io.Serializable;

@SuppressWarnings("serial")
public class GreetingResponse implements Serializable
{
    private String greeting;
    private String serverInfo;
    private String userAgent;

    public String getGreeting()
    {
        return greeting;
    }

    public void setGreeting(String greeting)
    {
        this.greeting = greeting;
    }

    public String getServerInfo()
    {
        return serverInfo;
    }

    public void setServerInfo(String serverInfo)
    {
        this.serverInfo = serverInfo;
    }

    public String getUserAgent()
    {
        return userAgent;
    }

    public void setUserAgent(String userAgent)
    {
        this.userAgent = userAgent;
    }
}
