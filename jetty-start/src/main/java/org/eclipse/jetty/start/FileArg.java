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

package org.eclipse.jetty.start;

public class FileArg
{
    public final String moduleName;
    public final String uri;
    public final String location;

    public FileArg(final Module module, final String uriLocation)
    {
        this(module == null ? (String)null : module.getName(), uriLocation);
    }

    public FileArg(final String uriLocation)
    {
        this((String)null, uriLocation);
    }

    private FileArg(final String moduleName, final String uriLocation)
    {
        this.moduleName = moduleName;
        String[] parts = uriLocation.split("\\|", 3);
        if (parts.length > 2)
        {
            StringBuilder err = new StringBuilder();
            final String LN = System.lineSeparator();
            err.append("Unrecognized [file] argument: ").append(uriLocation);
            err.append(LN).append("Valid Syntaxes: ");
            err.append(LN).append("    <relative-path>       - eg: resources/");
            err.append(LN).append(" or <absolute-path>       - eg: /var/run/jetty.pid");
            err.append(LN).append(" or <uri>                 - eg: basehome:some/path");
            err.append(LN).append(" or <uri>|<rel-path>      - eg: http://machine/my.conf|resources/my.conf");
            err.append(LN).append(" or <uri>|<abs-path>      - eg: http://machine/glob.dat|/opt/run/glob.dat");
            err.append(LN).append("Known uri schemes: http, maven, home");
            throw new IllegalArgumentException(err.toString());
        }
        if (parts.length == 2)
        {
            this.uri = parts[0];
            this.location = parts[1];
        }
        else if (uriLocation.contains(":"))
        {
            this.uri = uriLocation;
            this.location = null;
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
        result = (prime * result) + ((uri == null) ? 0 : uri.hashCode());
        result = (prime * result) + ((location == null) ? 0 : location.hashCode());
        return result;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("DownloadArg [uri=").append(uri).append(", location=").append(location).append("]");
        return builder.toString();
    }
}
