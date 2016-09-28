package com.dating.mechat.service;

import android.app.ActivityManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;
import android.widget.RemoteViews;

import com.dating.mechat.ChatActivity;
import com.dating.mechat.HiApplication;
import com.dating.mechat.MainActivity;
import com.dating.mechat.R;
import com.dating.mechat.database.ChatDatasource;
import com.dating.mechat.database.ContactDatasource;
import com.dating.mechat.utils.MyGlobals;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.SASLAuthentication;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.chat.Chat;
import org.jivesoftware.smack.chat.ChatManager;
import org.jivesoftware.smack.chat.ChatManagerListener;
import org.jivesoftware.smack.chat.ChatMessageListener;
import org.jivesoftware.smack.filter.MessageTypeFilter;
import org.jivesoftware.smack.filter.StanzaFilter;
import org.jivesoftware.smack.filter.StanzaTypeFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager;
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest;
import org.jivesoftware.smackx.receipts.ReceiptReceivedListener;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

public class MessengerService extends IntentService {
    private final String CHAT_NOTIFICATION = "chatnotifaction", DRAWABLE = "drawable";
    private final String ALL_MESSAGE_READ = "all_msg_read_ack";
    private boolean connectedToChatServer;
    private XMPPTCPConnectionConfiguration.Builder CONN_CONFIG = XMPPTCPConnectionConfiguration.builder();
    private AbstractXMPPConnection XMPP_CONNECTION;
    private ActivityManager activityManager;
    private ChatDatasource chatDatasource;
    private ComponentName componentName;
    private ContactDatasource contactDatasource;
    private HiApplication hiApplication;
    private IBinder mBinder;
    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mNotifyBuilder;
    private String openFirePassword;
    private String openFireUsername;
    private SharedPreferences sharedPreferences;
    private Context applicationContext;
    private String CHAT_ACTIVITY_NAME;
    private String applicationUsername;

    private XmppStanzaListener xmppStanzaListener;
    private DeliveryReceiptManager deliveryReceiptManager;

	
	/*static {
		try {
			Class.forName("org.jivesoftware.smack.ReconnectionManager");
		} catch (ClassNotFoundException localClassNotFoundException) {
			localClassNotFoundException.printStackTrace();
		}
	}*/


    public MessengerService() {
        super("messengerService");
        this.mBinder = new LocalBinder();
    }

    public void onCreate() {
        super.onCreate();
        SmackConfiguration.DEBUG = true;
        this.hiApplication = (HiApplication) getApplication();

        this.sharedPreferences = getSharedPreferences(MyGlobals.PREFERENCE_NAME,
                MODE_PRIVATE);
        this.openFireUsername = String.valueOf(this.sharedPreferences.getInt(
                MyGlobals.PREFERENCE_USER_ID, -1));
        this.openFirePassword = this.sharedPreferences.getString(MyGlobals.PREFERENCE_PASSWORD,
                null);

        applicationUsername = this.sharedPreferences.getString(MyGlobals.PREFERENCE_CHATUSERNAME, null);
        this.activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

        this.chatDatasource = new ChatDatasource(this);
        this.chatDatasource.open();
        this.contactDatasource = new ContactDatasource(this);
        this.contactDatasource.open();

        CHAT_ACTIVITY_NAME = hiApplication.getPackageName() + ".ChatActivity";
        setXmppConnection();

    }

    public int onStartCommand(Intent paramIntent, int paramInt1, int paramInt2) {
        this.mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        this.mNotifyBuilder = new NotificationCompat.Builder(this);
        applicationContext = getApplicationContext();
        connectToChatServer();
        return START_STICKY;
    }

