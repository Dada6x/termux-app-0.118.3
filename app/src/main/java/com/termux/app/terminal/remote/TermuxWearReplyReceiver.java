package com.termux.app.terminal.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.app.RemoteInput;

import com.google.android.gms.wearable.Wearable;

import com.termux.shared.android.DeviceUtils;

import java.nio.charset.StandardCharsets;

/** Phone side: receives the reply action of the "type on phone" notification, reads the text typed
 * by the user and sends it back over the Wear link to the watch that requested it. */
public class TermuxWearReplyReceiver extends BroadcastReceiver {

    public static final String KEY_REPLY_TEXT = "key_reply_text";
    public static final String ACTION_REPLY = "com.termux.remote_input.REPLY";

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        if (DeviceUtils.isWatchDevice(context)) return;

        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results == null) return;

        CharSequence text = results.getCharSequence(KEY_REPLY_TEXT);
        if (text == null || text.length() == 0) return;

        String originNodeId = intent.getStringExtra(TermuxWearRemoteInput.EXTRA_ORIGIN_NODE_ID);
        if (originNodeId == null) return;

        Wearable.getMessageClient(context).sendMessage(originNodeId, TermuxWearRemoteInput.MESSAGE_PATH_REPLY,
            text.toString().getBytes(StandardCharsets.UTF_8));
    }

}