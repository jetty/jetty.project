//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.api;

public interface Authentication
{
    boolean matches(String type, String uri, String realm);

    void authenticate(Request request);

    public static class Result
    {
        private final String uri;
        private final Authentication authentication;

        public Result(String uri, Authentication authentication)
        {
            this.uri = uri;
            this.authentication = authentication;
        }

        public String getURI()
        {
            return uri;
        }

        public Authentication getAuthentication()
        {
            return authentication;
        }
    }
}
