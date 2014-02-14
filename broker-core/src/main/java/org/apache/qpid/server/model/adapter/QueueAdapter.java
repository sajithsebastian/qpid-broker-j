/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.model.adapter;

import java.lang.reflect.Type;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.qpid.server.binding.Binding;
import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.message.MessageSource;
import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.model.ConfiguredObjectFinder;
import org.apache.qpid.server.model.Exchange;
import org.apache.qpid.server.model.IllegalStateTransitionException;
import org.apache.qpid.server.model.LifetimePolicy;
import org.apache.qpid.server.model.Queue;
import org.apache.qpid.server.model.QueueNotificationListener;
import org.apache.qpid.server.model.State;
import org.apache.qpid.server.model.Statistics;
import org.apache.qpid.server.protocol.AMQConnectionModel;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.queue.*;
import org.apache.qpid.server.security.QpidSecurityException;
import org.apache.qpid.server.store.DurableConfigurationStoreHelper;
import org.apache.qpid.server.consumer.Consumer;
import org.apache.qpid.server.util.MapValueConverter;

final class QueueAdapter<Q extends AMQQueue<?,Q,?>> extends AbstractAdapter implements Queue,
                                                            MessageSource.ConsumerRegistrationListener<Q>,
                                                            AMQQueue.NotificationListener
{
    @SuppressWarnings("serial")
    static final Map<String, Type> ATTRIBUTE_TYPES = Collections.unmodifiableMap(new HashMap<String, Type>(){{
        put(ALERT_REPEAT_GAP, Long.class);
        put(ALERT_THRESHOLD_MESSAGE_AGE, Long.class);
        put(ALERT_THRESHOLD_MESSAGE_SIZE, Long.class);
        put(ALERT_THRESHOLD_QUEUE_DEPTH_MESSAGES, Long.class);
        put(ALERT_THRESHOLD_QUEUE_DEPTH_BYTES, Long.class);
        put(QUEUE_FLOW_CONTROL_SIZE_BYTES, Long.class);
        put(QUEUE_FLOW_RESUME_SIZE_BYTES, Long.class);
        put(MAXIMUM_DELIVERY_ATTEMPTS, Integer.class);
        put(EXCLUSIVE, Boolean.class);
        put(DESCRIPTION, String.class);
    }});

    private final AMQQueue<?,Q,?> _queue;

    private final Map<Binding, BindingAdapter> _bindingAdapters =
            new HashMap<Binding, BindingAdapter>();
    private final Map<Consumer, ConsumerAdapter> _consumerAdapters =
            new HashMap<Consumer, ConsumerAdapter>();


    private final VirtualHostAdapter _vhost;
    private QueueStatisticsAdapter _statistics;
    private QueueNotificationListener _queueNotificationListener;

    public QueueAdapter(final VirtualHostAdapter virtualHostAdapter, final AMQQueue<?,Q,?> queue)
    {
        super(queue.getId(), virtualHostAdapter.getTaskExecutor());
        _vhost = virtualHostAdapter;
        addParent(org.apache.qpid.server.model.VirtualHost.class, virtualHostAdapter);

        _queue = queue;
        _queue.addConsumerRegistrationListener(this);
        populateConsumers();
        _statistics = new QueueStatisticsAdapter(queue);
        _queue.setNotificationListener(this);
    }

    /**
     * Helper method to retrieve the SessionAdapter keyed by the AMQSessionModel.
     * This method first finds the ConnectionAdapter associated with the Session from this QueueAdapter's parent vhost
     * then it does a lookup on that to find the SessionAdapter keyed by the requested AMQSessionModel instance.
     * @param session the AMQSessionModel used to index the SessionAdapter.
     * @return the requested SessionAdapter or null if it can't be found.
     */
    private SessionAdapter getSessionAdapter(AMQSessionModel session)
    {
        // Retrieve the ConnectionModel associated with the SessionModel as a key to lookup the ConnectionAdapter.
        AMQConnectionModel connectionKey = session.getConnectionModel();

        // Lookup the ConnectionAdapter, from which we should be able to retrieve the SessionAdapter we really want.
        ConnectionAdapter connectionAdapter = _vhost.getConnectionAdapter(connectionKey);
        if (connectionAdapter == null)
        {
            return null; // If we can't find an associated ConnectionAdapter the SessionAdapter is a lost cause.
        }
        else
        {   // With a good ConnectionAdapter we can finally try to find the SessionAdapter we are actually looking for.
            SessionAdapter sessionAdapter = connectionAdapter.getSessionAdapter(session);
            if (sessionAdapter == null)
            {
                return null; // If the SessionAdapter isn't associated with the selected ConnectionAdapter give up.
            }
            else
            {
                return sessionAdapter;
            }
        }
    }

    private void populateConsumers()
    {
        Collection<? extends Consumer> actualConsumers = _queue.getConsumers();

        synchronized (_consumerAdapters)
        {
            for(Consumer consumer : actualConsumers)
            {
                if(!_consumerAdapters.containsKey(consumer))
                {
                    SessionAdapter sessionAdapter = getSessionAdapter(consumer.getSessionModel());
                    ConsumerAdapter adapter = new ConsumerAdapter(this, sessionAdapter, consumer);
                    _consumerAdapters.put(consumer, adapter);
                    if (sessionAdapter != null)
                    { // Register ConsumerAdapter with the SessionAdapter.
                        sessionAdapter.consumerRegistered(consumer, adapter);
                    }
                }
            }
        }
    }

    public Collection<org.apache.qpid.server.model.Binding> getBindings()
    {
        synchronized (_bindingAdapters)
        {
            return new ArrayList<org.apache.qpid.server.model.Binding>(_bindingAdapters.values());
        }
    }

    public Collection<org.apache.qpid.server.model.Consumer> getConsumers()
    {
        synchronized (_consumerAdapters)
        {
            return new ArrayList<org.apache.qpid.server.model.Consumer>(_consumerAdapters.values());
        }

    }

    public void visit(final QueueEntryVisitor visitor)
    {
        _queue.visit(visitor);
    }

    public void delete()
    {
        try
        {
            _queue.getVirtualHost().removeQueue(_queue);
        }
        catch (QpidSecurityException e)
        {
            throw new AccessControlException(e.toString());
        }
    }

    public String getName()
    {
        return _queue.getName();
    }

    public String setName(final String currentName, final String desiredName)
            throws IllegalStateException, AccessControlException
    {
        return null;  //TODO
    }

    public State getActualState()
    {
        return null;  //TODO
    }

    public boolean isDurable()
    {
        return _queue.isDurable();
    }

    public void setDurable(final boolean durable)
            throws IllegalStateException, AccessControlException, IllegalArgumentException
    {
        //TODO
    }

    public LifetimePolicy getLifetimePolicy()
    {
        return _queue.isAutoDelete() ? LifetimePolicy.AUTO_DELETE : LifetimePolicy.PERMANENT;
    }

    public LifetimePolicy setLifetimePolicy(final LifetimePolicy expected, final LifetimePolicy desired)
            throws IllegalStateException, AccessControlException, IllegalArgumentException
    {
        return null;  //TODO
    }

    public long getTimeToLive()
    {
        return 0;  //TODO
    }

    public long setTimeToLive(final long expected, final long desired)
            throws IllegalStateException, AccessControlException, IllegalArgumentException
    {
        return 0;  //TODO
    }


    @Override
    public Collection<String> getAttributeNames()
    {
        return Queue.AVAILABLE_ATTRIBUTES;
    }

    @Override
    public boolean changeAttribute(String name, Object expected, Object desired) throws IllegalStateException, AccessControlException, IllegalArgumentException
    {
        try
        {
            if(ALERT_REPEAT_GAP.equals(name))
            {
                _queue.setMinimumAlertRepeatGap((Long)desired);
                return true;
            }
            else if(ALERT_THRESHOLD_MESSAGE_AGE.equals(name))
            {
                _queue.setMaximumMessageAge((Long)desired);
                return true;
            }
            else if(ALERT_THRESHOLD_MESSAGE_SIZE.equals(name))
            {
                _queue.setMaximumMessageSize((Long)desired);
                return true;
            }
            else if(ALERT_THRESHOLD_QUEUE_DEPTH_BYTES.equals(name))
            {
                _queue.setMaximumQueueDepth((Long)desired);
                return true;
            }
            else if(ALERT_THRESHOLD_QUEUE_DEPTH_MESSAGES.equals(name))
            {
                _queue.setMaximumMessageCount((Long)desired);
                return true;
            }
            else if(ALTERNATE_EXCHANGE.equals(name))
            {
                // In future we may want to accept a UUID as an alternative way to identifying the exchange
                ExchangeAdapter alternateExchange = (ExchangeAdapter) desired;
                _queue.setAlternateExchange(alternateExchange == null ? null : alternateExchange.getExchange());
                return true;
            }
            else if(EXCLUSIVE.equals(name))
            {
                Boolean exclusiveFlag = (Boolean) desired;
                _queue.setExclusive(exclusiveFlag);
                return true;
            }
            else if(MESSAGE_GROUP_KEY.equals(name))
            {
                // TODO
            }
            else if(MESSAGE_GROUP_SHARED_GROUPS.equals(name))
            {
                // TODO
            }
            else if(LVQ_KEY.equals(name))
            {
                // TODO
            }
            else if(MAXIMUM_DELIVERY_ATTEMPTS.equals(name))
            {
                _queue.setMaximumDeliveryCount((Integer)desired);
                return true;
            }
            else if(NO_LOCAL.equals(name))
            {
                // TODO
            }
            else if(OWNER.equals(name))
            {
                // TODO
            }
            else if(QUEUE_FLOW_CONTROL_SIZE_BYTES.equals(name))
            {
                _queue.setCapacity((Long)desired);
                return true;
            }
            else if(QUEUE_FLOW_RESUME_SIZE_BYTES.equals(name))
            {
                _queue.setFlowResumeCapacity((Long)desired);
                return true;
            }
            else if(QUEUE_FLOW_STOPPED.equals(name))
            {
                // TODO
            }
            else if(SORT_KEY.equals(name))
            {
                // TODO
            }
            else if(QUEUE_TYPE.equals(name))
            {
                // TODO
            }
            else if (DESCRIPTION.equals(name))
            {
                _queue.setDescription((String) desired);
                return true;
            }

            return super.changeAttribute(name, expected, desired);
        }
        finally
        {
            if (_queue.isDurable())
            {
                DurableConfigurationStoreHelper.updateQueue(_queue.getVirtualHost().getDurableConfigurationStore(),
                        _queue);
            }
        }
    }

    @Override
    public Object getAttribute(String name)
    {

        if(ALERT_REPEAT_GAP.equals(name))
        {
            return _queue.getMinimumAlertRepeatGap();
        }
        else if(ALERT_THRESHOLD_MESSAGE_AGE.equals(name))
        {
            return _queue.getMaximumMessageAge();
        }
        else if(ALERT_THRESHOLD_MESSAGE_SIZE.equals(name))
        {
            return _queue.getMaximumMessageSize();
        }
        else if(ALERT_THRESHOLD_QUEUE_DEPTH_BYTES.equals(name))
        {
            return _queue.getMaximumQueueDepth();
        }
        else if(ALERT_THRESHOLD_QUEUE_DEPTH_MESSAGES.equals(name))
        {
            return _queue.getMaximumMessageCount();
        }
        else if(ALTERNATE_EXCHANGE.equals(name))
        {
            org.apache.qpid.server.exchange.Exchange alternateExchange = _queue.getAlternateExchange();
            return alternateExchange == null ? null :
                                               ConfiguredObjectFinder.findConfiguredObjectByName(_vhost.getExchanges(),
                                                                                                 alternateExchange.getName());
        }
        else if(EXCLUSIVE.equals(name))
        {
            return _queue.isExclusive();
        }
        else if(MESSAGE_GROUP_KEY.equals(name))
        {
            return _queue.getAttribute(MESSAGE_GROUP_KEY);
        }
        else if(MESSAGE_GROUP_SHARED_GROUPS.equals(name))
        {
            //We only return the boolean value if message groups are actually in use
            return getAttribute(MESSAGE_GROUP_KEY) == null ? null : _queue.getAttribute(MESSAGE_GROUP_SHARED_GROUPS);
        }
        else if(LVQ_KEY.equals(name))
        {
            AMQQueue queue = _queue;
            if(queue instanceof ConflationQueue)
            {
                return ((ConflationQueue)queue).getConflationKey();
            }
        }
        else if(MAXIMUM_DELIVERY_ATTEMPTS.equals(name))
        {
            return _queue.getMaximumDeliveryCount();
        }
        else if(NO_LOCAL.equals(name))
        {
            // TODO
        }
        else if(OWNER.equals(name))
        {
            return _queue.getOwner();
        }
        else if(QUEUE_FLOW_CONTROL_SIZE_BYTES.equals(name))
        {
            return _queue.getCapacity();
        }
        else if(QUEUE_FLOW_RESUME_SIZE_BYTES.equals(name))
        {
            return _queue.getFlowResumeCapacity();
        }
        else if(QUEUE_FLOW_STOPPED.equals(name))
        {
            return _queue.isOverfull();
        }
        else if(SORT_KEY.equals(name))
        {
            AMQQueue queue = _queue;
            if(queue instanceof SortedQueue)
            {
                return ((SortedQueue)queue).getSortedPropertyName();
            }
        }
        else if(QUEUE_TYPE.equals(name))
        {
            AMQQueue queue = _queue;
            if(queue instanceof SortedQueue)
            {
                return "sorted";
            }
            if(queue instanceof ConflationQueue)
            {
                return "lvq";
            }
            if(queue instanceof PriorityQueue)
            {
                return "priority";
            }
            return "standard";
        }
        else if(CREATED.equals(name))
        {
            // TODO
        }
        else if(DURABLE.equals(name))
        {
            return _queue.isDurable();
        }
        else if(ID.equals(name))
        {
            return getId();
        }
        else if(LIFETIME_POLICY.equals(name))
        {
            return _queue.isAutoDelete() ? LifetimePolicy.AUTO_DELETE : LifetimePolicy.PERMANENT;
        }
        else if(NAME.equals(name))
        {
            return _queue.getName();
        }
        else if(STATE.equals(name))
        {
            return State.ACTIVE; // TODO
        }
        else if(TIME_TO_LIVE.equals(name))
        {
            // TODO
        }
        else if(UPDATED.equals(name))
        {
            // TODO
        }
        else if (DESCRIPTION.equals(name))
        {
            return _queue.getDescription();
        }
        else if(PRIORITIES.equals(name))
        {
            AMQQueue queue = _queue;
            if(queue instanceof PriorityQueue)
            {
                return ((PriorityQueue)queue).getPriorities();
            }
        }
        return super.getAttribute(name);
    }

    public Statistics getStatistics()
    {
        return _statistics;
    }

    @Override
    public <C extends ConfiguredObject> Collection<C> getChildren(Class<C> clazz)
    {
        if(clazz == org.apache.qpid.server.model.Consumer.class)
        {
            return (Collection<C>) getConsumers();
        }
        else if(clazz == org.apache.qpid.server.model.Binding.class)
        {
            return (Collection<C>) getBindings();
        }
        else
        {
            return Collections.emptySet();
        }
    }

    public org.apache.qpid.server.model.Binding createBinding(Exchange exchange, Map<String, Object> attributes)
            throws AccessControlException, IllegalStateException
    {
        attributes = new HashMap<String, Object>(attributes);
        String bindingKey = MapValueConverter.getStringAttribute(org.apache.qpid.server.model.Binding.NAME, attributes, "");
        Map<String, Object> bindingArgs = MapValueConverter.getMapAttribute(org.apache.qpid.server.model.Binding.ARGUMENTS, attributes, Collections.<String,Object>emptyMap());

        attributes.remove(org.apache.qpid.server.model.Binding.NAME);
        attributes.remove(org.apache.qpid.server.model.Binding.ARGUMENTS);

        return exchange.createBinding(bindingKey, this, bindingArgs, attributes);

    }



    @Override
    public <C extends ConfiguredObject> C addChild(Class<C> childClass, Map<String, Object> attributes, ConfiguredObject... otherParents)
    {
        if(childClass == org.apache.qpid.server.model.Binding.class)
        {
            if(otherParents != null && otherParents.length == 1 && otherParents[0] instanceof Exchange)
            {
                Exchange exchange = (Exchange) otherParents[0];
                if(exchange.getParent(org.apache.qpid.server.model.VirtualHost.class) == getParent(org.apache.qpid.server.model.VirtualHost.class))
                {
                    return (C) createBinding(exchange, attributes);
                }
                else
                {
                    throw new IllegalArgumentException("Queue and Exchange parents of a binding must be on same virtual host");
                }
            }
            else
            {
                throw new IllegalArgumentException("Other parent must be an exchange");
            }
        }
        else
        {
            throw new IllegalArgumentException();
        }
    }

    void bindingRegistered(Binding binding, BindingAdapter adapter)
    {
        synchronized (_bindingAdapters)
        {
            _bindingAdapters.put(binding, adapter);
        }
        childAdded(adapter);
    }

    void bindingUnregistered(Binding binding)
    {
        BindingAdapter adapter = null;
        synchronized (_bindingAdapters)
        {
            adapter = _bindingAdapters.remove(binding);
        }
        if(adapter != null)
        {
            childRemoved(adapter);
        }
    }

    AMQQueue getAMQQueue()
    {
        return _queue;
    }

    public void consumerAdded(final AMQQueue queue, final Consumer consumer)
    {
        ConsumerAdapter adapter = null;
        synchronized (_consumerAdapters)
        {
            if(!_consumerAdapters.containsKey(consumer))
            {
                SessionAdapter sessionAdapter = getSessionAdapter(consumer.getSessionModel());
                adapter = new ConsumerAdapter(this, sessionAdapter, consumer);
                _consumerAdapters.put(consumer, adapter);
                if (sessionAdapter != null)
                { // Register ConsumerAdapter with the SessionAdapter.
                    sessionAdapter.consumerRegistered(consumer, adapter);
                }
            }
        }
        if(adapter != null)
        {
            childAdded(adapter);
        }
    }

    public void consumerRemoved(final AMQQueue queue, final Consumer consumer)
    {
        ConsumerAdapter adapter = null;

        synchronized (_consumerAdapters)
        {
            adapter = _consumerAdapters.remove(consumer);
        }
        if(adapter != null)
        {
            SessionAdapter sessionAdapter = getSessionAdapter(consumer.getSessionModel());
            if (sessionAdapter != null)
            { // Unregister ConsumerAdapter with the SessionAdapter.
                sessionAdapter.consumerUnregistered(consumer);
            }
            childRemoved(adapter);
        }
    }

    VirtualHostAdapter getVirtualHost()
    {
        return _vhost;
    }


    private static class QueueStatisticsAdapter implements Statistics
    {

        private final AMQQueue _queue;

        public QueueStatisticsAdapter(AMQQueue queue)
        {
            _queue = queue;
        }

        public Collection<String> getStatisticNames()
        {
            return Queue.AVAILABLE_STATISTICS;
        }

        public Object getStatistic(String name)
        {
            if(BINDING_COUNT.equals(name))
            {
                return _queue.getBindingCount();
            }
            else if(CONSUMER_COUNT.equals(name))
            {
                return _queue.getConsumerCount();
            }
            else if(CONSUMER_COUNT_WITH_CREDIT.equals(name))
            {
                return _queue.getActiveConsumerCount();
            }
            else if(DISCARDS_TTL_BYTES.equals(name))
            {
                return null; // TODO
            }
            else if(DISCARDS_TTL_MESSAGES.equals(name))
            {
                return null; // TODO
            }
            else if(PERSISTENT_DEQUEUED_BYTES.equals(name))
            {
                return _queue.getPersistentByteDequeues();
            }
            else if(PERSISTENT_DEQUEUED_MESSAGES.equals(name))
            {
                return _queue.getPersistentMsgDequeues();
            }
            else if(PERSISTENT_ENQUEUED_BYTES.equals(name))
            {
                return _queue.getPersistentByteEnqueues();
            }
            else if(PERSISTENT_ENQUEUED_MESSAGES.equals(name))
            {
                return _queue.getPersistentMsgEnqueues();
            }
            else if(QUEUE_DEPTH_BYTES.equals(name))
            {
                return _queue.getQueueDepth();
            }
            else if(QUEUE_DEPTH_MESSAGES.equals(name))
            {
                return _queue.getMessageCount();
            }
            else if(STATE_CHANGED.equals(name))
            {
                return null; // TODO
            }
            else if(TOTAL_DEQUEUED_BYTES.equals(name))
            {
                return _queue.getTotalDequeueSize();
            }
            else if(TOTAL_DEQUEUED_MESSAGES.equals(name))
            {
                return _queue.getTotalDequeueCount();
            }
            else if(TOTAL_ENQUEUED_BYTES.equals(name))
            {
                return _queue.getTotalEnqueueSize();
            }
            else if(TOTAL_ENQUEUED_MESSAGES.equals(name))
            {
                return _queue.getTotalEnqueueCount();
            }
            else if(UNACKNOWLEDGED_BYTES.equals(name))
            {
                return _queue.getUnackedMessageBytes();
            }
            else if(UNACKNOWLEDGED_MESSAGES.equals(name))
            {
                return _queue.getUnackedMessageCount();
            }

            return null;
        }
    }

    @Override
    public void setNotificationListener(QueueNotificationListener listener)
    {
        _queueNotificationListener = listener;
    }

    @Override
    public void notifyClients(NotificationCheck notification, AMQQueue queue, String notificationMsg)
    {
        QueueNotificationListener listener = _queueNotificationListener;
        if(listener !=  null)
        {
            listener.notifyClients(notification, this, notificationMsg);
        }
    }

    @Override
    protected boolean setState(State currentState, State desiredState) throws IllegalStateTransitionException,
            AccessControlException
    {
        if (desiredState == State.DELETED)
        {
            delete();
            return true;
        }
        return false;
    }

    @Override
    protected void authoriseSetAttribute(String name, Object expected, Object desired) throws AccessControlException
    {
        if (!_vhost.getSecurityManager().authoriseUpdate(_queue))
        {
            throw new AccessControlException("Setting of queue attribute is denied");
        }
    }

    @Override
    protected void authoriseSetAttributes(Map<String, Object> attributes) throws AccessControlException
    {
        if (!_vhost.getSecurityManager().authoriseUpdate(_queue))
        {
            throw new AccessControlException("Setting of queue attributes is denied");
        }
    }

    @Override
    protected void changeAttributes(final Map<String, Object> attributes)
    {
        Map<String, Object> convertedAttributes = MapValueConverter.convert(attributes, ATTRIBUTE_TYPES);
        validateAttributes(convertedAttributes);

        super.changeAttributes(convertedAttributes);
    }

    private void validateAttributes(Map<String, Object> convertedAttributes)
    {
        Long queueFlowControlSize = (Long) convertedAttributes.get(QUEUE_FLOW_CONTROL_SIZE_BYTES);
        Long queueFlowControlResumeSize = (Long) convertedAttributes.get(QUEUE_FLOW_RESUME_SIZE_BYTES);
        if (queueFlowControlSize != null || queueFlowControlResumeSize != null )
        {
            if (queueFlowControlSize == null)
            {
                queueFlowControlSize = (Long)getAttribute(QUEUE_FLOW_CONTROL_SIZE_BYTES);
            }
            if (queueFlowControlResumeSize == null)
            {
                queueFlowControlResumeSize = (Long)getAttribute(QUEUE_FLOW_RESUME_SIZE_BYTES);
            }
            if (queueFlowControlResumeSize > queueFlowControlSize)
            {
                throw new IllegalConfigurationException("Flow resume size can't be greater than flow control size");
            }
        }
        for (Map.Entry<String, Object> entry: convertedAttributes.entrySet())
        {
            Object value = entry.getValue();
            if (value instanceof Number && ((Number)value).longValue() < 0)
            {
                throw new IllegalConfigurationException("Only positive integer value can be specified for the attribute "
                        + entry.getKey());
            }
        }
    }

}
