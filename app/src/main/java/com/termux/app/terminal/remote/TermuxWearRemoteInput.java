package com.termux.app.terminal.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.tasks.Task;

import com.termux.app.TermuxActivity;

import java.util.Set;

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

    /** Inject text received from the phone into the currently open terminal on the watch. Runs on
     * the main thread; if no activity is alive the text is silently dropped. */
    public static void injectOnWatch(@NonNull final String text) {
        HANDLER.post(() -> {
            TermuxActivity activity = sActivity;
            if (activity == null || text.length() == 0) return;
            activity.getTerminalView().injectRemoteInputText(text, true);
        });
    }

    /** Watch side: find the connected phone running this app and ask it to type for us. Shows a
     * toast if no reachable phone was found. */
    public static void requestPhoneInput(@NonNull final Context context) {
        CapabilityClient capabilityClient = Wearable.getCapabilityClient(context);
        Task<CapabilityInfo> capabilityTask =
            capabilityClient.getCapability(CAPABILITY_PHONE_INPUT, CapabilityClient.FILTER_REACHABLE);
        capabilityTask.addOnCompleteListener(task -> {
            final String phoneNodeId = getFirstNodeId(task);
            if (phoneNodeId == null) {
                Toast.makeText(context, "No paired phone with Termux found", Toast.LENGTH_SHORT).show();
                return;
            }
            Wearable.getMessageClient(context).sendMessage(phoneNodeId, MESSAGE_PATH_REQUEST, null);
            Toast.makeText(context, "Type the command in the phone's notification", Toast.LENGTH_SHORT).show();
        });
    }

    @Nullable
    private static String getFirstNodeId(@Nullable Task<CapabilityInfo> task) {
        if (task == null || !task.isSuccessful() || task.getResult() == null) return null;
        Set<Node> nodes = task.getResult().getNodes();
        for (Node node : nodes) {
            if (node.isNearby()) return node.getId();
        }
        for (Node node : nodes) {
            return node.getId();
        }
        return null;
    }

}