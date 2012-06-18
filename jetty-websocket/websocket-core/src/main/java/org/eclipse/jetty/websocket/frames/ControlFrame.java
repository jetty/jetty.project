package org.eclipse.jetty.websocket.frames;

/**
 * Base class for all <a href="https://tools.ietf.org/html/rfc6455#section-5.5">control frames</a>.
 * <p>
 * TODO: investigate as candidate for removal.
 */
public abstract class ControlFrame extends BaseFrame
{
    private final ControlFrameType type;

    public ControlFrame(ControlFrameType type)
    {
        this.type = type;
    }

    public ControlFrameType getType()
    {
        return type;
    }

    @Override
    public String toString()
    {
        return String.format("%s frame v%s",getType());
    }
}
