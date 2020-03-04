/*
 * @(#)file      GenericConnectorServer.java
 * @(#)author    Sun Microsystems, Inc.
 * @(#)version   1.87
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

import com.sun.jmx.remote.generic.DefaultConfig;
import com.sun.jmx.remote.generic.ObjectWrappingImpl;
import com.sun.jmx.remote.generic.ServerSynchroMessageConnection;
import com.sun.jmx.remote.generic.SynchroMessageConnectionServer;
import com.sun.jmx.remote.generic.SynchroMessageConnectionServerImpl;
import com.sun.jmx.remote.opt.internal.ArrayNotificationBuffer;
import com.sun.jmx.remote.opt.internal.NotificationBuffer;
import com.sun.jmx.remote.opt.security.MBeanServerFileAccessController;
import com.sun.jmx.remote.opt.util.ClassLogger;
import com.sun.jmx.remote.opt.util.EnvHelp;
import com.sun.jmx.remote.opt.util.ThreadService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXConnectorServerProvider;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.MBeanServerForwarder;
import javax.security.auth.Subject;

/**
 * A JMX API Connector server that creates connections to remote clients. This class can use a
 * {@link MessageConnectionServer} object to specify how connections are made.
 * <p>
 * User code does not usually instantiate this class. Instead, a {@link JMXConnectorServerProvider} should be added to
 * the {@link
 * JMXConnectorServerFactory} so that users can implicitly instantiate the GenericConnector (or a subclass of it)
 * through the {@link
 * JMXServiceURL} provided when creating it.</p>
 *
 * <p>
 * The specific connector protocol to be used by an instance of this class is specified by attributes in the
 * <code>Map</code> passed to the constructor. The attribute {@link
 * #MESSAGE_CONNECTION_SERVER} is the standard way to define the transport. An implementation can recognize other
 * attributes to define the transport differently.</p>
 */
public class GenericConnectorServer extends JMXConnectorServer {

    /**
     * Name of the attribute that specifies the object wrapping for parameters whose deserialization requires special
     * treatment. The value associated with this attribute, if any, must be an object that implements the interface
     * {@link ObjectWrapping}.
     */
    public static final String OBJECT_WRAPPING = "jmx.remote.object.wrapping";

    /**
     * Name of the attribute that specifies how connections are made to this connector server. The value associated with
     * this attribute, if any, must be an object that implements the interface {@link MessageConnectionServer}.
     */
    public static final String MESSAGE_CONNECTION_SERVER = "jmx.remote.message.connection.server";

// constructors
    /**
     * Constructs a <code>GenericConnectorServer</code> attached to the given MBean server.
     *
     * @param env a set of attributes for the connector server. Can be null, which is equivalent to an empty map.
     * @param mbs the local MBeanServer used to execute a remote request. Null if the MBean server will be specified by
     * registering this connector server as an MBean in it.
     *
     * @exception IllegalArgumentException if <var>env</var> contains some invalid values.
     */
    public GenericConnectorServer(Map env, MBeanServer mbs) {
        super(mbs);

        if (env == null) {
            this.env = Collections.EMPTY_MAP;
        } else {
            EnvHelp.checkAttributes(env);
            this.env = Collections.unmodifiableMap(env);
        }

        connectingTimeout = DefaultConfig.getConnectingTimeout(this.env);
    }

    // used by a client connection
    void clientClosing(ServerIntermediary inter, String connectionId, String msg, Object userData) {

        synchronized (lock) {
            clientList.remove(inter);
        }

        super.connectionClosed(connectionId, msg, userData);
    }

    // JMXConnectorServerMBean interface implementation
    @Override
    public JMXServiceURL getAddress() {
        if (!isActive()) {
            return null;
        }
        return sMsgServer.getAddress();
    }

    @Override
    public Map getAttributes() {
        Map map = EnvHelp.filterAttributes(env);
        return Collections.unmodifiableMap(map);
    }

