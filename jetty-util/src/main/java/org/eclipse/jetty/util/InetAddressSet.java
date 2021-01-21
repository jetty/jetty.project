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

package org.eclipse.jetty.util;

import java.net.InetAddress;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A set of InetAddress patterns.
 * <p>This is a {@link Set} of String patterns that are used to match
 * a {@link Predicate} over InetAddress for containment semantics.
 * The patterns that may be set are:
 * </p>
 * <dl>
 * <dt>InetAddress</dt><dd>A single InetAddress either in hostname or address format.
 * All formats supported by {@link InetAddress} are accepted.   Not ethat using hostname
 * matches may force domain lookups.  eg. "[::1]", "1.2.3.4", "::ffff:127.0.0.1"</dd>
 * <dt>InetAddress/CIDR</dt><dd>An InetAddress with a integer number of bits to indicate
 * the significant prefix. eg. "192.168.0.0/16" will match from "192.168.0.0" to
 * "192.168.255.255" </dd>
 * <dt>InetAddress-InetAddress</dt><dd>An inclusive range of InetAddresses.
 * eg. "[a000::1]-[afff::]", "192.168.128.0-192.168.128.255"</dd>
 * <dt>Legacy format</dt><dd>The legacy format used by {@link IPAddressMap} for IPv4 only.
 * eg. "10.10.10-14.0-128"</dd>
 * </dl>
 * <p>This class is designed to work with {@link IncludeExcludeSet}</p>
 *
 * @see IncludeExcludeSet
 */
public class InetAddressSet extends AbstractSet<String> implements Set<String>, Predicate<InetAddress>
{
    private Map<String, InetPattern> _patterns = new HashMap<>();

    @Override
    public boolean add(String pattern)
    {
        return _patterns.put(pattern, newInetRange(pattern)) == null;
    }

    private InetPattern newInetRange(String pattern)
    {
        if (pattern == null)
            return null;

        int slash = pattern.lastIndexOf('/');
        int dash = pattern.lastIndexOf('-');
        try
        {
            if (slash >= 0)
                return new CidrInetRange(pattern, InetAddress.getByName(pattern.substring(0, slash).trim()), StringUtil.toInt(pattern, slash + 1));

            if (dash >= 0)
                return new MinMaxInetRange(pattern, InetAddress.getByName(pattern.substring(0, dash).trim()), InetAddress.getByName(pattern.substring(dash + 1).trim()));

            return new SingletonInetRange(pattern, InetAddress.getByName(pattern));
        }
        catch (Exception e)
        {
            try
            {
                if (slash < 0 && dash > 0)
                    return new LegacyInetRange(pattern);
            }
            catch (Exception ex2)
            {
                e.addSuppressed(ex2);
            }
            throw new IllegalArgumentException("Bad pattern: " + pattern, e);
        }
    }

    @Override
    public boolean remove(Object pattern)
    {
        return _patterns.remove(pattern) != null;
    }

    @Override
    public Iterator<String> iterator()
    {
        return _patterns.keySet().iterator();
    }

    @Override
    public int size()
    {
        return _patterns.size();
    }

    @Override
    public boolean test(InetAddress address)
    {
        if (address == null)
            return false;
        byte[] raw = address.getAddress();
        for (InetPattern pattern : _patterns.values())
        {
            if (pattern.test(address, raw))
                return true;
        }
        return false;
    }

    abstract static class InetPattern
    {
        final String _pattern;

        InetPattern(String pattern)
        {
            _pattern = pattern;
        }

        abstract boolean test(InetAddress address, byte[] raw);

        @Override
        public String toString()
        {
            return _pattern;
        }
    }

    static class SingletonInetRange extends InetPattern
    {
        final InetAddress _address;

        public SingletonInetRange(String pattern, InetAddress address)
        {
            super(pattern);
            _address = address;
        }

        @Override
        public boolean test(InetAddress address, byte[] raw)
        {
            return _address.equals(address);
        }
    }

    static class MinMaxInetRange extends InetPattern
    {
        final int[] _min;
        final int[] _max;

