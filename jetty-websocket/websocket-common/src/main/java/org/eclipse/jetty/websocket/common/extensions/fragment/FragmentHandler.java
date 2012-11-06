package org.eclipse.jetty.websocket.common.extensions.fragment;

import java.nio.ByteBuffer;

import javax.net.websocket.extensions.FrameHandler;

import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractJettyFrameHandler;

/**
 * Handler to break apart the frames into multiple smaller frames.
 */
public class FragmentHandler extends AbstractJettyFrameHandler
{
    private final int maxLength;

    /**
     * @param fragmentExtension
     */
    public FragmentHandler(FrameHandler nextHandler, int maxLength)
    {
        super(nextHandler);
        this.maxLength = maxLength;
    }

    @Override
    public void handleJettyFrame(WebSocketFrame frame)
    {
        if (frame.getType().isControl())
        {
            // Cannot fragment Control Frames
            nextJettyHandler(frame);
            return;
        }

        int length = frame.getPayloadLength();

        byte opcode = frame.getType().getOpCode(); // original opcode
        ByteBuffer payload = frame.getPayload().slice();
        int originalLimit = payload.limit();
        int currentPosition = payload.position();

        if (maxLength <= 0)
        {
            // output original frame
            nextJettyHandler(frame);
            return;
        }

        boolean continuation = false;

        // break apart payload based on maxLength rules
        while (length > maxLength)
        {
            WebSocketFrame frag = new WebSocketFrame(frame);
            frag.setOpCode(opcode);
            frag.setFin(false); // always false here
            frag.setContinuation(continuation);
            payload.position(currentPosition);
            payload.limit(Math.min(payload.position() + maxLength,originalLimit));
            frag.setPayload(payload);

            nextJettyHandler(frag);

            length -= maxLength;
            opcode = OpCode.CONTINUATION;
            continuation = true;
            currentPosition = payload.limit();
        }

        // write remaining
        WebSocketFrame frag = new WebSocketFrame(frame);
        frag.setOpCode(opcode);
        frag.setFin(frame.isFin()); // use original fin
        frag.setContinuation(continuation);
        payload.position(currentPosition);
        payload.limit(originalLimit);
        frag.setPayload(payload);

        nextJettyHandler(frag);
    }
}