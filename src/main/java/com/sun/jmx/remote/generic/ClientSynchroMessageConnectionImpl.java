/*
 * @(#)file      ClientSynchroMessageConnectionImpl.java
 * @(#)author    Sun Microsystems, Inc.
 * @(#)version   1.29
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
import com.sun.jmx.remote.opt.util.EnvHelp;
import com.sun.jmx.remote.opt.util.ThreadService;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.management.remote.generic.ConnectionClosedException;
import javax.management.remote.generic.MessageConnection;
import javax.management.remote.message.CloseMessage;
import javax.management.remote.message.MBeanServerRequestMessage;
import javax.management.remote.message.MBeanServerResponseMessage;
import javax.management.remote.message.Message;
import javax.management.remote.message.NotificationRequestMessage;
import javax.management.remote.message.NotificationResponseMessage;

public class ClientSynchroMessageConnectionImpl implements ClientSynchroMessageConnection {

    public ClientSynchroMessageConnectionImpl(MessageConnection mc, SynchroCallback cb, Map env) {

        if (mc == null) {
            throw new IllegalArgumentException("Null message connection.");
        }

        if (cb == null) {
            throw new IllegalArgumentException("Null SynchroCallback object.");
        }

        connection = mc;
        callback = cb;

        this.env = env;
    }

    @Override
    public void connect(Map env) throws IOException {
        synchronized (stateLock) {
            switch (state) {
                case UNCONNECTED:
                    LOGGER.trace("connect", "Establishing the connection.");

                    // first time to connect, need to merge env parameters
                    // with the one passed to the constructor.
                    Map newEnv = new HashMap();
                    if (this.env != null) {
                        newEnv.putAll(this.env);
                    }
                    if (env != null) {
                        newEnv.putAll(env);
                    }

                    wtimeout = DefaultConfig.getRequestTimeout(newEnv);
                    waitConnectedState = DefaultConfig.getTimeoutForWaitConnectedState(newEnv);

                    clientAdmin = DefaultConfig.getClientAdmin(newEnv);

                    state = CONNECTING;
                    stateLock.notifyAll();

                    connection.connect(newEnv);

                    connection = clientAdmin.connectionOpen(connection);

                    this.env = newEnv;

                    reader = new MessageReader();
                    threads = new ThreadService(1, 1);
                    threads.handoff(reader);

                    state = CONNECTED;
                    stateLock.notifyAll();
                    break;

                case FAILED:
                case CONNECTED:
                    // reconnect
                    LOGGER.trace("connect", "Re-establishing the connection...");

                    if (state == CONNECTED) {
                        state = FAILED;
                        stateLock.notifyAll();
                    }

                    state = CONNECTING;
                    stateLock.notifyAll();

                    // should stop the old reader for cleaning
                    if (reader != null) {
                        reader.stop();
                    }

                    synchronized (notifLock) {
                        notifLock.notify();
                    }

                    // Attention: lock order:
                    // stateLock before connectionLock
                    // reconnect. forbid all other requests
                    synchronized (connectionLock) {
                        connection.connect(this.env);
                        connection = clientAdmin.connectionOpen(connection);
                    }

                    // wakeup all waiting threads
                    LOGGER.trace("connect", "Wakeup the threads which are waiting a response frome the server to inform them of the connection failure.");

                    final ConnectionClosedException ce = new ConnectionClosedException("The connection has been closed by the server.");

                    // Attention: lock order:
                    // stateLock before waitingList before ResponseMsgWrapper
                    synchronized (waitingList) {
                        for (Iterator iter = waitingList.keySet().iterator();
                                iter.hasNext();) {
                            Long id = (Long) iter.next();

                            ResponseMsgWrapper rm = (ResponseMsgWrapper) waitingList.get(id);
                            synchronized (rm) {
                                if (!rm.got) { // see whether the response has arrived.
                                    rm.got = true;
                                    rm.msg = ce;
                                }
                                rm.notify();
                            }
                        }

                        waitingList.clear();
                    }

                    state = CONNECTED;

                    reader = new MessageReader();
                    threads.handoff(reader);

                    stateLock.notifyAll();
                    break;
                    
                default:
                    // is someone else calling connect()?
                    checkState();
            }
        }

        LOGGER.trace("connect", "Done");
    }

    @Override
    public void sendOneWay(Message msg) throws IOException {
        LOGGER.trace("sendOneWay", "Send a message without response: " + msg);

        checkState();

        synchronized (connectionLock) {
            connection.writeMessage(msg);
        }
    }

    @Override
    public Message sendWithReturn(Message msg) throws IOException {
        if (LOGGER.traceOn()) {
            LOGGER.trace("sendWithReturn", "Send a message with response: " + msg);
        }

        checkState();

        Message ret = null;

        if (msg instanceof NotificationRequestMessage) {
            LOGGER.trace("sendWithReturn", "Send a NotificationRequestMessage: " + msg);

            notifResp = null;

            synchronized (connectionLock) {
                connection.writeMessage(msg);
            }

            synchronized (notifLock) {
                while (notifResp == null) {
                    checkState();

                    try {
                        notifLock.wait();
                    } catch (InterruptedException ire) {
                        InterruptedIOException iioe = new InterruptedIOException(ire.toString());
                        EnvHelp.initCause(iioe, ire);
                        throw iioe;
                    }
                }

                ret = notifResp;
                notifResp = null;
            }
        } else if (msg instanceof MBeanServerRequestMessage) {
            LOGGER.trace("sendWithReturn", "Send a MBeanServerRequestMessage: " + msg);

            final Long id = ((MBeanServerRequestMessage) msg).getMessageId();

            // When receiving CloseMessage, it is possible that the server closes
            // itself by timeout, so we will do reconnection and then wakeup all
            // threads which are waiting a response by a ConnectionClosedException,
            // to ask them to try once time again, the flag "retried" is specified
            // here to tell whether the retried has done.
            // Note: if a ConnectionClosedException is thrown by the server,
            // that exception will be received by ClientIntermediary and it will
            // inform the ClientCommunicationAdmin before doing retry.
            boolean retried = false;

            while (true) {
                ResponseMsgWrapper mwrapper = new ResponseMsgWrapper();

                synchronized (waitingList) {
                    waitingList.put(id, mwrapper);
                }

                // send out the msg
                synchronized (connectionLock) {
                    connection.writeMessage(msg);
                }

                long remainingTime = wtimeout;
                final long startTime = System.currentTimeMillis();

                synchronized (mwrapper) {
                    while (!mwrapper.got && remainingTime > 0) {
                        try {
                            mwrapper.wait(remainingTime);
                        } catch (InterruptedException ie) {
                            // OK
                            // This is a user thread, so it is possible that the
                            // user wants to stop waiting.
                            break;
                        }

                        remainingTime = wtimeout - (System.currentTimeMillis() - startTime);
                    }
                }

                synchronized (waitingList) {
                    waitingList.remove(id);
                }

                // at this point mwrapper has been already removed from the waitinglist
                // and it will not be modified any more. Synchronizing on mwrapper
                // is no longer needed.
                //
                if (!mwrapper.got) {
                    if (!isTerminated()) {
                        throw new InterruptedIOException("Waiting response timeout: " + wtimeout);
                    } else {
                        throw new IOException("The connection has been closed or broken.");
                    }
                }

                if (mwrapper.msg instanceof MBeanServerResponseMessage) {
                    ret = (MBeanServerResponseMessage) mwrapper.msg;

                    break;
                } else if (mwrapper.msg instanceof ConnectionClosedException) {
                    if (isTerminated() || retried) {
                        throw (ConnectionClosedException) mwrapper.msg;
                    }

                    LOGGER.trace("sendWithReturn", "Got a local ConnectionClosedException, retry.");

                    retried = true;

                    continue;
                } else {
                    throw new IOException("Got wrong response: " + mwrapper.msg);
                }
            }
        } else {
            throw new IOException("Unknow message type: " + msg);
        }

        return ret;
    }

    @Override
    public void close() throws IOException {
        LOGGER.trace("close", "Closing this SynchroMessageConnection.");

        synchronized (stateLock) {
            if (state == TERMINATED) {
                return;
            }

            state = TERMINATED;

            LOGGER.trace("close", "Close the callback reader.");
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

            clientAdmin.connectionClosed(connection);

            // clean
            LOGGER.trace("close", "Clean all threads waiting theire responses.");

            synchronized (waitingList) {
                for (Iterator iter = waitingList.values().iterator(); iter.hasNext();) {

                    ResponseMsgWrapper rm = (ResponseMsgWrapper) iter.next();

                    final ConnectionClosedException ce = new ConnectionClosedException(
                            "The connection has been closed by the server.");
                    synchronized (rm) {
                        if (!rm.got) { // see whether the response has arrived.
                            rm.got = true;
                            rm.msg = ce;
                        }
                        rm.notify();
                    }
                }

                waitingList.clear();
            }

            stateLock.notifyAll();
        }

        // wakeup notif thread
        synchronized (notifLock) {
            int i = 0;
            notifLock.notifyAll();
        }
    }

    @Override
    public String getConnectionId() {
        // at client side, only clientAdmin can know connectionId
        // when it receives HandshakeEndMessage from its server.
        return clientAdmin.getConnectionId();
    }

    /**
     * Returns the underlying asynchronous transport.
     *
     * @return the connection
     */
    public MessageConnection getAsynchroConnection() {
        return connection;
    }

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
                        msg = null;
                        if (stopped()) {
                            break;
                        }

                        try {
                            callback.connectionException(e);
                        } catch (Exception ee) {
                            // OK.
                            // We have already informed the admin.
                        }

                        // if reconnected, a new reader should be created.
                        break;
                    }

                    if (stopped()) {
                        break;
                    }

                    if (msg instanceof NotificationResponseMessage) {
                        synchronized (notifLock) {
                            notifResp = (NotificationResponseMessage) msg;

                            notifLock.notify();
                        }
                    } else if (msg instanceof MBeanServerResponseMessage) {
                        ResponseMsgWrapper mwrapper;
                        synchronized (waitingList) {
                            mwrapper = (ResponseMsgWrapper) waitingList.get(((MBeanServerResponseMessage) msg).getMessageId());
                        }

                        if (mwrapper == null) {
                            checkState();
                            // waiting thread is timeout
                            if (LOGGER.traceOn()) {
                                LOGGER.trace("MessageReader-run", "Receive a MBeanServerResponseMessage but no one is waiting it.");
                            }
                        } else {
                            synchronized (mwrapper) {
                                mwrapper.setMsg(msg);
                                mwrapper.notify();
                            }
                        }
                    } else if (msg instanceof CloseMessage) {
                        // Hmm shouldn't we try to reopen connection actively from here, or wait for another thread
                        // for example Checker to detect server unexpected closure?
                        break;
                    } else { // unknown message, protocol error
                        // very strange... why should we resend wrong message to remote server???
                        threads.handoff(new RemoteJob(msg));
                    }
                }
            } catch (Exception eee) {
                // need to stop
                LOGGER.warning("MessageReader-run", "Need to stop because of exception: " + eee.getMessage());
                LOGGER.fine("MessageReader-run", "exception:", eee);
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

    private static class ResponseMsgWrapper {

        public boolean got = false;
        public Object msg = null;

        public ResponseMsgWrapper() {
        }

        public void setMsg(Message msg) {
            got = true;
            this.msg = msg;
        }
    }

    private class RemoteJob implements Runnable {

        public RemoteJob(Message msg) {
            this.msg = msg;
        }

        @Override
        public void run() {
            LOGGER.trace("RemoteJob-run", "Received a new request: " + msg.toString());

            try {
                Message resp = callback.execute(msg);

                if (resp != null) {
                    synchronized (connectionLock) {
                        connection.writeMessage(resp);
                    }
                }
            } catch (Exception ie) {
                synchronized (stateLock) {
                    if (state != CONNECTED && callback != null) {
                        // inform the callback
                        callback.connectionException(ie);
                    }
                }
            }
        }
        private final Message msg;
    }

//----------------------------------------------
// private methods
//----------------------------------------------
    private void checkState() throws IOException {
        synchronized (stateLock) {
            if (state == CONNECTED) {
                return;
            }

            if (state == TERMINATED) {
                throw new IOException("The connection [" + connection.getConnectionId() + "] has been closed.");
            }

            // waiting
            long remainingTime = waitConnectedState;
            final long startTime = System.currentTimeMillis();

            while (state != CONNECTED && state != TERMINATED && remainingTime > 0) {
                try {
                    stateLock.wait(remainingTime);
                } catch (InterruptedException ire) {
                    break;
                }

                remainingTime = waitConnectedState - (System.currentTimeMillis() - startTime);
            }

            if (state != CONNECTED) {
                throw new IOException("The connection [" + connection.getConnectionId() + "] is currently not established");
            }
        }
    }

    private boolean isTerminated() {
        synchronized (stateLock) {
            return (state == TERMINATED);
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
    private transient SynchroCallback callback;
    private transient ClientAdmin clientAdmin = null;
//    private final transient ServerAdmin serverAdmin = null;
//    private final transient Subject subject = null;
    private Map env;
    private transient ThreadService threads;
    private transient MessageReader reader;
    private transient long wtimeout;
    /**
     * Maps message id to ResponseMsgWrapper, locked at itself when the map is updated. A ResponseMsgWrapper is used to
     * wait for response for given request. Synchronizing on it to do wait/notify
     */
    private final transient HashMap waitingList = new HashMap();
    // notif stuff.
    private transient Message notifResp = null;
    /**
     * Controls access to notifResp field; used in wait/notify so waiting thread can be informed when a notifResp is
     * set.
     */
    private transient final int[] notifLock = new int[0];
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
    private static final ClassLogger LOGGER = new ClassLogger("javax.management.remote.misc", "ClientSynchroMessageConnectionImpl");
}
