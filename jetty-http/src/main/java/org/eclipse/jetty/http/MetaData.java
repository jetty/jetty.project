//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http;

public interface MetaData extends Iterable<HttpField>
{
    public HttpVersion getHttpVersion();
    public boolean isRequest();
    public boolean isResponse();
    public HttpFields getFields();
    
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public interface Request extends MetaData
    {
        public String getMethod();
        public HttpScheme getScheme();
        public String getHost();
        public int getPort();
        public HttpURI getURI();
    }

    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public interface Response extends MetaData
    {
        public int getStatus();
    }
}
