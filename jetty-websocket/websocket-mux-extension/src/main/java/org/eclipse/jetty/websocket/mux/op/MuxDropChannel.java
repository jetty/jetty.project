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

package org.eclipse.jetty.websocket.mux.op;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.websocket.mux.MuxControlBlock;
import org.eclipse.jetty.websocket.mux.MuxOp;

public class MuxDropChannel implements MuxControlBlock
{
    /**
     * Outlined in <a href="https://tools.ietf.org/html/draft-ietf-hybi-websocket-multiplexing-05#section-9.4.1">Section 9.4.1. Drop Reason Codes</a>
     */
    public static enum Reason
    {
        // Normal Close : (1000-1999)
        NORMAL_CLOSURE(1000),

        // Failures in Physical Connection : (2000-2999)
        PHYSICAL_CONNECTION_FAILED(2000),
        INVALID_ENCAPSULATING_MESSAGE(2001),
        CHANNEL_ID_TRUNCATED(2002),
        ENCAPSULATED_FRAME_TRUNCATED(2003),
        UNKNOWN_MUX_CONTROL_OPC(2004),
        UNKNOWN_MUX_CONTROL_BLOCK(2005),
        CHANNEL_ALREADY_EXISTS(2006),
        NEW_CHANNEL_SLOT_VIOLATION(2007),
        NEW_CHANNEL_SLOT_OVERFLOW(2008),
        BAD_REQUEST(2009),
        UNKNOWN_REQUEST_ENCODING(2010),
        BAD_RESPONSE(2011),
        UNKNOWN_RESPONSE_ENCODING(2012),

        // Failures in Logical Connections : (3000-3999)
        LOGICAL_CHANNEL_FAILED(3000),
        SEND_QUOTA_VIOLATION(3005),
        SEND_QUOTA_OVERFLOW(3006),
        IDLE_TIMEOUT(3007),
        DROP_CHANNEL_ACK(3008),

        // Other Peer Actions : (4000-4999)
        USE_ANOTHER_PHYSICAL_CONNECTION(4001),
        BUSY(4002);

        private static final Map<Integer, Reason> codeMap;

        static
        {
            codeMap = new HashMap<>();
            for (Reason r : values())
            {
                codeMap.put(r.getValue(),r);
            }
        }

        public static Reason valueOf(int code)
        {
            return codeMap.get(code);
        }

        private final int code;

        private Reason(int code)
        {
            this.code = code;
        }

        public int getValue()
        {
            return code;
        }
    }

    public static MuxDropChannel parse(long channelId, ByteBuffer payload)
    {
        // TODO Auto-generated method stub
        return null;
    }

    private final long channelId;
    private final Reason code;
    private String phrase;
    private int rsv;

    /**
     * Normal Drop. no reason Phrase.
     * 
     * @param channelId
     *            the logical channel Id to perform drop against.
     */
    public MuxDropChannel(long channelId)
    {
        this(channelId,Reason.NORMAL_CLOSURE,null);
    }

    /**
     * Drop with reason code and optional phrase
     * 
     * @param channelId
     *            the logical channel Id to perform drop against.
     * @param code
     *            reason code
     * @param phrase
     *            optional human readable phrase
     */
    public MuxDropChannel(long channelId, int code, String phrase)
    {
        this(channelId, Reason.valueOf(code), phrase);
    }

    /**
     * Drop with reason code and optional phrase
     * 
     * @param channelId
     *            the logical channel Id to perform drop against.
     * @param code
     *            reason code
     * @param phrase
     *            optional human readable phrase
     */
    public MuxDropChannel(long channelId, Reason code, String phrase)
    {
        this.channelId = channelId;
        this.code = code;
        this.phrase = phrase;
    }

    public ByteBuffer asReasonBuffer()
    {
        // TODO: convert to reason buffer
        return null;
    }

    public long getChannelId()
    {
        return channelId;
    }

    public Reason getCode()
    {
        return code;
    }

    @Override
    public int getOpCode()
    {
        return MuxOp.DROP_CHANNEL;
    }

    public String getPhrase()
    {
        return phrase;
    }

    public int getRsv()
    {
        return rsv;
    }

    public void setRsv(int rsv)
    {
        this.rsv = rsv;
    }
}
