/**
 * Copyright (c) 2016-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.flicbutton.handler;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.flicbutton.FlicButtonBindingConstants;
import org.openhab.binding.flicbutton.internal.discovery.FlicButtonDiscoveryService;
import org.openhab.binding.flicbutton.internal.discovery.FlicButtonPassiveDiscoveryService;
import org.openhab.binding.flicbutton.internal.util.FlicButtonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import io.flic.fliclib.javaclient.Bdaddr;

/**
 * The {@link FlicDaemonBridgeHandler} handles a running instance of the fliclib-linux-hci server (flicd).
 *
 * @author Patrick Fink - Initial contribution
 */
public class FlicDaemonBridgeHandler extends BaseBridgeHandler {
    private static final Logger logger = LoggerFactory.getLogger(FlicDaemonBridgeHandler.class);
    private static final long REINITIALIZE_DELAY_SECONDS = 10;
    // Config parameters
    private FlicDaemonBridgeConfiguration cfg;
    // Services
    private ListeningExecutorService listeningScheduler = MoreExecutors.listeningDecorator(scheduler);
    private FlicButtonDiscoveryService buttonDiscoveryService;
    private ListenableFuture flicClientFuture;
    // For disposal
    private Collection<Future> startedTasks = new ArrayList<Future>(2);

    public FlicDaemonBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    public Thing getFlicButtonThing(Bdaddr bdaddr) {
        ThingUID flicButtonUID = FlicButtonUtils.getThingUIDFromBdAddr(bdaddr, thing.getUID());
        return this.getThingByUID(flicButtonUID);
    }

    @Override
    public void initialize() {
        logger.debug("Initialize Fliclib bridge");

        try {
            initConfigParameters();
            initButtonDiscoveryService();
            startFlicdClientAsync();
            initThingStatus();
        } catch (UnknownHostException ignored) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Hostname wrong or unknown!");
            return;
        }
    }

    private void initConfigParameters() throws UnknownHostException {
        Object hostConfigRaw = thing.getConfiguration().get(FlicButtonBindingConstants.CONFIG_HOST_NAME);
        Object portConfigRaw = thing.getConfiguration().get(FlicButtonBindingConstants.CONFIG_PORT);
        cfg = new FlicDaemonBridgeConfiguration(hostConfigRaw, portConfigRaw);
    }

    private void initButtonDiscoveryService() {
        buttonDiscoveryService = new FlicButtonPassiveDiscoveryService(thing.getUID());
        buttonDiscoveryService.start(bundleContext);
    }

    private void startFlicdClientAsync() throws UnknownHostException {
        FlicDaemonBridgeEventListener flicDaemonEventListener = new FlicDaemonBridgeEventListener(this);
        FlicDaemonClientRunner flicClientService = new FlicDaemonClientRunner(flicDaemonEventListener,
                cfg.getHostname(), cfg.getPort());

        flicClientFuture = listeningScheduler.submit(flicClientService);
        flicClientFuture.addListener(() -> onClientFailure(), scheduler);
        startedTasks.add(flicClientFuture);
    }

    private void onClientFailure() {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                "flicd client terminated, probably flicd is not reachable.");
        dispose();
        scheduleReinitialize();
    }

    private void initThingStatus() {
        if (!flicClientFuture.isDone()) {
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "flicd client could not be started, probably flicd is not reachable.");
        }
    }

    @Override
    public void dispose() {
        for (Future startedTask : startedTasks) {
            if (!startedTask.isDone()) {
                startedTask.cancel(true);
            }
        }
        startedTasks = new ArrayList<Future>(2);
    }

    private void scheduleReinitialize() {
        startedTasks.add(scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                initialize();
            }
        }, REINITIALIZE_DELAY_SECONDS, TimeUnit.SECONDS));
    }

    FlicButtonDiscoveryService getButtonDiscoveryService() {
        return this.buttonDiscoveryService;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // No commands to the fliclib-linux-hci are supported.
        // So there is nothing to handle in the bridge handler
    }
}
