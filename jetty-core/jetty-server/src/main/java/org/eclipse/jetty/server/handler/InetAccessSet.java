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

package org.eclipse.jetty.server.handler;

import java.net.InetSocketAddress;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.InetAddressPattern;
import org.eclipse.jetty.util.StringUtil;

public class InetAccessSet extends AbstractSet<InetAccessSet.PatternTuple> implements Set<InetAccessSet.PatternTuple>, Predicate<Request>
{
    private final ArrayList<PatternTuple> tuples = new ArrayList<>();

    @Override
    public boolean add(PatternTuple storageTuple)
    {
        return tuples.add(storageTuple);
    }

    @Override
    public boolean remove(Object o)
    {
        return tuples.remove(o);
    }

    @Override
    public Iterator<PatternTuple> iterator()
    {
        return tuples.iterator();
    }

    @Override
    public int size()
    {
        return tuples.size();
    }

    @Override
    public boolean test(Request entry)
    {
        if (entry == null)
            return false;

        for (PatternTuple tuple : tuples)
        {
            if (tuple.test(entry))
                return true;
        }
        return false;
    }

    public static class PatternTuple implements Predicate<Request>
    {
        private final String connector;
        private final InetAddressPattern address;
        private final String method;
        private final PathSpec pathSpec;

        public static PatternTuple from(String pattern)
        {
            String path = null;
            int pathIndex = pattern.indexOf('|');
            if (pathIndex >= 0)
            {
                path = pattern.substring(pathIndex + 1);
                pattern = pattern.substring(0, pathIndex);
            }

            String method = null;
            int methodIndex = pattern.indexOf('>');
            if (methodIndex >= 0)
            {
                method = pattern.substring(methodIndex + 1);
                pattern = pattern.substring(0, methodIndex);
            }

            String connector = null;
            int connectorIndex = pattern.indexOf('@');
            if (connectorIndex >= 0)
                connector = pattern.substring(0, connectorIndex);

            String addr = null;
            int addrStart = (connectorIndex < 0) ? 0 : connectorIndex + 1;
            int addrEnd = (pathIndex < 0) ? pattern.length() : pathIndex;
            if (addrStart != addrEnd)
                addr = pattern.substring(addrStart, addrEnd);

            return new PatternTuple(
                connector,
                InetAddressPattern.from(addr),
                method,
                StringUtil.isEmpty(path) ? null : new ServletPathSpec(path));
        }

        public PatternTuple(String connector, InetAddressPattern address, String method, PathSpec pathSpec)
        {
            this.connector = connector;
            this.address = address;
            this.method = method;
            this.pathSpec = pathSpec;
        }

        @Override
        public boolean test(Request request)
        {
            // Match for connector.
            if ((connector != null) && !connector.equals(request.getConnectionMetaData().getConnector().getName()))
                return false;

            // If we have a method we must match
            if ((method != null) && !method.equals(request.getMethod()))
                return false;

            // If we have a path we must be at this path to match for an address.
            if ((pathSpec != null) && !pathSpec.matches(Request.getPathInContext(request)))
                return false;

            // Match for InetAddress.
            return address == null ||
                request.getConnectionMetaData().getRemoteSocketAddress() instanceof InetSocketAddress inet && address.test(inet.getAddress());
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{connector=%s, addressPattern=%s, method=%s, pathSpec=%s}", getClass().getSimpleName(), hashCode(), connector, address, method, pathSpec);
        }
    }
}
