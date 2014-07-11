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

import java.util.Iterator;

public abstract class AbstractMetaData implements MetaData
{
    @Override
    public boolean isRequest()
    {
        return false;
    }

    @Override
    public boolean isResponse()
    {
        return false;
    }

    @Override
    public Iterator<HttpField> iterator()
    {
        return getFields().iterator();
    }
    
    @Override
    public int hashCode()
    {
        return 31 * getHttpVersion().hashCode() + getFields().hashCode();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (!(o instanceof AbstractMetaData))
            return false;
        AbstractMetaData that = (AbstractMetaData)o;

        if (getHttpVersion() != that.getHttpVersion())
            return false;

        return getFields().equals(that.getFields());
    }

    @Override
    public String toString()
    {
        StringBuilder out = new StringBuilder();
        for (HttpField field: this)
            out.append(field).append(System.lineSeparator());
        return out.toString();
    }
    
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public abstract static class Request extends AbstractMetaData implements MetaData.Request
    {
        @Override
        public boolean isRequest()
        {
            return true;
        }

        @Override
        public boolean isResponse()
        {
            return false;
        }

        @Override
        public int hashCode()
        {
            int hash = getMethod().hashCode();
            hash = 31 * hash + getScheme().hashCode();
            hash = 31 * hash + getURI().hashCode();
            return 31 * hash + super.hashCode();
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (!(o instanceof MetaData.Request))
                return false;
            MetaData.Request that = (MetaData.Request)o;
            if (!getMethod().equals(that.getMethod()) ||
                !getScheme().equals(that.getScheme()) ||
                !getURI().equals(that.getURI()))
                return false;
            return super.equals(o);
        }

        @Override
        public String toString()
        {
            return String.format("%s %s://%s:%d%s HTTP/2%s%s",
                    getMethod(), getScheme(), getHost(), getPort(), getURI(), System.lineSeparator(), super.toString());
        }
    }

    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    /* -------------------------------------------------------- */
    public abstract static class Response extends AbstractMetaData implements MetaData.Response
    {
        @Override
        public boolean isRequest()
        {
            return false;
        }

        @Override
        public boolean isResponse()
        {
            return true;
        }
        @Override
        public int hashCode()
        {
            return 31 * getStatus() + super.hashCode();
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
                return true;
            if (!(o instanceof MetaData.Response))
                return false;
            MetaData.Response that = (MetaData.Response)o;
            if (getStatus() != that.getStatus())
                return false;
            return super.equals(o);
        }

        @Override
        public String toString()
        {
            return String.format("HTTP/2 %d%s%s", getStatus(), System.lineSeparator(), super.toString());
        }
    }
}
