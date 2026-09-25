package com.termux.app.terminal.remote;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.android.DeviceUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;

import java.nio.charset.StandardCharsets;

/** Bridges the "type on phone" relay between the watch and the phone, both running the same
 * Termux app.
 *
 * - On the phone: receives a {@link TermuxWearRemoteInput#MESSAGE_PATH_REQUEST} from the watch
 *   and posts a notification with a reply (RemoteInput) action. The typed text is sent back over
 *   the Wear link by {@link TermuxWearReplyReceiver}.
 *
 * - On the watch: receives the {@link TermuxWearRemoteInput#MESSAGE_PATH_REPLY} and types it into
 *   the open terminal via {@link TermuxWearRemoteInput#injectOnWatch(String)}. */
public class TermuxWearListenerService extends WearableListenerService {

    private static final int REMOTE_INPUT_NOTIFICATION_ID = 4507;
    private static final String LOG_TAG = "TermuxWearListenerService";

    @Override
    public void onCreate() {
        super.onCreate();
        // Only the Termux install on a phone advertises the input capability so the watch can find
        // it. On the watch we never advertise (we are the ones asking for input).
        if (DeviceUtils.isWatchDevice(this)) return;
        Logger.logDebug(LOG_TAG, "Advertising phone input capability");
        Wearable.getCapabilityClient(this).addLocalCapability(TermuxWearRemoteInput.CAPABILITY_PHONE_INPUT);
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        try {
            String path = messageEvent.getPath();
            Logger.logDebug(LOG_TAG, "Message received: " + path + " from node " + messageEvent.getSourceNodeId());

            if (TermuxWearRemoteInput.MESSAGE_PATH_REQUEST.equals(path)) {
                // A watch asked this (phone) install to type a command for it.
                if (DeviceUtils.isWatchDevice(this)) return;
                handlePhoneInputRequest(messageEvent.getSourceNodeId());
            } else if (TermuxWearRemoteInput.MESSAGE_PATH_REPLY.equals(path)) {
                // The phone sent back the text typed for this (watch) install.
                if (!DeviceUtils.isWatchDevice(this)) return;
                String text = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                if (text.length() == 0) return;
                Logger.logDebug(LOG_TAG, "Injecting remote input text: " + text);
                TermuxWearRemoteInput.injectOnWatch(text);
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Exception handling message: " + e.getMessage());
        }
    }

    /** On the phone: if Termux is open, collect the text with an in-app dialog (no notification
     * permission needed). Otherwise fall back to a notification with a reply box.
     *
     * This can be entered from the Data-Layer binder thread, so every call path re-centers itself on
     * the main thread before touching any view. Creating/attaching an AlertDialog from a non-main
     * thread leaves its Handler without a prepared Looper, which is what produced the
     * "app stutters, then stops responding" freeze on the phone. */
    private void handlePhoneInputRequest(@NonNull final String sourceNodeId) {
        TermuxWearRemoteInput.postOnMainThread(() -> {
            try {
                final TermuxActivity activity = TermuxWearRemoteInput.getCurrentActivity();
                if (activity != null && !activity.isFinishing()) {
                    TermuxWearRemoteInput.showPhoneInputDialog(activity, sourceNodeId);
                } else {
                    postPhoneInputNotification(sourceNodeId);
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Exception posting phone input UI: " + e.getMessage());
            }
        });
    }

    private void postPhoneInputNotification(String sourceNodeId) {
        NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_NAME, NotificationManagerCompat.IMPORTANCE_HIGH);

        Intent replyIntent = new Intent(this, TermuxWearReplyReceiver.class)
            .setAction(TermuxWearReplyReceiver.ACTION_REPLY)
            .putExtra(TermuxWearRemoteInput.EXTRA_ORIGIN_NODE_ID, sourceNodeId);

        // RemoteInput actions MUST use a mutable PendingIntent: Android writes the typed reply into
        // it, which immutable intents forbid. With FLAG_IMMUTABLE here notify() throws
        // IllegalArgumentException and the phone crashes before the notification is even shown.
        // RemoteInput actions MUST use a MUTABLE PendingIntent for the system to write the reply
        // into; immutable ones make notify() throw and crash. FLAG_MUTABLE(0x02000000) only became a
        // named constant on API 31, so write the literal value since compileSdk is 30. Unknown high
        // bits are ignored on older Android, and on API 31+ this is required. */
        PendingIntent pendingReply = PendingIntent.getBroadcast(this, sourceNodeId.hashCode(), replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | 0x02000000);

        RemoteInput remoteInput = new RemoteInput.Builder(TermuxWearReplyReceiver.KEY_REPLY_TEXT)
            .setLabel(getString(R.string.remote_input_reply_label))
            .build();

        Notification notification = new NotificationCompat.Builder(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(getString(R.string.remote_input_notification_title))
            .setContentText(getString(R.string.remote_input_notification_text))
            .setAutoCancel(true)
            .addAction(new NotificationCompat.Action.Builder(null, getString(R.string.action_type_on_phone), pendingReply)
                .addRemoteInput(remoteInput)
                .build())
            .build();

        try {
            NotificationManagerCompat.from(this).notify(REMOTE_INPUT_NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            Logger.logError(LOG_TAG, "Could not post notification, permission missing: " + e.getMessage());
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Could not post notification: " + e.getMessage());
        }
    }

}