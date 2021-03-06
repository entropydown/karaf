/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.util.tracker;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseActivator implements BundleActivator, SingleServiceTracker.SingleServiceListener {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected BundleContext bundleContext;

    protected ExecutorService executor = new ThreadPoolExecutor(0, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>());
    private AtomicBoolean scheduled = new AtomicBoolean();

    private List<ServiceRegistration> registrations;
    private Map<String, SingleServiceTracker> trackers = new HashMap<String, SingleServiceTracker>();
    private ServiceRegistration managedServiceRegistration;
    private Dictionary<String, ?> configuration;

    @Override
    public void start(BundleContext context) throws Exception {
        bundleContext = context;
        scheduled.set(true);
        doOpen();
        scheduled.set(false);
        if (managedServiceRegistration == null && trackers.isEmpty()) {
            try {
                doStart();
            } catch (Exception e) {
                doStop();
            }
        } else {
            reconfigure();
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        scheduled.set(true);
        doClose();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        doStop();
    }

    protected void doOpen() throws Exception {
    }

    protected void doClose() {
        if (managedServiceRegistration != null) {
            managedServiceRegistration.unregister();
        }
        for (SingleServiceTracker tracker : trackers.values()) {
            tracker.close();
        }
    }

    protected void doStart() throws Exception {
    }

    protected void doStop() {
        if (registrations != null) {
            for (ServiceRegistration reg : registrations) {
                reg.unregister();
            }
            registrations = null;
        }
    }

    /**
     * Called in {@link #doOpen()}
     */
    protected void manage(String pid) {
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put(Constants.SERVICE_PID, pid);
        managedServiceRegistration = bundleContext.registerService(
                "org.osgi.service.cm.ManagedService", this, props);
    }

    public void updated(Dictionary<String, ?> properties) {
        this.configuration = properties;
        reconfigure();
    }

    /**
     * Called in {@link #doStart()}
     */
    protected int getInt(String key, int def) {
        if (configuration != null) {
            Object val = configuration.get(key);
            if (val instanceof Number) {
                return ((Number) val).intValue();
            } else if (val != null) {
                return Integer.parseInt(val.toString());
            }
        }
        return def;
    }

    /**
     * Called in {@link #doStart()}
     */
    protected boolean getBoolean(String key, boolean def) {
        if (configuration != null) {
            Object val = configuration.get(key);
            if (val instanceof Boolean) {
                return (Boolean) val;
            } else if (val != null) {
                return Boolean.parseBoolean(val.toString());
            }
        }
        return def;
    }

    /**
     * Called in {@link #doStart()}
     */
    protected long getLong(String key, long def) {
        if (configuration != null) {
            Object val = configuration.get(key);
            if (val instanceof Number) {
                return ((Number) val).longValue();
            } else if (val != null) {
                return Long.parseLong(val.toString());
            }
        }
        return def;
    }

    /**
     * Called in {@link #doStart()}
     */
    protected String getString(String key, String def) {
        if (configuration != null) {
            Object val = configuration.get(key);
            if (val != null) {
                return val.toString();
            }
        }
        return def;
    }

    @Override
    public void serviceFound() {
        reconfigure();
    }

    @Override
    public void serviceLost() {
        reconfigure();
    }

    @Override
    public void serviceReplaced() {
        reconfigure();
    }

    protected void reconfigure() {
        if (scheduled.compareAndSet(false, true)) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    scheduled.set(false);
                    doStop();
                    try {
                        doStart();
                    } catch (Exception e) {
                        logger.warn("Error starting activator", e);
                        doStop();
                    }
                }
            });
        }
    }

    /**
     * Called in {@link #doOpen()}
     */
    protected void trackService(Class clazz) {
        if (!trackers.containsKey(clazz.getName())) {
            SingleServiceTracker tracker = new SingleServiceTracker(bundleContext, clazz, this);
            tracker.open();
            trackers.put(clazz.getName(), tracker);
        }
    }

    /**
     * Called in {@link #doOpen()}
     */
    protected void trackService(String clazz) {
        if (!trackers.containsKey(clazz)) {
            SingleServiceTracker tracker = new SingleServiceTracker(bundleContext, clazz, this);
            tracker.open();
            trackers.put(clazz, tracker);
        }
    }

    /**
     * Called in {@link #doOpen()}
     */
    protected void trackService(Class clazz, String filter) throws InvalidSyntaxException {
        if (!trackers.containsKey(clazz.getName())) {
            SingleServiceTracker tracker = new SingleServiceTracker(bundleContext, clazz, filter, this);
            tracker.open();
            trackers.put(clazz.getName(), tracker);
        }
    }

    /**
     * Called in {@link #doStart()}
     */
    protected <T> T getTrackedService(Class<T> clazz) {
        SingleServiceTracker tracker = trackers.get(clazz.getName());
        if (tracker == null) {
            throw new IllegalStateException("Service not tracked for class " + clazz);
        }
        return clazz.cast(tracker.getService());
    }

    /**
     * Called in {@link #doStart()}
     */
    protected Object getTrackedService(String clazz) {
        SingleServiceTracker tracker = trackers.get(clazz);
        if (tracker == null) {
            throw new IllegalStateException("Service not tracked for class " + clazz);
        }
        return tracker.getService();
    }

    /**
     * Called in {@link #doStart()}
     */
    protected void registerMBean(Object mbean, String type) {
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put("jmx.objectname", "org.apache.karaf:" + type + ",name=" + System.getProperty("karaf.name"));
        trackRegistration(bundleContext.registerService(getInterfaceNames(mbean), mbean, props));
    }

    /**
     * Called in {@link #doStart()}
     */
    protected void register(Class clazz, Object service) {
        register(clazz, service, null);
    }

    /**
     * Called in {@link #doStart()}
     */
    protected void register(Class clazz, Object service, Dictionary<String, ?> props) {
        trackRegistration(bundleContext.registerService(clazz, service, props));
    }

    /**
     * Called in {@link #doStart()}
     */
    protected void register(String clazz, Object service) {
        register(clazz, service, null);
    }

    /**
     * Called in {@link #doStart()}
     */
    protected void register(String clazz, Object service, Dictionary<String, ?> props) {
        trackRegistration(bundleContext.registerService(clazz, service, props));
    }

    /**
     * Called in {@link #doStart()}
     */
    protected void register(String[] clazz, Object service) {
        register(clazz, service, null);
    }

    /**
     * Called in {@link #doStart()}
     */
    protected void register(String[] clazz, Object service, Dictionary<String, ?> props) {
        trackRegistration(bundleContext.registerService(clazz, service, props));
    }

    private void trackRegistration(ServiceRegistration registration) {
        if (registrations == null) {
            registrations = new ArrayList<ServiceRegistration>();
        }
        registrations.add(registration);
    }

    protected String[] getInterfaceNames(Object object) {
        List<String> names = new ArrayList<String>();
        for (Class cl = object.getClass(); cl != Object.class; cl = cl.getSuperclass()) {
            addSuperInterfaces(names, cl);
        }
        return names.toArray(new String[names.size()]);
    }

    private void addSuperInterfaces(List<String> names, Class clazz) {
        for (Class cl : clazz.getInterfaces()) {
            names.add(cl.getName());
            addSuperInterfaces(names, cl);
        }
    }

}
