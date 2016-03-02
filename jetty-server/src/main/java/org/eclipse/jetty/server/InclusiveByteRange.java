//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.util.Enumeration;
import java.util.List;
import java.util.StringTokenizer;

import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/** Byte range inclusive of end points.
 * <PRE>
 * 
 *   parses the following types of byte ranges:
 * 
 *       bytes=100-499
 *       bytes=-300
 *       bytes=100-
 *       bytes=1-2,2-3,6-,-2
 *
 *   given an entity length, converts range to string
 * 
 *       bytes 100-499/500
 * 
 * </PRE>
 * 
 * Based on RFC2616 3.12, 14.16, 14.35.1, 14.35.2
 * <p>
 * And yes the spec does strangely say that while 10-20, is bytes 10 to 20 and 10- is bytes 10 until the end that -20 IS NOT bytes 0-20, but the last 20 bytes of the content.
 * 
 * @version $version$
 * 
 */
public class InclusiveByteRange 
{
    private static final Logger LOG = Log.getLogger(InclusiveByteRange.class);

    long first = 0;
    long last  = 0;    

    public InclusiveByteRange(long first, long last)
    {
        this.first = first;
        this.last = last;
    }
    
    public long getFirst()
    {
        return first;
    }

    public long getLast()
    {
        return last;
    }    


    
    /* ------------------------------------------------------------ */
    /** 
     * @param headers Enumeration of Range header fields.
     * @param size Size of the resource.
     * @return LazyList of satisfiable ranges
     */
    public static List<InclusiveByteRange> satisfiableRanges(Enumeration<String> headers, long size)
    {
        Object satRanges=null;
        
        // walk through all Range headers
    headers:
        while (headers.hasMoreElements())
        {
            String header = headers.nextElement();
            StringTokenizer tok = new StringTokenizer(header,"=,",false);
            String t=null;
            try
            {
                // read all byte ranges for this header 
                while (tok.hasMoreTokens())
                {
                    try
                    {
                        t = tok.nextToken().trim();

                        long first = -1;
                        long last = -1;
                        int d = t.indexOf('-');
                        if (d < 0 || t.indexOf("-",d + 1) >= 0)
                        {
                            if ("bytes".equals(t))
                                continue;
                            LOG.warn("Bad range format: {}",t);
                            continue headers;
                        }
                        else if (d == 0)
                        {
                            if (d + 1 < t.length())
                                last = Long.parseLong(t.substring(d + 1).trim());
                            else
                            {
                                LOG.warn("Bad range format: {}",t);
                                continue;
                            }
                        }
                        else if (d + 1 < t.length())
                        {
                            first = Long.parseLong(t.substring(0,d).trim());
                            last = Long.parseLong(t.substring(d + 1).trim());
                        }
                        else
                            first = Long.parseLong(t.substring(0,d).trim());

                        if (first == -1 && last == -1)
                            continue headers;

                        if (first != -1 && last != -1 && (first > last))
                            continue headers;

                        if (first < size)
                        {
                            InclusiveByteRange range = new InclusiveByteRange(first,last);
                            satRanges = LazyList.add(satRanges,range);
                        }
                    }
                    catch (NumberFormatException e)
                    {
                        LOG.warn("Bad range format: {}",t);
                        LOG.ignore(e);
                        continue;
                    }
                }
            }
            catch(Exception e)
            {
                LOG.warn("Bad range format: {}",t);
                LOG.ignore(e);
            }    
        }
        return LazyList.getList(satRanges,true);
    }

    /* ------------------------------------------------------------ */
    public long getFirst(long size)
    {
        if (first<0)
        {
            long tf=size-last;
            if (tf<0)
                tf=0;
            return tf;
        }
        return first;
    }
    
    /* ------------------------------------------------------------ */
    public long getLast(long size)
    {
        if (first<0)
            return size-1;
        
        if (last<0 ||last>=size)
            return size-1;
        return last;
    }
    
    /* ------------------------------------------------------------ */
    public long getSize(long size)
    {
        return getLast(size)-getFirst(size)+1;
    }


    /* ------------------------------------------------------------ */
    public String toHeaderRangeString(long size)
    {
        StringBuilder sb = new StringBuilder(40);
        sb.append("bytes ");
        sb.append(getFirst(size));
        sb.append('-');
        sb.append(getLast(size));
        sb.append("/");
        sb.append(size);
        return sb.toString();
    }

    /* ------------------------------------------------------------ */
    public static String to416HeaderRangeString(long size)
    {
        StringBuilder sb = new StringBuilder(40);
        sb.append("bytes */");
        sb.append(size);
        return sb.toString();
    }


    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder(60);
        sb.append(Long.toString(first));
        sb.append(":");
        sb.append(Long.toString(last));
        return sb.toString();
    }


}