        public MinMaxInetRange(String pattern, InetAddress min, InetAddress max)
        {
            super(pattern);

            byte[] rawMin = min.getAddress();
            byte[] rawMax = max.getAddress();
            if (rawMin.length != rawMax.length)
                throw new IllegalArgumentException("Cannot mix IPv4 and IPv6: " + pattern);

            if (rawMin.length == 4)
            {
                // there must be 6 '.' or this is likely to be a legacy pattern
                int count = 0;
                for (char c : pattern.toCharArray())
                {
                    if (c == '.')
                        count++;
                }
                if (count != 6)
                    throw new IllegalArgumentException("Legacy pattern: " + pattern);
            }

            _min = new int[rawMin.length];
            _max = new int[rawMin.length];

            for (int i = 0; i < _min.length; i++)
            {
                _min[i] = 0xff & rawMin[i];
                _max[i] = 0xff & rawMax[i];
            }

            for (int i = 0; i < _min.length; i++)
            {
                if (_min[i] > _max[i])
                    throw new IllegalArgumentException("min is greater than max: " + pattern);
                if (_min[i] < _max[i])
                    break;
            }
        }

        @Override
        public boolean test(InetAddress item, byte[] raw)
        {
            if (raw.length != _min.length)
                return false;

            boolean minOk = false;
            boolean maxOk = false;

            for (int i = 0; i < _min.length; i++)
            {
                int r = 0xff & raw[i];
                if (!minOk)
                {
                    if (r < _min[i])
                        return false;
                    if (r > _min[i])
                        minOk = true;
                }
                if (!maxOk)
                {
                    if (r > _max[i])
                        return false;
                    if (r < _max[i])
                        maxOk = true;
                }

                if (minOk && maxOk)
                    break;
            }

            return true;
        }
    }

    static class CidrInetRange extends InetPattern
    {
        final byte[] _raw;
        final int _octets;
        final int _mask;
        final int _masked;

        public CidrInetRange(String pattern, InetAddress address, int cidr)
        {
            super(pattern);
            _raw = address.getAddress();
            _octets = cidr / 8;
            _mask = 0xff & (0xff << (8 - cidr % 8));
            _masked = _mask == 0 ? 0 : _raw[_octets] & _mask;

            if (cidr > (_raw.length * 8))
                throw new IllegalArgumentException("CIDR too large: " + pattern);

            if (_mask != 0 && (0xff & _raw[_octets]) != _masked)
                throw new IllegalArgumentException("CIDR bits non zero: " + pattern);

            for (int o = _octets + (_mask == 0 ? 0 : 1); o < _raw.length; o++)
            {
                if (_raw[o] != 0)
                    throw new IllegalArgumentException("CIDR bits non zero: " + pattern);
            }
        }

        @Override
        public boolean test(InetAddress item, byte[] raw)
        {
            if (raw.length != _raw.length)
                return false;

            for (int o = 0; o < _octets; o++)
            {
                if (_raw[o] != raw[o])
                    return false;
            }

            return _mask == 0 || (raw[_octets] & _mask) == _masked;
        }
    }

    static class LegacyInetRange extends InetPattern
    {
        int[] _min = new int[4];
        int[] _max = new int[4];

        public LegacyInetRange(String pattern)
        {
            super(pattern);

            String[] parts = pattern.split("\\.");
            if (parts.length != 4)
                throw new IllegalArgumentException("Bad legacy pattern: " + pattern);

            for (int i = 0; i < 4; i++)
            {
                String part = parts[i].trim();
                int dash = part.indexOf('-');
                if (dash < 0)
                    _min[i] = _max[i] = Integer.parseInt(part);
                else
                {
                    _min[i] = (dash == 0) ? 0 : StringUtil.toInt(part, 0);
                    _max[i] = (dash == part.length() - 1) ? 255 : StringUtil.toInt(part, dash + 1);
                }

                if (_min[i] < 0 || _min[i] > _max[i] || _max[i] > 255)
                    throw new IllegalArgumentException("Bad legacy pattern: " + pattern);
            }
        }

        @Override
        public boolean test(InetAddress item, byte[] raw)
        {
            if (raw.length != 4)
                return false;

            for (int i = 0; i < 4; i++)
            {
                if ((0xff & raw[i]) < _min[i] || (0xff & raw[i]) > _max[i])
                    return false;
            }

            return true;
        }
    }
}
