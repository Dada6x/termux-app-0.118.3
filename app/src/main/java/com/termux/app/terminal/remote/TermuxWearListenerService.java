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
import com.termux.shared.android.DeviceUtils;
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

    @Override
    public void onCreate() {
        super.onCreate();
        // Only the Termux install on a phone advertises the input capability so the watch can find
        // it. On the watch we never advertise (we are the ones asking for input).
        if (DeviceUtils.isWatchDevice(this)) return;
        Wearable.getCapabilityClient(this).addLocalCapability(TermuxWearRemoteInput.CAPABILITY_PHONE_INPUT);
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        String path = messageEvent.getPath();

        if (TermuxWearRemoteInput.MESSAGE_PATH_REQUEST.equals(path)) {
            // A watch asked this (phone) install to type a command for it.
            if (DeviceUtils.isWatchDevice(this)) return;
            postPhoneInputNotification(messageEvent.getSourceNodeId());
        } else if (TermuxWearRemoteInput.MESSAGE_PATH_REPLY.equals(path)) {
            // The phone sent back the text typed for this (watch) install.
            if (!DeviceUtils.isWatchDevice(this)) return;
            String text = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            TermuxWearRemoteInput.injectOnWatch(text);
        }
    }

    private void postPhoneInputNotification(String sourceNodeId) {
        NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_NAME, NotificationManagerCompat.IMPORTANCE_HIGH);

        Intent replyIntent = new Intent(this, TermuxWearReplyReceiver.class)
            .setAction(TermuxWearReplyReceiver.ACTION_REPLY)
            .putExtra(TermuxWearRemoteInput.EXTRA_ORIGIN_NODE_ID, sourceNodeId);

        PendingIntent pendingReply = PendingIntent.getBroadcast(this, sourceNodeId.hashCode(), replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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

        NotificationManagerCompat.from(this).notify(REMOTE_INPUT_NOTIFICATION_ID, notification);
    }

}