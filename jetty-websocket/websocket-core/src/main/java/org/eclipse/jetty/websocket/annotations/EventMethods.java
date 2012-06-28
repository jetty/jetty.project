package org.eclipse.jetty.websocket.annotations;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.InvalidWebSocketException;
import org.eclipse.jetty.websocket.frames.BaseFrame;

/**
 * A representation of the methods available to call for a particular class.
 * <p>
 * This class used to cache the method lookups via the {@link EventMethodsCache}
 */
public class EventMethods
{
    private Class<?> pojoClass;
    private boolean isAnnotated = false;
    public EventMethod onConnect = null;
    public EventMethod onClose = null;
    public EventMethod onBinary = null;
    public EventMethod onText = null;
    public EventMethod onException = null;

    // special case, multiple methods allowed
    private Map<Class<? extends BaseFrame>, EventMethod> onFrames = new HashMap<Class<? extends BaseFrame>, EventMethod>();

    public EventMethods(Class<?> pojoClass, boolean annotated)
    {
        this.pojoClass = pojoClass;
        this.isAnnotated = annotated;
    }

    public void addOnFrame(EventMethod eventMethod)
    {
        Class<?> paramTypes[] = eventMethod.getParamTypes();
        @SuppressWarnings("unchecked")
        Class<? extends BaseFrame> frameType = (Class<? extends BaseFrame>)((paramTypes.length == 1)?paramTypes[0]:paramTypes[1]);

        if (onFrames.containsKey(frameType))
        {
            // Attempt to add duplicate frame type (a no-no)
            StringBuilder err = new StringBuilder();
            err.append("Duplicate Frame Type declaration on ");
            err.append(eventMethod.getMethod());
            err.append(StringUtil.__LINE_SEPARATOR);

            EventMethod dup = onFrames.get(frameType);
            err.append("Type ").append(frameType.getSimpleName()).append(" previously declared at ");
            err.append(dup.getMethod());

            throw new InvalidWebSocketException(err.toString());
        }

        onFrames.put(frameType,eventMethod);
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
        EventMethods other = (EventMethods)obj;
        if (pojoClass == null)
        {
            if (other.pojoClass != null)
            {
                return false;
            }
        }
        else if (!pojoClass.getName().equals(other.pojoClass.getName()))
        {
            return false;
        }
        return true;
    }

    public EventMethod getOnFrame(Class<? extends BaseFrame> frameType)
    {
        return onFrames.get(frameType);
    }

    public Map<Class<? extends BaseFrame>, EventMethod> getOnFrames()
    {
        return onFrames;
    }

    public Class<?> getPojoClass()
    {
        return pojoClass;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((pojoClass == null)?0:pojoClass.getName().hashCode());
        return result;
    }

    public boolean isAnnotated()
    {
        return isAnnotated;
    }

}
