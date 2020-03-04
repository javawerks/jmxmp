/*
 * @(#)file      GenericConnector.java
 * @(#)author    Sun Microsystems, Inc.
 * @(#)version   1.85
 * @(#)lastedit  07/03/08
 * @(#)build     @BUILD_TAG_PLACEHOLDER@
 *
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2007 Sun Microsystems, Inc. All Rights Reserved.
 *
 * The contents of this file are subject to the terms of either the GNU General
 * Public License Version 2 only ("GPL") or the Common Development and
 * Distribution License("CDDL")(collectively, the "License"). You may not use
 * this file except in compliance with the License. You can obtain a copy of the
 * License at http://opendmk.dev.java.net/legal_notices/licenses.txt or in the
 * LEGAL_NOTICES folder that accompanied this code. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file found at
 *     http://opendmk.dev.java.net/legal_notices/licenses.txt
 * or in the LEGAL_NOTICES folder that accompanied this code.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.
 *
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 *
 *       "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding
 *
 *       "[Contributor] elects to include this software in this distribution
 *        under the [CDDL or GPL Version 2] license."
 *
 * If you don't indicate a single choice of license, a recipient has the option
 * to distribute your version of this file under either the CDDL or the GPL
 * Version 2, or to extend the choice of license to its licensees as provided
 * above. However, if you add GPL Version 2 code and therefore, elected the
 * GPL Version 2 license, then the option applies only if the new code is made
 * subject to such option by the copyright holder.
 *
 */
package javax.management.remote.generic;

import com.sun.jmx.remote.generic.ClientSynchroMessageConnection;
import com.sun.jmx.remote.generic.ClientSynchroMessageConnectionImpl;
import com.sun.jmx.remote.generic.DefaultConfig;
import com.sun.jmx.remote.generic.ObjectWrappingImpl;
import com.sun.jmx.remote.generic.SynchroCallback;
import com.sun.jmx.remote.opt.util.ClassLogger;
import com.sun.jmx.remote.opt.util.EnvHelp;
import com.sun.jmx.remote.opt.util.ThreadService;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.InvalidAttributeValueException;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServerConnection;
import javax.management.NotCompliantMBeanException;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.QueryExp;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnectionNotification;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXConnectorProvider;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.message.CloseMessage;
import javax.management.remote.message.Message;
import javax.security.auth.Subject;

/**
 * A client connection to a remote JMX API server. This class can use a {@link MessageConnection} object to specify the
 * transport for communicating with the server.
 *
 * <p>
 * User code does not usually instantiate this class. Instead, a {@link JMXConnectorProvider} should be added to the {@link
 * JMXConnectorFactory} so that users can implicitly instantiate the GenericConnector (or a subclass of it) through the {@link
 * JMXServiceURL} provided when connecting.</p>
 *
 * <p>
 * The specific connector protocol to be used by an instance of this class is specified by attributes in the
 * <code>Map</code> passed to the constructor or the {@link #connect(Map) connect} method. The attribute
 * {@link #MESSAGE_CONNECTION} is the standard way to define the transport. An implementation can recognize other
 * attributes to define the transport differently.</p>
 */
public class GenericConnector implements JMXConnector {

    /**
     * Name of the attribute that specifies the object wrapping for parameters whose deserialization requires special
     * treatment. The value associated with this attribute, if any, must be an object that implements the interface
     * {@link ObjectWrapping}.
     */
    public static final String OBJECT_WRAPPING
            = GenericConnectorServer.OBJECT_WRAPPING;

    /**
     * Name of the attribute that specifies how this connector sends messages to its connector server. The value
     * associated with this attribute, if any, must be an object that implements the interface
     * {@link MessageConnection}.
     */
    public static final String MESSAGE_CONNECTION = "jmx.remote.message.connection";

