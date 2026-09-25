package com.termux.app.terminal.remote;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.android.DeviceUtils;
import com.termux.shared.logger.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Shared constants and helpers for the "type on phone" relay between the watch and the paired
 * phone, both running the same Termux app. The watch asks the phone to do the typing: the phone
 * shows a notification with a reply box, the typed text travels back over the Wear link and is
 * injected into the open terminal on the watch. */
public final class TermuxWearRemoteInput {

    /** Capability advertised by the Termux install running on a phone, so the watch can find it. */
    public static final String CAPABILITY_PHONE_INPUT = "termux_wear_phone_input";

    /** Watch to phone: "show me a reply box so I can type on the phone". */
    public static final String MESSAGE_PATH_REQUEST = "/termux/remote-input-request";

    /** Phone to watch: "here is the text typed on the phone". */
    public static final String MESSAGE_PATH_REPLY = "/termux/remote-input-reply";

    /** Extra holding the node id (watch) that requested the input, kept in the phone notification. */
    public static final String EXTRA_ORIGIN_NODE_ID = "origin_node_id";

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    /** The currently running activity, if any, whose terminal receives the remote text. */
    @Nullable private static TermuxActivity sActivity;

    private TermuxWearRemoteInput() {}

    /** Bind (or unbind, with {@code null}) the running {@link TermuxActivity} so replies arriving
     * over the Wear link can be typed into its terminal. */
    public static void bindActivity(@Nullable TermuxActivity activity) {
        sActivity = activity;
    }

    /** Get the currently bound {@link TermuxActivity}, if any. */
    @Nullable
    public static TermuxActivity getCurrentActivity() {
        return sActivity;
    }

    /** Phone side: send text typed on the phone back over the Wear link to the watch that asked. */
    public static void sendReply(@NonNull final Context context, @NonNull final String originNodeId, @NonNull final String text) {
        Logger.logDebug(LOG_TAG, "Sending remote input reply \"" + text + "\" to node " + originNodeId);
        Wearable.getMessageClient(context).sendMessage(originNodeId, MESSAGE_PATH_REPLY, text.getBytes(StandardCharsets.UTF_8))
            .addOnFailureListener(e -> Logger.logError(LOG_TAG, "Reply send failed: " + e.getMessage()));
    }

    /** Phone side: show the "type for the watch" input as an in-app dialog instead of a notification
     * when Termux is already open on the phone. Runs on the main thread. */
    public static void showPhoneInputDialog(@NonNull final TermuxActivity activity, @NonNull final String originNodeId) {
        final EditText input = new EditText(activity);
        input.setSingleLine(false);
        input.setHint(R.string.remote_input_reply_label);
        new AlertDialog.Builder(activity)
            .setTitle(R.string.remote_input_notification_title)
            .setMessage(R.string.remote_input_notification_text)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String text = input.getText().toString();
                if (text.length() > 0) sendReply(activity, originNodeId, text);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Advertise the "phone input" capability so the watch can find this install. Called from the
     * activity on phones; the listener service advertises it as well but may never be bound by Play
     * services when nothing has arrived yet, so the activity call guarantees the phone is
     * discoverable after the first open. No-op on watches. */
    public static void advertisePhoneInput(@NonNull final Context context) {
        if (DeviceUtils.isWatchDevice(context)) return;
        Wearable.getCapabilityClient(context).addLocalCapability(CAPABILITY_PHONE_INPUT)
            .addOnFailureListener(e -> Logger.logError(LOG_TAG, "Could not advertise phone input capability: " + e.getMessage()));
    }

    /** Inject text received from the phone into the currently open terminal on the watch. Runs on
     * the main thread; if no activity is alive the text is silently dropped. */
    public static void injectOnWatch(@NonNull final String text) {
        HANDLER.post(() -> {
            TermuxActivity activity = sActivity;
            if (activity == null || text.length() == 0) return;
            activity.getTerminalView().injectRemoteInputText(text, true);
        });
    }

    /** Watch side: ask any connected phone running this app to type for us. Sends the request to
     * every connected node since the capability the phone advertises is only registered after Play
     * services binds its listener service (lazy), which may never happen when the phone connects
     * first. The phone's listener ignores it if the payload is not for it (watch-only hookup). */
    public static void requestPhoneInput(@NonNull final Context context) {
        Wearable.getNodeClient(context).getConnectedNodes()
            .addOnSuccessListener(nodes -> {
                if (nodes == null || nodes.isEmpty()) {
                    Toast.makeText(context, "No paired phone is connected", Toast.LENGTH_LONG).show();
                    return;
                }
                for (Node node : nodes) {
                    Logger.logDebug(LOG_TAG, "Sending remote input request to node \"" + node.getDisplayName() + "\"");
                    Wearable.getMessageClient(context).sendMessage(node.getId(), MESSAGE_PATH_REQUEST, null)
                        .addOnSuccessListener(aVoid -> Logger.logDebug(LOG_TAG, "Remote input request sent"))
                        .addOnFailureListener(e -> Logger.logError(LOG_TAG, "Remote input request send failed: " + e.getMessage()));
                }
                Toast.makeText(context, "Type the command in the phone's notification", Toast.LENGTH_LONG).show();
            })
            .addOnFailureListener(e -> {
                Logger.logError(LOG_TAG, "Could not read connected nodes: " + e.getMessage());
                Toast.makeText(context, "Could not reach the Wear data layer", Toast.LENGTH_LONG).show();
            });
    }

    /** Run the given work on the main thread from the Wear Data-Layer binder thread. View + dialog
     * creation must happen on the main thread or an un-prepared Looper freezes the app. */
    public static void postOnMainThread(@NonNull final Runnable runnable) {
        HANDLER.post(runnable);
    }

    private static final String LOG_TAG = "TermuxWearRemoteInput";

}