    private void setXmppConnection() {
        if ( this.XMPP_CONNECTION == null ) {
            CONN_CONFIG.setSecurityMode(ConnectionConfiguration.SecurityMode.disabled);
            CONN_CONFIG.setPort(MyGlobals.PORT);
            CONN_CONFIG.setHost(MyGlobals.HOST);
            CONN_CONFIG.setServiceName(MyGlobals.HOST);
            CONN_CONFIG.setDebuggerEnabled(true);
            XMPP_CONNECTION = new XMPPTCPConnection(CONN_CONFIG.build());
            xmppStanzaListener = new XmppStanzaListener();
            StanzaFilter filter = new StanzaTypeFilter(Message.class);
            deliveryReceiptManager = DeliveryReceiptManager.getInstanceFor(XMPP_CONNECTION);
            deliveryReceiptManager.addReceiptReceivedListener( new MessageRecieptListener() );
            //XMPP_CONNECTION.addAsyncStanzaListener( xmppStanzaListener,filter );
            ChatManager chatManager = ChatManager.getInstanceFor(XMPP_CONNECTION);
            chatManager.addChatListener( new ChatManagerListener() {
                @Override
                public void chatCreated(Chat chat, boolean createdLocally) {
                    if (!createdLocally)
                        chat.addMessageListener(new MyNewMessageListener());
                }
            });
        }
    }

    private long saveChatinDatabase(String messageSenderChatUsername,
                                    String messageReciverChatusername, int readStatus, String date,
                                    String chatWithUser, String chatMessage) {
        return this.chatDatasource.saveChat(messageSenderChatUsername,
                messageReciverChatusername, readStatus, date, chatWithUser,
                chatMessage, "");
    }

    private void sendChatNotification(String messageSenderFullName,
                                      String message, int notificationId) {
        SharedPreferences localSharedPreferences = getSharedPreferences(MyGlobals.PREFERENCE_NAME, Context.MODE_PRIVATE);
        boolean isLogin = localSharedPreferences.getBoolean(MyGlobals.PREFERENCE_LOGIN, false);
        if (!isLogin) return;
        Intent localIntent1 = new Intent(this, MainActivity.class);
        localIntent1.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        localIntent1.putExtra(CHAT_NOTIFICATION, true);
        PendingIntent localPendingIntent = PendingIntent.getActivity(this, 0,
                localIntent1, PendingIntent.FLAG_CANCEL_CURRENT);

        String str1 = "Message from " + messageSenderFullName;
        this.mNotifyBuilder.setSmallIcon(R.drawable.fabicon);
        this.mNotifyBuilder.setContentIntent(localPendingIntent);
        this.mNotifyBuilder.setAutoCancel(true);

        boolean vibrate = localSharedPreferences.getBoolean(MyGlobals.PREFERENCE_VIBRATE, false);
        boolean tone = localSharedPreferences.getBoolean(MyGlobals.PREFERENCE_TONE, false);

        int k = 0;
        if (tone) k = k | Notification.DEFAULT_SOUND;
        else this.mNotifyBuilder.setSound(null);

        if (vibrate) k = k | Notification.DEFAULT_VIBRATE;

        this.mNotifyBuilder.setDefaults(k);


        Spanned localSpanned = Html.fromHtml(message, new Html.ImageGetter() {

            @Override
            public Drawable getDrawable(String source) {
                Resources localResources = applicationContext.getResources();
                String str1 = applicationContext.getPackageName();
                int i = localResources.getIdentifier(source, DRAWABLE, str1);
                Drawable localDrawable = localResources.getDrawable(i);
                int k = localDrawable.getIntrinsicWidth();
                int m = localDrawable.getIntrinsicHeight();
                localDrawable.setBounds(0, 0, k, m);
                return localDrawable;
            }
        }, null);

        RemoteViews contentView = new RemoteViews(getPackageName(), R.layout.custom_notification);
        contentView.setImageViewResource(R.id.image, R.drawable.fabicon);
        contentView.setTextViewText(R.id.textView6, localSpanned);
        contentView.setTextViewText(R.id.textView5, str1);
        this.mNotifyBuilder.setContent(contentView);
        //this.mNotifyBuilder.setContentText(localSpanned);
        Notification localNotification = this.mNotifyBuilder.build();
        this.mNotificationManager.notify(notificationId, localNotification);
        return;
    }


