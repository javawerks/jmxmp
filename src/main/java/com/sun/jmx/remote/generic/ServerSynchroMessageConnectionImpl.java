/*
 * @(#)file      ServerSynchroMessageConnectionImpl.java
 * @(#)author    Sun Microsystems, Inc.
 * @(#)version   1.5
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
package com.sun.jmx.remote.generic;

import com.sun.jmx.remote.opt.util.ClassLogger;
import com.sun.jmx.remote.opt.util.ThreadService;
import java.io.IOException;
import java.util.Map;
import javax.management.remote.generic.MessageConnection;
import javax.management.remote.message.CloseMessage;
import javax.management.remote.message.Message;
import javax.security.auth.Subject;

public class ServerSynchroMessageConnectionImpl implements ServerSynchroMessageConnection {

    public ServerSynchroMessageConnectionImpl(MessageConnection mc, Map env) throws IOException {
        if (mc == null) {
            throw new IllegalArgumentException("Null message connection.");
        }

        this.env = env;
        this.waitConnectedState = DefaultConfig.getTimeoutForWaitConnectedState(env);
        this.serverAdmin = DefaultConfig.getServerAdmin(this.env);
        this.connection = mc;
    }

    @Override
    public void connect(Map env) throws IOException {
        synchronized (stateLock) {
            if (state != UNCONNECTED) {
                waitConnected();
                return;
            } else {
                state = CONNECTING;
            }
        }

        connection.connect(env);
        connection = serverAdmin.connectionOpen(connection);

        synchronized (stateLock) {
            if (state != CONNECTING) {
                // closed by another thread
                try {
                    connection.close();
                } catch (Exception e) {
                    // OK Already closed.
                }
                throw new IOException("The connecting is stooped by another thread.");
            }

            state = CONNECTED;
            stateLock.notifyAll();
        }
    }

    @Override
    public void sendOneWay(Message msg) throws IOException {
        if (LOGGER.traceOn()) {
            LOGGER.trace("sendOneWay", "Send a message without response: " + msg);
        }

        waitConnected();

        synchronized (connectionLock) {
            connection.writeMessage(msg);
        }
    }

    @Override
    public void setCallback(SynchroCallback cb) {
        LOGGER.trace("setCallback", "be called.");

        if (callback != null) {
            throw new IllegalArgumentException("The callback has been assigned.");
        }

        if (cb == null) {
            throw new IllegalArgumentException("Null callback.");
        }
        callback = cb;

        threads = new ThreadService(DefaultConfig.getServerMinThreads(env), DefaultConfig.getServerMaxThreads(env));

        reader = new MessageReader();
        threads.handoff(reader);
    }

    @Override
    public String getConnectionId() {
        return connection.getConnectionId();
    }

    @Override
    public void close() throws IOException {
        LOGGER.trace("close", "Closing this SynchroMessageConnection.");

        synchronized (stateLock) {
            if (state == TERMINATED) {
                return;
            }

            state = TERMINATED;

            if (LOGGER.traceOn()) {
                LOGGER.trace("close", "Close the callback reader.");
            }
            if (reader != null) {
                reader.stop();
            }

            if (threads != null) {
                threads.terminate();

                threads = null;
            }

            LOGGER.trace("close", "Closing the underlying connection.");
            if (connection != null) {
                connection.close();
            }

            serverAdmin.connectionClosed(connection);

            // clean
            LOGGER.trace("close", "Clean all threads waiting theire responses.");

            stateLock.notify();
        }
    }

    /**
     * Returns the underlying asynchronous transport.
     *
     * @return the message connection
     */
    public MessageConnection getAsynchroConnection() {
        return connection;
    }

    @Override
    public Subject getSubject() {
        return serverAdmin.getSubject(connection);
    }

    private void waitConnected() throws IOException {
        synchronized (stateLock) {
            if (state == CONNECTED) {
                return;
            } else if (state != CONNECTING) {
                throw new IOException("The connection was closed or failed.");
            }

            final long startTime = System.currentTimeMillis();
            long remainingTime = waitConnectedState;

            while (state == CONNECTING && waitConnectedState > 0) {

                try {
                    stateLock.wait(remainingTime);
                } catch (InterruptedException ire) {
                    break;
                }

                remainingTime = waitConnectedState - (System.currentTimeMillis() - startTime);
            }

            if (state != CONNECTED) {
                throw new IOException("The connection is not connected.");
            }
        }
    }

