package org.eclipse.jetty.websocket.annotations;

/**
 * A representation of the methods available to call for a particular class.
 * <p>
 * This class used to cache the method lookups via the {@link EventMethodsCache}
 */
public class EventMethods
{
    private Class<?> pojoClass;
    private boolean isAnnotated = false;
    public EventMethod onConnect = EventMethod.NOOP;
    public EventMethod onClose = EventMethod.NOOP;
    public EventMethod onBinary = EventMethod.NOOP;
    public EventMethod onText = EventMethod.NOOP;
    public EventMethod onFrame = EventMethod.NOOP;
    public EventMethod onException = EventMethod.NOOP;

    public EventMethods(Class<?> pojoClass, boolean annotated)
    {
        this.pojoClass = pojoClass;
        this.isAnnotated = annotated;
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
