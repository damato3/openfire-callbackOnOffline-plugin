package com.fotsum;

import org.dom4j.Element;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.*;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.LocaleUtils;
import org.jivesoftware.util.StringUtils;
import org.jivesoftware.util.XMPPDateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class CallbackOnOffline implements Plugin, PacketInterceptor {

    private static final Logger Log = LoggerFactory.getLogger(CallbackOnOffline.class);

    private static final String PROPERTY_DEBUG = "plugin.callback_on_offline.debug";
    private static final String PROPERTY_URL = "plugin.callback_on_offline.url";
    private static final String PROPERTY_TOKEN = "plugin.callback_on_offline.token";
    private static final String PROPERTY_SEND_BODY = "plugin.callback_on_offline.send_body";

    private boolean debug;
    private boolean sendBody;

    private String url;
    private String token;
    private InterceptorManager interceptorManager;
    private UserManager userManager;
    private PresenceManager presenceManager;
    private Client client;
    private OfflineMessageStrategy offlineMessageStrategy;

    public void initializePlugin(PluginManager pManager, File pluginDirectory) {
        debug = JiveGlobals.getBooleanProperty(PROPERTY_DEBUG, false);
        sendBody = JiveGlobals.getBooleanProperty(PROPERTY_SEND_BODY, true);

        url = getProperty(PROPERTY_URL, "http://localhost/user/offline/callback/url");
        token = getProperty(PROPERTY_TOKEN, UUID.randomUUID().toString());

        logDebug("initialize CallbackOnOffline plugin. Start.");
        logDebug("Loaded properties: \nurl={}, \ntoken={}, \nsendBody={}", new Object[]{url, token, sendBody});

        interceptorManager = InterceptorManager.getInstance();
        presenceManager = XMPPServer.getInstance().getPresenceManager();
        userManager = XMPPServer.getInstance().getUserManager();
        client = ClientBuilder.newClient();
        offlineMessageStrategy = XMPPServer.getInstance().getOfflineMessageStrategy();

        // register with interceptor manager
        interceptorManager.addInterceptor(this);

        logDebug("initialize CallbackOnOffline plugin. Finish.");
    }

    private String getProperty(String code, String defaultSetValue) {
        String value = JiveGlobals.getProperty(code, null);
        if (value == null || value.length() == 0) {
            JiveGlobals.setProperty(code, defaultSetValue);
            value = defaultSetValue;
        }

        return value;
    }

    public void destroyPlugin() {
        // unregister with interceptor manager
        interceptorManager.removeInterceptor(this);
        logDebug("destroy CallbackOnOffline plugin.");
    }


    public void interceptPacket(Packet packet, Session session, boolean incoming,
                                boolean processed) throws PacketRejectedException {
        if (processed
            && incoming
            && packet instanceof Message
            && packet.getTo() != null) {

            Message msg = (Message) packet;
            JID to = packet.getTo();

            if (msg.getType() != Message.Type.chat || msg.getBody() == null || msg.getBody().isEmpty()) {
                return;
            }

            try {
                User userFrom = userManager.getUser(packet.getFrom().getNode());
                User userTo = userManager.getUser(to.getNode());

                boolean available = presenceManager.isAvailable(userTo);

                // Quick check to determine user availability
                if (!available) {
                    sendNotification(msg, userFrom, userTo, to);
                }
                else {
                    new Thread(() -> {
                        try {
                            logDebug("Wait 7 seconds before we verify if the message was actually sent to an available user");
                            TimeUnit.SECONDS.sleep(7);
                            sendNotification(msg, userFrom, userTo, to);
                            saveOfflineMessage(msg, userFrom, userTo, to);
                        } catch (InterruptedException ie) {
                            logDebug("Error saving offline message");
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            } catch (UserNotFoundException e) {
                logDebug("can't find user with name: " + to.getNode());
            }
        }
    }

    public boolean sendNotification(Message message, User userFrom, User userTo, JID to) {
        boolean available = presenceManager.isAvailable(userTo);

        logDebug("intercepted message from {} to {}, recipient is available {}",
            new Object[]{message.getFrom().toBareJID(), to.toBareJID(), available});

        if (!available) {
            logDebug("Saving and calling Offline URL...");
            String body = sendBody ? message.getBody() : null;

            WebTarget target = client.target(url);

            logDebug("sending request to url='{}'", target);

            MessageData data = new MessageData(userFrom.getName(), to.toBareJID(), body);

            logDebug("Sending notification: " + data.toString());

            Future<Response> responseFuture = target
                .request()
                .header("Authorization", token)
                .async()
                .post(Entity.json(data));

            if (debug) {
                try {
                    Response response = responseFuture.get();
                    logDebug("got response status url='{}' status='{}'", target, response.getStatus());
                } catch (Exception e) {
                    logDebug("can't get response status url='{}'", target, e);
                }
            }
        }

        return available;
    }

    /**
     * Store the message offline so the user can receive it later.
     *
     * @param message message instance
     * @param userFrom user 'from' instance
     * @param userTo user 'to' instance
     * @param to user JID instance
     */
    public void saveOfflineMessage(Message message, User userFrom, User userTo, JID to) {
        try {
            offlineMessageStrategy.storeOffline(message);
        } catch (Exception e) {
            logDebug("Error saving offline message");
        }
    }

    /**
     * Helper method to log Debug messages.
     *
     * @param message String message
     * @param objects parameters
     */
    private void logDebug(String message, Object[] objects) {
        if (debug) {
            Log.debug(message, objects);
        }
    }

    private void logDebug(String message, Object object) {
        logDebug(message, new Object[]{object});
    }

    private void logDebug(String message, Object object, Object object2) {
        logDebug(message, new Object[]{object, object2});
    }

    private void logDebug(String message) {
        logDebug(message, null);
    }

}