    /**
     * Activates the connector server, that is, starts listening for client connections. Calling this method when the
     * connector server is already active has no effect. Calling this method when the connector server has been stopped
     * will generate an {@link IOException}.
     *
     * @exception IllegalStateException if the connector server has not been attached to an MBean server.
     * @exception IOException if the connector server cannot be started.
     */
    @Override
    public void start() throws IOException {
        synchronized (lock) {
            if (state == STARTED) {
                LOGGER.trace("start", "already started");
                return;
            } else if (state == STOPPED) {
                LOGGER.trace("start", "already stopped");
                throw new IOException("The server has been stopped.");
            }
            LOGGER.trace("start", "starting...");
            LOGGER.trace("start", "setting MBeanServer...");

            MBeanServer mbs = getMBeanServer();
            if (mbs == null) {
                throw new IllegalStateException("This connector server is not attached to an MBean server");
            }
            // Check the internal access file property to see
            // if an MBeanServerForwarder is to be provided
            //
            if (env != null) {
                // Check if access file property is specified
                //
                String accessFile = (String) env.get("jmx.remote.x.access.file");
                if (accessFile != null) {
                    // Access file property specified, create an instance
                    // of the MBeanServerFileAccessController class
                    //
                    MBeanServerForwarder mbsf = null;
                    try {
                        mbsf = new MBeanServerFileAccessController(accessFile);
                    } catch (IOException e) {
                        throw (IllegalArgumentException) EnvHelp.initCause(
                                new IllegalArgumentException(e.getMessage()), e);
                    }
                    // Set the MBeanServerForwarder
                    //
                    setMBeanServerForwarder(mbsf);
                    mbs = getMBeanServer();
                }
            }

            LOGGER.trace("start", "setting default ClassLoader...");

            try {
                defaultClassLoader = EnvHelp.resolveServerClassLoader(env, mbs);
            } catch (InstanceNotFoundException infc) {
                LOGGER.debug("start", "ClassLoader not found: " + infc);
                IllegalArgumentException x = new IllegalArgumentException("ClassLoader not found: " + infc);
                throw (IllegalArgumentException) EnvHelp.initCause(x, infc);
            }

            LOGGER.trace("start", "setting ObjectWrapping...");

            objectWrapping = (ObjectWrapping) env.get(OBJECT_WRAPPING);
            if (objectWrapping == null) {
                objectWrapping = new ObjectWrappingImpl();
            }

            final MessageConnectionServer messageServer = (MessageConnectionServer) env.get(MESSAGE_CONNECTION_SERVER);
            if (messageServer == null) {
                sMsgServer = DefaultConfig.getSynchroMessageConnectionServer(env);

                if (sMsgServer == null) {
                    final String msg = "No message connection server";
                    throw new IllegalArgumentException(msg);
                }
            } else {
                sMsgServer = new SynchroMessageConnectionServerImpl(messageServer, env);
            }
            sMsgServer.start(env);

            state = STARTED;

            LOGGER.trace("start", "Connector Server Address = " + sMsgServer.getAddress());
            LOGGER.trace("start", "started.");

            // start to receive clients
            receiver = new Receiver();
            receiver.start();
        }
    }

    @Override
    public void stop() throws IOException {
        synchronized (lock) {
            if (state == STOPPED) {
                LOGGER.trace("stop", "already stopped.");
                return;
            } else if (state == CREATED) {
                LOGGER.trace("stop", "not started yet.");
            }

            state = STOPPED;

            final boolean debug = LOGGER.debugOn();

            LOGGER.trace("stop", "stoping.");
            LOGGER.trace("stop", "stop MessageConnectionServer...");

            // stop the transport level
            if (sMsgServer != null) {
                sMsgServer.stop();
            }

            LOGGER.trace("stop", "stop clients...");
            // stop all existing clients
            LOGGER.trace("stop", clientList.size() + "client(s) found...");

            while (clientList.size() > 0) {
                try {
                    ServerIntermediary inter = (ServerIntermediary) clientList.remove(0);
                    inter.terminate();
                } catch (Exception e) {
                    // Warning should be enough.
                    LOGGER.warning("stop", "Failed to stop client: " + e);
                    if (debug) {
                        LOGGER.debug("stop", e);
                    }
                }
            }

            if (notifBuffer != null) {
                notifBuffer.dispose();
            }

            threads.terminate();
        }

        CONNECTING_CANCELLATION_TIMER.cancel();
        LOGGER.trace("stop", "stopped.");
    }

    @Override
    public boolean isActive() {
        synchronized (lock) {
            return state == STARTED;
        }
    }

    // used by ServerIntermediary
    void failedConnectionNotif(String connectionId, String message, Object userData) {
        super.connectionFailed(connectionId, message, userData);
    }

    private class Receiver extends Thread {

        public Receiver() {
            super();
            this.setName("JMXMP Server - ConnectorServer Receiver " + getName());
        }

        @Override
        public void run() {
            LOGGER.debug("Receiver.run", "starting receiver.");

            while (isActive()) {
                final boolean debug = LOGGER.debugOn();
                LOGGER.trace("Receiver.run", "waiting for connection.");

                ServerSynchroMessageConnection connection;
                try {
                    connection = sMsgServer.accept();
                } catch (IOException ioe) {
                    if (isActive()) {
                        LOGGER.error("Receiver.run", "Unexpected IOException: " + ioe);
                        if (debug) {
                            LOGGER.debug("Receiver.run", ioe);
                        }
                        try {
                            LOGGER.error("Receiver.run", "stopping server");
                            GenericConnectorServer.this.stop();
                        } catch (IOException ie) {
                            LOGGER.warning("Receiver.run", "Failed to stop server: " + ie);
                            if (debug) {
                                LOGGER.debug("Receiver.run", ie);
                            }
                        }
                    } else {
                        LOGGER.trace("Receiver.run", "interrupted: " + ioe);
                    }
                    break;
                }

                if (!isActive()) {
                    return;
                }

                LOGGER.trace("Receiver.run", "received connection request.");

                // use another thread to do security issue to free
                // the receiver thread for receiving new clients
                ClientCreation cc = new ClientCreation(connection);
                if (connectingTimeout <= 0) {
                    threads.handoff(cc);
                } else {
                    ConnectingStopper stopper = new ConnectingStopper(cc);
                    cc.setStopper(stopper);
                    threads.handoff(cc);
                    CONNECTING_CANCELLATION_TIMER.schedule(stopper, connectingTimeout);
                }
            }

            LOGGER.debug("Receiver.run", "receiver terminated");
        }
    }

