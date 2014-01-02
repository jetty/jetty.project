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

package org.eclipse.jetty.start;

public class FileArg
{
    public String uri;
    public String location;

    public FileArg(String uriLocation)
    {
        String parts[] = uriLocation.split(":",3);
        if (parts.length == 3)
        {
            if (!"http".equalsIgnoreCase(parts[0]))
            {
                throw new IllegalArgumentException("Download only supports http protocol");
            }
            if (!parts[1].startsWith("//"))
            {
                throw new IllegalArgumentException("Download URI invalid: " + uriLocation);
            }
            this.uri = String.format("%s:%s",parts[0],parts[1]);
            this.location = parts[2];
        }
        else
        {
            this.uri = null;
            this.location = uriLocation;
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        FileArg other = (FileArg)obj;
        if (uri == null)
        {
            if (other.uri != null)
            {
                return false;
            }
        }
        else if (!uri.equals(other.uri))
        {
            return false;
        }
        if (location == null)
        {
            if (other.location != null)
            {
                return false;
            }
        }
        else if (!location.equals(other.location))
        {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((uri == null)?0:uri.hashCode());
        result = (prime * result) + ((location == null)?0:location.hashCode());
        return result;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("DownloadArg [uri=");
        builder.append(uri);
        builder.append(", location=");
        builder.append(location);
        builder.append("]");
        return builder.toString();
    }
}