    // constructors
    /**
     * Default no-arg constructor.
     */
    public GenericConnector() {
        // ------------------------------------------------------------
        //  WARNING - WARNING - WARNING - WARNING - WARNING - WARNING
        // ------------------------------------------------------------
        // This constructor is needed in order for subclasses to be
        // serializable.
        //
        this((Map) null);
    }

    /**
     * Constructor specifying connection attributes.
     *
     * @param env the attributes of the connection.
     *
     */
    public GenericConnector(Map env) {
        // ------------------------------------------------------------
        //  WARNING - WARNING - WARNING - WARNING - WARNING - WARNING
        // ------------------------------------------------------------
        // Initialize transient variables. All transient variables
        // that need a specific initialization must be initialized here.
        //
        rmbscMap = new WeakHashMap();
        lock = new int[0];
        state = CREATED;
        if (env == null) {
            this.env = Collections.EMPTY_MAP;
        } else {
            EnvHelp.checkAttributes(env);
            this.env = Collections.unmodifiableMap(env);
        }
        connectionBroadcaster = new NotificationBroadcasterSupport();
    }

    @Override
    public void connect() throws IOException {
        connect(null);
    }

    @Override
    public void connect(Map env) throws IOException {
        final String idstr = (LOGGER.traceOn() ? "[" + this.toString() + "]" : null);

        synchronized (lock) {
            switch (state) {
                case CREATED:
                    break;
                case CONNECTED:
                    LOGGER.trace("connect", idstr + " already connected.");
                    return;
                case CLOSED:
                    LOGGER.trace("connect", idstr + " already closed.");
                    throw new IOException("Connector already closed.");
                default:
                    // should never happen
                    LOGGER.trace("connect", idstr + " unknown state: " + state);
                    throw new IOException("Invalid state (" + state + ")");
            }

            final Map tmpEnv = new HashMap((this.env == null) ? Collections.EMPTY_MAP : this.env);

            if (env != null) {
                EnvHelp.checkAttributes(env);
                tmpEnv.putAll(env);
            }

            MessageConnection conn = (MessageConnection) tmpEnv.get(MESSAGE_CONNECTION);
            if (conn == null) {
                connection = DefaultConfig.getClientSynchroMessageConnection(tmpEnv);
                if (connection == null) {
                    LOGGER.trace("connect", idstr + " No MessageConnection");
                    throw new IllegalArgumentException("No MessageConnection");
                }
                LOGGER.trace("connect", "The connection uses a user specific Synchronous message connection: " + connection);
            } else {
                requestHandler = new RequestHandler();

                connection = new ClientSynchroMessageConnectionImpl(conn, requestHandler, tmpEnv);
                LOGGER.trace("connect", "The connection uses a user specific Asynchronous message connection: " + conn);
            }
            connection.connect(tmpEnv);
            connectionId = connection.getConnectionId();

            objectWrapping = (ObjectWrapping) tmpEnv.get(OBJECT_WRAPPING);
            if (objectWrapping == null) {
                objectWrapping = new ObjectWrappingImpl();
            }

            clientMBeanServer = new ClientIntermediary(connection, objectWrapping, this, tmpEnv);

            this.env = tmpEnv;

            state = CONNECTED;
            LOGGER.trace("connect", idstr + " " + connectionId + " Connected.");
        }

        notifThread = new ThreadService(0, 1);

        sendNotification(new JMXConnectionNotification(
                JMXConnectionNotification.OPENED,
                this,
                connectionId,
                clientNotifID++,
                null,
                null));
    }

    @Override
    public String getConnectionId() throws IOException {
        checkState();
        return connection.getConnectionId();
    }

    // implements client interface here
    @Override
    public MBeanServerConnection getMBeanServerConnection()
            throws IOException {
        return getMBeanServerConnection(null);
    }