//----------------------------------------------
// private variables
//----------------------------------------------
    /**
     * This lock used to ensures no concurrent writes
     */
    private final transient int[] connectionLock = new int[0];
    private transient MessageConnection connection;

    private transient ServerAdmin serverAdmin = null;

    private Map env;

    private transient SynchroCallback callback;
    private transient ThreadService threads;
    private transient MessageReader reader;

    // state issues
    private static final int UNCONNECTED = 1;
    private static final int CONNECTING = 2;
    private static final int CONNECTED = 3;
    private static final int FAILED = 4;
    private static final int TERMINATED = 5;

    private int state = UNCONNECTED;

    /**
     * Used to control access to the state variable, including ensuring that only one thread manages state transitions.
     */
    private final int[] stateLock = new int[0];

    private long waitConnectedState;

    private final ClassLogger LOGGER = new ClassLogger("javax.management.remote.misc", "ServerSynchroMessageConnectionImpl");

    //----------------------------------------------
// private classes
//----------------------------------------------
    private class MessageReader implements Runnable {

        public MessageReader() {
        }

        @Override
        public void run() {
            try {
                executingThread = Thread.currentThread();

                Message msg;

                while (!stopped()) {
                    LOGGER.fine("MessageReader-run", "Waiting for an incoming message...");

                    try {
                        msg = (Message) connection.readMessage();
                        LOGGER.fine("MessageReader-run", "Received a message of type: " + msg);
                    } catch (Exception e) {
                        if (stopped()) {
                            break;
                        }
                        LOGGER.warning("MessageReader-run", ">>>> " + connection + " got exception", e);

                        callback.connectionException(e);

                        // if reconnected, a new reader should be created.
                        break;
                    }

                    if (stopped()) {
                        break;
                    }

                    threads.handoff(new RemoteJob(msg));

                    if (msg instanceof CloseMessage) {
                        LOGGER.fine("MessageReader-run", "==== " + connection + " received close request");
                        break;
                    }
                }
            } catch (Exception eee) {
                // need to stop
                LOGGER.warning("MessageReader-run", "Need to stop because of exception", eee);
            }

            synchronized (stateLock) {
                executingThreadInterrupted = true;
            }

            LOGGER.trace("MessageReader-run", "ended.");
        }

        public void stop() {
            LOGGER.trace("MessageReader-terminated", "be called.");

            synchronized (stateLock) {
                if (Thread.currentThread() != executingThread
                        && executingThread != null
                        && !executingThreadInterrupted) {

                    executingThreadInterrupted = true;

                    executingThread.interrupt();
                }
            }

            LOGGER.trace("MessageReader-terminated", "done.");
        }

        private boolean stopped() {
            synchronized (stateLock) {
                return (state != CONNECTED || executingThreadInterrupted);
            }
        }

        private Thread executingThread;

        // This flag is used to ensure that we interrupt the executingThread
        // only when it is running in this MessageReader object.
        private boolean executingThreadInterrupted = false;
    }

    private class RemoteJob implements Runnable {

        public RemoteJob(Message msg) {
            this.msg = msg;
        }

        @Override
        public void run() {
            LOGGER.trace("RemoteJob-run", "Received a new request: " + msg);

            try {
                Message resp = callback.execute(msg);

                if (resp != null) {
                    synchronized (connectionLock) {
                        connection.writeMessage(resp);
                    }
                }
            } catch (Exception ie) {
                synchronized (stateLock) {
                    // Check if we are still connected. Ignore exceptions otherwise: another thread could have already disconnected the connector
                    if (state == CONNECTED && callback != null) {
                        LOGGER.warning("RemoteJob-run", ">>>> " + connection + " got exception: {" + ie + "} while executing message: " + msg);
                        // inform the callback
                        callback.connectionException(ie);
                    }
                }
            }
        }

        private final Message msg;
    }
}
