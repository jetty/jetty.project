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

package org.eclipse.jetty.util;

import java.net.InetAddress;
import java.util.function.Predicate;

/**
 * A pattern representing a single or range of {@link InetAddress}. To create a pattern use
 * the {@link InetAddressPattern#from(String)} method, which will create a pattern given a
 * string conforming to one of the following formats.
 *
 * <dl>
 * <dt>InetAddress</dt>
 * <dd>A single InetAddress either in hostname or address format.
 * All formats supported by {@link InetAddress} are accepted.   Not ethat using hostname
 * matches may force domain lookups.  eg. "[::1]", "1.2.3.4", "::ffff:127.0.0.1"</dd>
 * <dt>InetAddress/CIDR</dt>
 * <dd>An InetAddress with a integer number of bits to indicate
 * the significant prefix. eg. "192.168.0.0/16" will match from "192.168.0.0" to
 * "192.168.255.255" </dd>
 * <dt>InetAddress-InetAddress</dt>
 * <dd>An inclusive range of InetAddresses.
 * eg. "[a000::1]-[afff::]", "192.168.128.0-192.168.128.255"</dd>
 * <dt>Legacy format</dt>
 * <dd>The legacy format used for IPv4 only.
 * eg. "10.10.10-14.0-128"</dd>
 * </dl>
 */
public abstract class InetAddressPattern implements Predicate<InetAddress>
{
    protected final String _pattern;

    public static InetAddressPattern from(String pattern)
    {
        if (pattern == null)
            return null;

        int slash = pattern.lastIndexOf('/');
        int dash = pattern.lastIndexOf('-');
        try
        {
            if (slash >= 0)
                return new CidrInetAddressRange(pattern, InetAddress.getByName(pattern.substring(0, slash).trim()), StringUtil.toInt(pattern, slash + 1));

            if (dash >= 0)
                return new MinMaxInetAddressRange(pattern, InetAddress.getByName(pattern.substring(0, dash).trim()), InetAddress.getByName(pattern.substring(dash + 1).trim()));

            return new SingletonInetAddressRange(pattern, InetAddress.getByName(pattern));
        }
        catch (Exception e)
        {
            try
            {
                if (slash < 0 && dash > 0)
                    return new LegacyInetAddressRange(pattern);
            }
            catch (Exception ex2)
            {
                e.addSuppressed(ex2);
            }
            throw new IllegalArgumentException("Bad pattern: " + pattern, e);
        }
    }

    public InetAddressPattern(String pattern)
    {
        _pattern = pattern;
    }

    @Override
    public String toString()
    {
        return _pattern;
    }

    static class SingletonInetAddressRange extends InetAddressPattern
    {
        final InetAddress _address;

        public SingletonInetAddressRange(String pattern, InetAddress address)
        {
            super(pattern);
            _address = address;
        }

        @Override
        public boolean test(InetAddress address)
        {
            return _address.equals(address);
        }
    }

    static class MinMaxInetAddressRange extends InetAddressPattern
    {
        final int[] _min;
        final int[] _max;

        public MinMaxInetAddressRange(String pattern, InetAddress min, InetAddress max)
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
        public boolean test(InetAddress address)
        {
            byte[] raw = address.getAddress();
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

    static class CidrInetAddressRange extends InetAddressPattern
    {
        final byte[] _raw;
        final int _octets;
        final int _mask;
        final int _masked;

        public CidrInetAddressRange(String pattern, InetAddress address, int cidr)
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
        public boolean test(InetAddress address)
        {
            byte[] raw = address.getAddress();
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

    static class LegacyInetAddressRange extends InetAddressPattern
    {
        int[] _min = new int[4];
        int[] _max = new int[4];

        public LegacyInetAddressRange(String pattern)
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
        public boolean test(InetAddress address)
        {
            byte[] raw = address.getAddress();
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