    private class ClientCreation implements Runnable {

        public ClientCreation(ServerSynchroMessageConnection connection) {
            this.connection = connection;
        }

        public void setStopper(ConnectingStopper stopper) {
            this.stopper = stopper;
        }

        @Override
        public void run() {
            Subject subject = null;

            boolean failed = false;

            try {
                connection.connect(env);
                LOGGER.trace("ClientCreation.run", "opening connection.");

                subject = connection.getSubject();
            } catch (Throwable e) {
                failed = true;

                LOGGER.warning("ClientCreation.run", "Failed to open connection: " + e, e);
                LOGGER.debug("ClientCreation.run", e);
                try {
                    LOGGER.debug("ClientCreation.run", "cleaning up...");
                    connection.close();
                } catch (Exception ee) {
                    if (LOGGER.debugOn()) {
                        LOGGER.debug("ClientCreation.run", "Failed to cleanup: " + ee);
                    }
                    if (LOGGER.debugOn()) {
                        LOGGER.debug("ClientCreation.run", ee);
                    }
                }
            }

            synchronized (this) {
                if (done) {
                    // set by stopper, timeout
                    failed = true;
                } else {
                    done = true;
                    if (stopper != null) {
                        stopper.cancel();
                    }
                }
            }

            if (failed) {
                return;
            }

            LOGGER.trace("ClientCreation.run", "connection opened.");

            final ServerIntermediary inter = new ServerIntermediary(getMBeanServer(),
                    GenericConnectorServer.this,
                    connection, objectWrapping,
                    subject,
                    defaultClassLoader, env);

            synchronized (lock) {
                if (state != STARTED) {
                    try {
                        LOGGER.debug("ClientCreation.run", "connector already stopped.");
                        LOGGER.trace("ClientCreation.run", "cleaning up...");
                        inter.terminate();
                    } catch (Exception e) {
                        LOGGER.debug("ClientCreation.run", "Failed to cleanup: " + e);
                        LOGGER.debug("ClientCreation.run", e);
                    }

                    return;
                } else {
                    LOGGER.trace("ClientCreation.run", "adding connection to client list.");
                    clientList.add(inter);
                }
            }

            final String cid = connection.getConnectionId();
            connectionOpened(cid, "New client connection " + cid + " has been established", null);

            inter.start();
        }

        ServerSynchroMessageConnection connection;
        private boolean done = false;
        private ConnectingStopper stopper;
    }

    private class ConnectingStopper extends TimerTask {

        public ConnectingStopper(ClientCreation cc) {
            this.cc = cc;
        }

        @Override
        public void run() {
            synchronized (cc) {
                if (cc.done) {
                    return;
                }

                // tell "timeout"
                cc.done = true;
            }

            LOGGER.trace("ConnectingStopper.run", "Connecting timeout for: " + cc.connection);

            try {
                cc.connection.close();
            } catch (Exception e) {
                LOGGER.debug("ConnectingStoper.run", e);
            }

        }

        private final ClientCreation cc;
    }

    synchronized NotificationBuffer getNotifBuffer() {
        //Notification buffer is lazily created when the first client connects
        if (notifBuffer == null) {
            notifBuffer = ArrayNotificationBuffer.getNotificationBuffer(getMBeanServer(), env);
        }
        return notifBuffer;
    }

    private static final ClassLogger LOGGER = new ClassLogger("javax.management.remote.generic", "GenericConnectorServer");

    // private variables
    private Receiver receiver;

    private SynchroMessageConnectionServer sMsgServer;
    private ObjectWrapping objectWrapping;
    private final Map env;
    private ClassLoader defaultClassLoader = null;

    private final ThreadService threads = new ThreadService(0, 10);

    private final ArrayList clientList = new ArrayList();

    private static final int DEFAULT_NOTIF_BUFFER_SIZE = 1000;

    // state
    private static final int CREATED = 0;
    private static final int STARTED = 1;
    private static final int STOPPED = 2;

    private int state = CREATED;

    private final int[] lock = new int[0];

    // client id
    private static final long CLIENT_ID_COUNT = 0;
    private static final int[] CLIENT_ID_COUNTLOCK = new int[0];

    private NotificationBuffer notifBuffer;

    // client connecting control
    private final long connectingTimeout;
//     private final int maxConnecting;

    private static final Timer CONNECTING_CANCELLATION_TIMER = new Timer(true);
}