    @Override
    public MBeanServerConnection getMBeanServerConnection(
            Subject delegationSubject)
            throws IOException {
        checkState();
        if (rmbscMap.containsKey(delegationSubject)) {
            return (MBeanServerConnection) rmbscMap.get(delegationSubject);
        } else {
            RemoteMBeanServerConnection rmbsc = new RemoteMBeanServerConnection(clientMBeanServer, delegationSubject);
            rmbscMap.put(delegationSubject, rmbsc);
            return rmbsc;
        }
    }

    @Override
    public void close() throws IOException {
        close(false, "The connection is closed by a user.");
    }

    private void close(boolean local, String msg) throws IOException {
        final String idstr = (LOGGER.traceOn() ? "[" + this.toString() + "]" : null);
        Exception closeException;

        boolean createdState;
        synchronized (lock) {
            if (state == CLOSED) {
                LOGGER.trace("close", idstr + " already closed.");
                return;
            }

            createdState = (state == CREATED);
            state = CLOSED;
            closeException = null;

            LOGGER.trace("close", idstr + " closing.");

            if (!createdState) {
                // inform the remote side of termination
                if (!local) {
                    try {
                        synchronized (connection) {
                            connection.sendOneWay(new CloseMessage(msg));
                            Thread.sleep(100);
                        }
                    } catch (InterruptedException ire) {
                        // OK
                    } catch (Exception e1) {
                        // error trace
                        closeException = e1;
                        LOGGER.trace("close", idstr + " failed to send close message: " + e1);
                        LOGGER.debug("close", e1);
                    }
                }

                // close the transport protocol.
                try {
                    connection.close();
                } catch (Exception e1) {
                    closeException = e1;
                    LOGGER.trace("close", idstr + " failed to close MessageConnection: " + e1);
                    LOGGER.debug("close", e1);
                }
            }

            if (clientMBeanServer != null) {
                clientMBeanServer.terminate();
            }

            // Clean up MBeanServerConnection table
            //
            rmbscMap.clear();
        }

        // if not connected, no need to send closed notif
        if (!createdState) {
            sendNotification(new JMXConnectionNotification(
                    JMXConnectionNotification.CLOSED,
                    this,
                    connectionId,
                    clientNotifID++,
                    "The client has been closed.", null));
        }

        if (closeException != null) {
            if (closeException instanceof RuntimeException) {
                throw (RuntimeException) closeException;
            }
            if (closeException instanceof IOException) {
                throw (IOException) closeException;
            }
            final IOException x = new IOException("Failed to close: "
                    + closeException);
            throw (IOException) EnvHelp.initCause(x, closeException);

        }
        LOGGER.trace("close", idstr + " closed.");
    }

    @Override
    public void addConnectionNotificationListener(NotificationListener listener,
            NotificationFilter filter,
            Object handback) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is null");
        }
        connectionBroadcaster.addNotificationListener(listener, filter, handback);
    }

    @Override
    public void removeConnectionNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
        if (listener == null) {
            throw new IllegalArgumentException("listener is null");
        }
        connectionBroadcaster.removeNotificationListener(listener);
    }

    @Override
    public void removeConnectionNotificationListener(NotificationListener listener,
            NotificationFilter filter,
            Object handback) throws ListenerNotFoundException {
        if (listener == null) {
            throw new IllegalArgumentException("listener is null");
        }
        connectionBroadcaster.removeNotificationListener(listener, filter, handback);
    }

    /**
     * Send a notification to the connection listeners. The notification will be sent to every listener added with {@link
     * #addConnectionNotificationListener
     * addConnectionNotificationListener} that was not subsequently removed by a
     * <code>removeConnectionNotificationListener</code>, provided the corresponding {@link NotificationFilter} matches.
     *
     * @param n the notification to send. This will usually be a {@link JMXConnectionNotification}, but an
     * implementation can send other notifications as well.
     */
    protected void sendNotification(final Notification n) {
        Runnable job = new Runnable() {
            @Override
            public void run() {
                try {
                    connectionBroadcaster.sendNotification(n);
                } catch (Exception e) {
                    // OK.
                    // should never
                }
            }
        };

        notifThread.handoff(job);
    }