    public void connectToChatServer() {
        new AsyncTask<String, Void, Void>() {
            @Override
            protected Void doInBackground(String... params) {
                try {
                    if (XMPP_CONNECTION.isConnected()) {
                        if (XMPP_CONNECTION.isAuthenticated()) {
                            //XMPP_CONNECTION.addPacketListener(xmppPacketListener, localMessageTypeFilter);
                            Presence presence = new Presence(Presence.Type.available);
                            presence.setMode(Presence.Mode.available);
                            try {
                                XMPP_CONNECTION.sendPacket(presence);
                            } catch (IllegalStateException s) {
                                s.printStackTrace();
                            } catch (SmackException.NotConnectedException e) {
                                e.printStackTrace();
                            }

                            connectedToChatServer = true;
                        } else {
                            SASLAuthentication.unBlacklistSASLMechanism("PLAIN");
                            SASLAuthentication.blacklistSASLMechanism("DIGEST-MD5");
                            XMPP_CONNECTION.login(openFireUsername, openFirePassword);

                            //XMPP_CONNECTION.addPacketListener(xmppPacketListener, localMessageTypeFilter);
                            Presence presence = new Presence(Presence.Type.available);
                            presence.setMode(Presence.Mode.available);
                            XMPP_CONNECTION.sendPacket(presence);
                            connectedToChatServer = true;
                        }
                    } else {
                        XMPP_CONNECTION.connect();
                        openFireUsername = String.valueOf(sharedPreferences.getInt(
                                MyGlobals.PREFERENCE_USER_ID, -1));
                        openFirePassword = sharedPreferences.getString(MyGlobals.PREFERENCE_PASSWORD, null);
                        SASLAuthentication.unBlacklistSASLMechanism("PLAIN");
                        SASLAuthentication.blacklistSASLMechanism("DIGEST-MD5");
                        XMPP_CONNECTION.login(openFireUsername, openFirePassword);
                        //XMPP_CONNECTION.addPacketListener(xmppPacketListener, localMessageTypeFilter);
                        Presence presence = new Presence(Presence.Type.available);
                        presence.setMode(Presence.Mode.available);
                        XMPP_CONNECTION.sendPacket(presence);
                        connectedToChatServer = true;
                    }
                } catch (IllegalStateException i) {
                    //XMPP_CONNECTION.addPacketListener(xmppPacketListener, localMessageTypeFilter);
                    Presence presence = new Presence(Presence.Type.available);
                    presence.setMode(Presence.Mode.available);
                    try {
                        XMPP_CONNECTION.sendPacket(presence);
                        connectedToChatServer = true;
                    } catch (SmackException.NotConnectedException e) {
                        e.printStackTrace();
                    }

                    i.printStackTrace();
                } catch (XMPPException localXMPPException) {
                    localXMPPException.printStackTrace();
                } catch (Error localError) {
                } catch (SmackException.NotConnectedException e) {
                    e.printStackTrace();
                } catch (SmackException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }

        }.execute(null, null, null);
    }

    public boolean isConnectedToChatServer() {
        return connectedToChatServer;
    }



    public AbstractXMPPConnection getXmppConnection() {
        if (this.XMPP_CONNECTION == null)
            setXmppConnection();
        return this.XMPP_CONNECTION;
    }

    public IBinder onBind(Intent paramIntent) {
        return this.mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        chatDatasource.close();
        contactDatasource.close();
        Log.d("messenger", "messenger service destroyed ");
    }

    @Override
    protected void onHandleIntent(Intent paramIntent) {
    }


    private void updateAndNotify(String senderChatUsername) {
        int rowAffected = chatDatasource.updateRecieverReadStatus(
                senderChatUsername, this.applicationUsername);
        if (rowAffected > 0) {
            @SuppressWarnings("deprecation")
            List<ActivityManager.RunningTaskInfo> localList = activityManager
                    .getRunningTasks(1);
            this.componentName = localList.get(0).topActivity;
            if (CHAT_ACTIVITY_NAME.equals(componentName
                    .getClassName())) {
                if (senderChatUsername
                        .equals(ChatActivity.CHAT_WITH_USERNAME)) {
                    Intent localIntent1 = new Intent(ALL_MESSAGE_READ);
                    sendBroadcast(localIntent1);
                }
            }
        }
    }

    public void reconnectingIn( int paramInt ) {
    }

    public void reconnectionFailed(Exception paramException) {
    }

    public void reconnectionSuccessful() {
        connectedToChatServer = true;
    }

    public class LocalBinder extends Binder {
        public LocalBinder() {
        }

        public MessengerService getServerInstance() {
            return MessengerService.this;
        }
    }



    class MyNewMessageListener implements ChatMessageListener {

        @Override
        public void processMessage(Chat chat, Message message) {
            String messageBody = message.getBody();
            try {
                JSONObject messageJson = new JSONObject(messageBody);
                int messageType = messageJson.getInt("message_type") ;
                if( messageType == 1 ){
                    // For chat messages
                    String messageContent = messageJson
                            .getString("message");
                    String time = messageJson.getString("currentTime");
                    String reciverChatUsername = messageJson
                            .getString("receiverUsername");
                    String senderChatUsername = messageJson
                            .getString("senderUsername");
                    String messageSenderFullname = messageJson
                            .getString("messageSenderFullName");
                    long messageID  = messageJson.getLong("messageID");
                    if ( hiApplication.getBlockedUserList().contains(
                            senderChatUsername ));
                    ComponentName localComponentName = ((ActivityManager.RunningTaskInfo) activityManager.
                            getRunningTasks(1).get(0)).topActivity;
                    componentName = localComponentName;
                    if ( componentName.getClassName().equals(
                            CHAT_ACTIVITY_NAME )) {
                    /* chat activity is open , check if both chatting to each other */

                        int readStatus = 1;
                        if ( senderChatUsername
                                .equals(ChatActivity.CHAT_WITH_USERNAME )) {
                            // both are chating to each other.
                            long newRowID = saveChatinDatabase(
                                    senderChatUsername,
                                    reciverChatUsername, readStatus, time,
                                    senderChatUsername, messageContent );
                            if ( newRowID > 0) {
                                Message status = new Message(message.getFrom());
                                JSONObject json = new JSONObject();
                                json.put("messageID",messageID);
                                json.put("status",MyGlobals.READ);
                                json.put("reciverChatUsername", reciverChatUsername );

                                status.setBody(json.toString());
                                DeliveryReceiptRequest.addTo(status);
                                try {
                                    XMPP_CONNECTION.sendStanza(status);
                                } catch (SmackException.NotConnectedException e) {
                                    e.printStackTrace();
                                }

                                Intent newMessageIntent = new Intent(
                                        "new_chat_message_recieved");
                                newMessageIntent.putExtra("messageData",
                                        messageBody);
                                newMessageIntent.putExtra("newMessageID",
                                        newRowID);
                                sendBroadcast(newMessageIntent);
                            }

                        } else {
                            long newRowID = saveChatinDatabase( senderChatUsername,
                                    reciverChatUsername, 0, time,
                                    senderChatUsername, messageContent);
                            if( newRowID > 0 ){
                                Message status = new Message(message.getFrom());
                                JSONObject json = new JSONObject();
                                json.put("messageID",messageID);
                                json.put("status",MyGlobals.RECEIVED );
                                json.put("reciverChatUsername", reciverChatUsername );
                                status.setBody(json.toString());
                                DeliveryReceiptRequest.addTo(status);
                                try {
                                    XMPP_CONNECTION.sendStanza(status);
                                } catch (SmackException.NotConnectedException e) {
                                    e.printStackTrace();
                                }
                                int notificationId = 0 ;
                                String notificationID = senderChatUsername.substring(0, senderChatUsername.indexOf('@'));
                                try{
                                    notificationId = Integer.parseInt(notificationID);
                                }catch (NumberFormatException e){
                                    e.printStackTrace();
                                }

                                sendChatNotification(messageSenderFullname, messageContent,notificationId);
                            }
                        }
                    } else {
                        long newRowId = saveChatinDatabase(senderChatUsername,
                                reciverChatUsername, 0, time,
                                senderChatUsername, messageContent);
                        if(newRowId > 0 ){
                            Message status = new Message(message.getFrom());
                            JSONObject json = new JSONObject();
                            json.put("messageID",messageID);
                            json.put("status",MyGlobals.RECEIVED );
                            json.put("reciverChatUsername", reciverChatUsername );
                            status.setBody(json.toString());
                            DeliveryReceiptRequest.addTo(status);
                            try {
                                XMPP_CONNECTION.sendStanza(status);
                            } catch (SmackException.NotConnectedException e) {
                                e.printStackTrace();
                            }
                            int notificationId = 0;
                            String notificationID = senderChatUsername.substring(0, senderChatUsername.indexOf("@"));
                            try{
                                notificationId = Integer.parseInt(notificationID);
                            }catch (NumberFormatException e){
                                e.printStackTrace();
                            }

                            sendChatNotification(messageSenderFullname,messageContent,notificationId);
                        }
                    }
                }else if( messageType == 2 ){
                    Log.d("tag","message type 2 ");
                    // Save new user and read message handled here.
                    try {
                        JSONObject json = new JSONObject(messageBody);
                        int id = json.getInt("userID");
                        String fullName = json.getString("fullName");
                        String email = json.getString("email");
                        String imagePath = json.getString("imagePath");
                        String gender = json.getString("gender");
                        double latitude = json.getDouble("latitude");
                        double longitude = json.getDouble("longitude");
                        String aboutMe = json.getString("aboutMe");
                        String occupation = json.getString("occupation");
                        String socialLink = json.getString("socialLink");
                        String birthday = json.getString("birthday");
                        String senderChatUsername = json.getString("senderChatUsername");
                        boolean isUserFound = contactDatasource.userExists(String.valueOf(id));
                        if(!isUserFound){
                            long newRowID = contactDatasource.saveContact(id, fullName, email, imagePath, gender, birthday, latitude, longitude, senderChatUsername, aboutMe, occupation, socialLink);
                            if(newRowID >  0 ){
                                updateAndNotify(senderChatUsername);
                            }
                        }else updateAndNotify(senderChatUsername);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }


            } catch (JSONException e) {
                e.printStackTrace();
            }

        }
    }

    //Instant message read listener
    private  class MessageRecieptListener implements ReceiptReceivedListener{

        @Override
        public void onReceiptReceived(String fromJid, String toJid, String receiptId, Stanza receipt) {
            Message msg = (Message) receipt;
            String response = msg.getBody();
            try {
                JSONObject json = new JSONObject(response);
                final String newStatus = json.getString("status");
                final int messageID = (int) json.getLong("messageID");
                final String reciverChatUsername = json.getString("reciverChatUsername");
                int updatedRowCount = chatDatasource.updateRecieverReadStatus(
                        messageID, newStatus);
                if( updatedRowCount > 0 ){
                    if( reciverChatUsername.equals(ChatActivity.CHAT_WITH_USERNAME)){
                        Intent i = new Intent(ChatActivity.SINGLE_MESSAGE_ACTION);
                        i.putExtra("messageID",messageID);
                        i.putExtra("status",newStatus );
                        sendBroadcast(i);
                    }
                }

            } catch (JSONException e) {
                e.printStackTrace();
            }

        }
    }
    // For chat send,received,read status
    private class XmppStanzaListener implements StanzaListener {

        @Override
        public void processPacket(Stanza packet) throws SmackException.NotConnectedException {
            Message msg = (Message) packet;
            String response = msg.getBody();
            try {
                JSONObject json = new JSONObject(response);
                final String newStatus = json.getString("status");
                final int messageID = (int) json.getLong("messageID");
                final String reciverChatUsername = json.getString("reciverChatUsername");
                int updatedRowCount = chatDatasource.updateRecieverReadStatus(
                        messageID, newStatus);
                if( updatedRowCount > 0 ){
                    if( reciverChatUsername.equals(ChatActivity.CHAT_WITH_USERNAME)){
                        Intent i = new Intent(ChatActivity.SINGLE_MESSAGE_ACTION);
                        i.putExtra("messageID",messageID);
                        i.putExtra("status",newStatus );
                        sendBroadcast(i);
                    }
                }

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }
}