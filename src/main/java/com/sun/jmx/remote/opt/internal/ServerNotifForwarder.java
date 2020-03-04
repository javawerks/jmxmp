/*
 * @(#)ServerNotifForwarder.java	1.3
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
package com.sun.jmx.remote.opt.internal;

import com.sun.jmx.remote.opt.util.ClassLogger;
import com.sun.jmx.remote.opt.util.EnvHelp;
import java.io.IOException;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.management.InstanceNotFoundException;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanPermission;
import javax.management.MBeanServer;
import javax.management.NotificationBroadcaster;
import javax.management.NotificationFilter;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.NotificationResult;
import javax.management.remote.TargetedNotification;

public class ServerNotifForwarder {

    public ServerNotifForwarder(MBeanServer mbeanServer, Map env, NotificationBuffer notifBuffer) {
        this.mbeanServer = mbeanServer;

        this.notifBuffer = notifBuffer;

        connectionTimeout = EnvHelp.getServerConnectionTimeout(env);
    }

    public Integer addNotificationListener(final ObjectName name, final NotificationFilter filter) throws InstanceNotFoundException, IOException {

        LOGGER.trace("addNotificationListener", "Add a listener with ObjectName " + name + " and filter " + filter);

        checkState();

        // Explicitly check MBeanPermission for addNotificationListener
        //
        checkMBeanPermission(name, "addNotificationListener");

        try {
            Boolean instanceOf = (Boolean) AccessController.doPrivileged(new PrivilegedExceptionAction() {
                @Override
                public Object run() throws InstanceNotFoundException {
                    return new Boolean(
                            mbeanServer.isInstanceOf(name,
                                    BROADCASTER_CLASS));
                }
            });
            if (!instanceOf) {
                throw new IllegalArgumentException("The specified MBean [" + name + "] is not a NotificationBroadcaster object.");
            }
        } catch (PrivilegedActionException e) {
            throw (InstanceNotFoundException) extractException(e);
        }

        final Integer id = getListenerID();
        synchronized (listenerList) {
            listenerList.add(new ListenerInfo(id, name, filter));
        }

        return id;
    }

    public void removeNotificationListener(ObjectName name, Integer[] listenerIDs) throws Exception {

        LOGGER.trace("removeNotificationListener", "Remove some listeners from " + name);

        checkState();

        // Explicitly check MBeanPermission for removeNotificationListener
        //
        checkMBeanPermission(name, "removeNotificationListener");

        Exception re = null;
        for (int i = 0; i < listenerIDs.length; i++) {
            try {
                removeNotificationListener(name, listenerIDs[i]);
            } catch (Exception e) {
                // Give back the first exception
                //
                if (re != null) {
                    re = e;
                }
            }
        }
        if (re != null) {
            throw re;
        }
    }

    public void removeNotificationListener(ObjectName name, Integer listenerID)
            throws
            InstanceNotFoundException,
            ListenerNotFoundException,
            IOException {

        LOGGER.trace("removeNotificationListener", "Remove the listener " + listenerID + " from " + name);

        checkState();

        if (name != null && !name.isPattern()) {
            if (!mbeanServer.isRegistered(name)) {
                throw new InstanceNotFoundException("The MBean " + name + " is not registered.");
            }
        }

        synchronized (listenerList) {
            if (!listenerList.remove(new ListenerInfo(listenerID, name, null))) {
                throw new ListenerNotFoundException("Listener not found!");
            }
        }
    }

    public NotificationResult fetchNotifs(long startSequenceNumber, long timeout, int maxNotifications) {
        LOGGER.trace("fetchNotifs", "Fetching notifications for request startSequenceNumber=" + startSequenceNumber + ", timeout=" + timeout + ", maxNotifications=" + maxNotifications);

        NotificationResult nr;
        final long t = Math.min(connectionTimeout, timeout);
        LOGGER.debug("fetchNotifs", "will use timeout=" + t);

        try {
            nr = notifBuffer.fetchNotifications(listenerList,
                    startSequenceNumber,
                    t, maxNotifications);
        } catch (InterruptedException ire) {
            nr = new NotificationResult(0L, 0L, new TargetedNotification[0]);
        }
        LOGGER.trace("fetchNotifs", "Forwarding the notifs: " + nr);

        return nr;
    }

    public void terminate() {
        LOGGER.trace("terminate", "Be called.");

        synchronized (terminationLock) {
            if (terminated) {
                return;
            }

            terminated = true;

            synchronized (listenerList) {
                listenerList.clear();
            }
        }
        LOGGER.trace("terminate", "Terminated.");
    }

    //----------------
    // PRIVATE METHODS
    //----------------
    private void checkState() throws IOException {
        synchronized (terminationLock) {
            if (terminated) {
                throw new IOException("The connection has been terminated.");
            }
        }
    }

    private Integer getListenerID() {
        synchronized (LISTENER_COUNTER_LOCK) {
            return listenerCounter++;
        }
    }

    /**
     * Explicitly check the MBeanPermission for the current access control context.
     */
    private void checkMBeanPermission(final ObjectName name, final String actions) throws InstanceNotFoundException, SecurityException {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            AccessControlContext acc = AccessController.getContext();
            ObjectInstance oi = null;
            try {
                oi = (ObjectInstance) AccessController.doPrivileged(new PrivilegedExceptionAction() {
                    @Override
                    public Object run()
                            throws InstanceNotFoundException {
                        return mbeanServer.getObjectInstance(name);
                    }
                });
            } catch (PrivilegedActionException e) {
                throw (InstanceNotFoundException) extractException(e);
            }
            String classname = oi.getClassName();
            MBeanPermission perm = new MBeanPermission(classname, null, name, actions);
            sm.checkPermission(perm, acc);
        }
    }

    /**
     * Iterate until we extract the real exception from a stack of PrivilegedActionExceptions.
     */
    private static Exception extractException(Exception e) {
        while (e instanceof PrivilegedActionException) {
            e = ((PrivilegedActionException) e).getException();
        }
        return e;
    }

    //------------------
    // PRIVATE VARIABLES
    //------------------
    private final MBeanServer mbeanServer;

    private final long connectionTimeout;

    private static int listenerCounter = 0;
    private static final int[] LISTENER_COUNTER_LOCK = new int[0];

    private final NotificationBuffer notifBuffer;
    private final Set listenerList = new HashSet();

    private boolean terminated = false;
    private final int[] terminationLock = new int[0];

    private static final String BROADCASTER_CLASS = NotificationBroadcaster.class.getName();

    private static final ClassLogger LOGGER = new ClassLogger("javax.management.remote.misc", "ServerNotifForwarder");
}