//----------------------------------------------
// private classes
//----------------------------------------------
    private static class RemoteMBeanServerConnection implements MBeanServerConnection {

        public RemoteMBeanServerConnection(ClientIntermediary ci) {
            this(ci, null);
        }

        public RemoteMBeanServerConnection(ClientIntermediary ci, Subject ds) {
            this.ci = ci;
            this.ds = ds;
        }

        //----------------------------------------------
        // Implementation of MBeanServerConnection
        //----------------------------------------------
        @Override
        public ObjectInstance createMBean(String className, ObjectName name)
                throws
                ReflectionException,
                InstanceAlreadyExistsException,
                MBeanRegistrationException,
                MBeanException,
                NotCompliantMBeanException,
                IOException {
            return ci.createMBean(className, name, ds);
        }

        @Override
        public ObjectInstance createMBean(String className,
                ObjectName name,
                ObjectName loaderName)
                throws
                ReflectionException,
                InstanceAlreadyExistsException,
                MBeanRegistrationException,
                MBeanException,
                NotCompliantMBeanException,
                InstanceNotFoundException,
                IOException {
            return ci.createMBean(className, name, loaderName, ds);
        }

        @Override
        public ObjectInstance createMBean(String className,
                ObjectName name,
                Object params[],
                String signature[])
                throws
                ReflectionException,
                InstanceAlreadyExistsException,
                MBeanRegistrationException,
                MBeanException,
                NotCompliantMBeanException,
                IOException {
            return ci.createMBean(className, name, params, signature, ds);
        }

        @Override
        public ObjectInstance createMBean(String className,
                ObjectName name,
                ObjectName loaderName,
                Object params[],
                String signature[])
                throws
                ReflectionException,
                InstanceAlreadyExistsException,
                MBeanRegistrationException,
                MBeanException,
                NotCompliantMBeanException,
                InstanceNotFoundException,
                IOException {
            return ci.createMBean(className, name, loaderName,
                    params, signature, ds);
        }

        @Override
        public void unregisterMBean(ObjectName name)
                throws
                InstanceNotFoundException,
                MBeanRegistrationException,
                IOException {
            ci.unregisterMBean(name, ds);
        }

        @Override
        public ObjectInstance getObjectInstance(ObjectName name)
                throws InstanceNotFoundException, IOException {
            return ci.getObjectInstance(name, ds);
        }

        @Override
        public Set queryMBeans(ObjectName name, QueryExp query)
                throws IOException {
            return ci.queryMBeans(name, query, ds);
        }

        @Override
        public Set queryNames(ObjectName name, QueryExp query)
                throws IOException {
            return ci.queryNames(name, query, ds);
        }

        @Override
        public boolean isRegistered(ObjectName name)
                throws IOException {
            return ci.isRegistered(name, ds);
        }

        @Override
        public Integer getMBeanCount()
                throws IOException {
            return ci.getMBeanCount(ds);
        }

        @Override
        public Object getAttribute(ObjectName name, String attribute)
                throws
                MBeanException,
                AttributeNotFoundException,
                InstanceNotFoundException,
                ReflectionException,
                IOException {
            return ci.getAttribute(name, attribute, ds);
        }

        @Override
        public AttributeList getAttributes(ObjectName name, String[] attributes)
                throws InstanceNotFoundException, ReflectionException, IOException {
            return ci.getAttributes(name, attributes, ds);
        }

        @Override
        public void setAttribute(ObjectName name, Attribute attribute)
                throws
                InstanceNotFoundException,
                AttributeNotFoundException,
                InvalidAttributeValueException,
                MBeanException,
                ReflectionException,
                IOException {
            ci.setAttribute(name, attribute, ds);
        }

        @Override
        public AttributeList setAttributes(ObjectName name,
                AttributeList attributes)
                throws InstanceNotFoundException, ReflectionException, IOException {
            return ci.setAttributes(name, attributes, ds);
        }

        @Override
        public Object invoke(ObjectName name, String operationName,
                Object params[], String signature[])
                throws
                InstanceNotFoundException,
                MBeanException,
                ReflectionException,
                IOException {
            return ci.invoke(name, operationName, params, signature, ds);
        }

        @Override
        public String getDefaultDomain()
                throws IOException {
            return ci.getDefaultDomain(ds);
        }

        @Override
        public String[] getDomains()
                throws IOException {
            return ci.getDomains(ds);
        }

        @Override
        public void addNotificationListener(ObjectName name,
                NotificationListener listener,
                NotificationFilter filter,
                Object handback)
                throws InstanceNotFoundException, IOException {
            ci.addNotificationListener(name, listener, filter, handback, ds);
        }

        @Override
        public void addNotificationListener(ObjectName name,
                ObjectName listener,
                NotificationFilter filter,
                Object handback)
                throws InstanceNotFoundException, IOException {
            ci.addNotificationListener(name, listener, filter, handback, ds);
        }

        @Override
        public void removeNotificationListener(ObjectName name,
                ObjectName listener)
                throws
                InstanceNotFoundException,
                ListenerNotFoundException,
                IOException {
            ci.removeNotificationListener(name, listener, ds);
        }

        @Override
        public void removeNotificationListener(ObjectName name,
                ObjectName listener,
                NotificationFilter filter,
                Object handback)
                throws
                InstanceNotFoundException,
                ListenerNotFoundException,
                IOException {
            ci.removeNotificationListener(name, listener, filter, handback, ds);
        }

        @Override
        public void removeNotificationListener(ObjectName name,
                NotificationListener listener)
                throws
                InstanceNotFoundException,
                ListenerNotFoundException,
                IOException {
            ci.removeNotificationListener(name, listener, ds);
        }

        @Override
        public void removeNotificationListener(ObjectName name,
                NotificationListener listener,
                NotificationFilter filter,
                Object handback)
                throws
                InstanceNotFoundException,
                ListenerNotFoundException,
                IOException {
            ci.removeNotificationListener(name, listener, filter, handback, ds);
        }

        @Override
        public MBeanInfo getMBeanInfo(ObjectName name)
                throws
                InstanceNotFoundException,
                IntrospectionException,
                ReflectionException,
                IOException {
            return ci.getMBeanInfo(name, ds);
        }

        @Override
        public boolean isInstanceOf(ObjectName name, String className)
                throws InstanceNotFoundException, IOException {
            return ci.isInstanceOf(name, className, ds);
        }

        private ClientIntermediary ci;
        private Subject ds;
    }

    private class RequestHandler implements SynchroCallback {

        @Override
        public Message execute(Message msg) {
            if (msg instanceof CloseMessage) {
                if (LOGGER.traceOn()) {
                    LOGGER.trace("RequestHandler-execute", "got Message REMOTE_TERMINATION");
                }

                // try to re-connect anyway
                try {
                    com.sun.jmx.remote.opt.internal.ClientCommunicatorAdmin admin = clientMBeanServer.getCommunicatorAdmin();
                    admin.gotIOException(new IOException(""));

                    return null;
                } catch (IOException ioe) {
                    // OK
                    // the server has been closed.
                }

                try {
                    GenericConnector.this.close(true, null);
                } catch (IOException ie) {
                    // OK never
                }
            } else {
                final String errstr = ((msg == null) ? "null" : msg.getClass().getName()) + ": Bad message type.";
                LOGGER.warning("RequestHandler-execute", errstr);

                try {
                    LOGGER.warning("RequestHandler-execute", "Closing connector");
                    GenericConnector.this.close(false, null);
                } catch (IOException ie) {
                    LOGGER.info("RequestHandler-execute", "Error during close " + ie.getMessage());
                    LOGGER.fine("RequestHandler-execute", ie);
                }
            }

            return null;
        }

        @Override
        public void connectionException(Exception e) {
            synchronized (lock) {
                if (state != CONNECTED) {
                    return;
                }
            }

            LOGGER.warning("RequestHandler-connectionException", e.getMessage());
            LOGGER.fine("RequestHandler-connectionException: ", e);

            if (e instanceof IOException) {
                try {
                    com.sun.jmx.remote.opt.internal.ClientCommunicatorAdmin admin = clientMBeanServer.getCommunicatorAdmin();
                    admin.gotIOException((IOException) e);

                    return;
                } catch (IOException ioe) {
                    // OK.
                    // closing at the following steps
                }
            }

            synchronized (lock) {
                if (state == CONNECTED) {
                    LOGGER.warning("RequestHandler-connectionException", "Got connection exception: " + e.toString());
                    LOGGER.debug("RequestHandler-connectionException", "Got connection exception: " + e.toString(), e);

                    try {
                        GenericConnector.this.close(true, null);
                    } catch (IOException ie) {
                        LOGGER.info("RequestHandler-execute", "Error during close " + ie.getMessage());
                        LOGGER.fine("RequestHandler-execute", ie);
                    }
                }
            }
        }
    }

//    private static class ResponseMsgWrapper {
//
//        public boolean got = false;
//        public Message msg = null;
//
//        public ResponseMsgWrapper() {
//        }
//
//        public void setMsg(Message msg) {
//            got = true;
//            this.msg = msg;
//        }
//    }
    /**
     * Called by a ClientIntermediary to reconnect the transport because the server has been closed after its timeout.
     */
    ClientSynchroMessageConnection reconnect() throws IOException {
        synchronized (lock) {
            if (state != CONNECTED) {
                throw new IOException("The connector is not at the connection state.");
            }
        }

        sendNotification(new JMXConnectionNotification(JMXConnectionNotification.FAILED, this, connectionId, clientNotifID++, "The client has got connection exception. Trying to reconnect.", null));

        connection.connect(env);
        connectionId = connection.getConnectionId();

        sendNotification(new JMXConnectionNotification(JMXConnectionNotification.OPENED, this, connectionId, clientNotifID++, "The client has succesfully reconnected to the server.", null));

        return connection;
    }

// -------------------------------------------------
// private methods
// -------------------------------------------------
    private void checkState() throws IOException {
        synchronized (lock) {
            if (state == CREATED) {
                throw new IOException("The client has not been connected.");
            } else if (state == CLOSED) {
                throw new IOException("The client has been closed.");
            }
        }
    }

    // private variables
    // -------------------------------------------------------------------
    // WARNING - WARNING - WARNING - WARNING - WARNING - WARNING - WARNING
    // -------------------------------------------------------------------
    //
    // SERIALIZATION ISSUES
    // --------------------
    //
    // All private variables must be defined transient.
    //
    // Do not put any initialization here. If a specific initialization
    // is needed, put it in the empty default constructor.
    //
    // -------------------------------------------------------------------
    private transient ClientSynchroMessageConnection connection;
    private transient ObjectWrapping objectWrapping;
    private transient Map env;

    private transient ClientIntermediary clientMBeanServer;
    private transient WeakHashMap rmbscMap;
    private transient String connectionId;

    private transient RequestHandler requestHandler;

    private transient final NotificationBroadcasterSupport connectionBroadcaster;

    private transient ThreadService notifThread;

    // state
    private static final int CREATED = 1;
    private static final int CONNECTED = 2;
    private static final int CLOSED = 3;

    // default value is 0.
    private transient int state;
    private final transient int[] lock;

    private transient long clientNotifID = 0;

    private static final ClassLogger LOGGER = new ClassLogger("javax.management.remote.generic", "GenericConnector");
}
