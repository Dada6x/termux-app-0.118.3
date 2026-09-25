package com.termux.app.terminal.io;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.shared.terminal.io.extrakeys.ExtraKeyButton;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysConstants;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysInfo;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView;
import com.termux.shared.terminal.io.extrakeys.IExtraKeysViewState;
import com.termux.shared.terminal.io.extrakeys.SpecialButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A looping carousel {@link RecyclerView} showing the extra keys of the first row of an
 * {@link ExtraKeysInfo} as large buttons on Wear OS (watch) devices. Swiping left or right slides
 * the keys in an infinite loop (no start/end edge) as the data set is repeated and the scroll
 * position is recentred when the user nears a boundary.
 *
 * It implements the same {@link IExtraKeysViewState} and {@link ExtraKeysView.IExtraKeysView} client
 * contracts as the grid based {@link ExtraKeysView} so that phones, tablets and other devices can
 * keep using the {@link ExtraKeysView} grid unchanged.
 *
 * Not supported on the carousel (conflicts with the horizontal swipe gesture):
 * the swipe up alternate key popups of {@link ExtraKeysView}. Alternate keys can instead be
 * triggered by defining a long press if extended in future.
 */
public class WearLoopingExtraKeysView extends RecyclerView implements IExtraKeysViewState {

    /** The {@link ExtraKeysView.IExtraKeysView} client that handles the extra key button clicks. */
    private ExtraKeysView.IExtraKeysView mExtraKeysViewClient;

    /** The looping data set: the first row of keys repeated {@link #LOOP_COPY_COUNT} times. */
    private final List<ExtraKeyButton> mButtons = new ArrayList<>();

    /** The key count of a single copy of the first row of keys, used to compute the boundary positions. */
    private int mSingleRowCount;

    /** The states of the {@link SpecialButton} keys. */
    private final Map<String, CarouselButtonState> mSpecialButtonStates = new HashMap<>();

    /** The keys that auto repeat if long pressed, as defined by {@link ExtraKeysConstants#PRIMARY_REPETITIVE_KEYS}. */
    private final List<String> mRepetitiveKeys;

    /** The duration in milliseconds before a press turns into a long press. */
    private final int mLongPressTimeout;

    private boolean mButtonTextAllCaps = true;
    private boolean mLongPressTriggered;
    private float mDownRawX;
    private float mDownRawY;
    private final int mTouchSlop;
    private int mButtonTextColor = 0xFFFFFFFF;
    private int mButtonActiveTextColor = 0xFF80DEEA;
    private int mButtonBackgroundColor = 0x00000000;
    private int mButtonActiveBackgroundColor = 0xFF7F7F7F;

    private ScheduledExecutorService mScheduledExecutor;
    private Handler mHandler;
    private Runnable mSpecialButtonLongHoldRunnable;

    /** Called on every touch the carousel receives (down and move), before the event is dispatched
     * to the keys. Used to keep the terminal toolbar row alive while the user swipes the keys. */
    private Runnable mOnUserInteractionRunnable;

    /** The number of times the first row of keys is repeated to fake an infinite loop. */
    private static final int LOOP_COPY_COUNT = 3;
    /** The default long press repeat delay in milliseconds for the repetitive keys. */
    private static final long LONG_PRESS_REPEAT_DELAY_MS = 80;
    /** The width divisor for each key: the keys are {@code 1 / KEY_WIDTH_DIVISOR} of the screen width wide. */
    private static final float KEY_WIDTH_DIVISOR = 3f;

    /**
     * The {@link Button} and state related to a {@link SpecialButton}.
     */
    private static class CarouselButtonState {
        boolean isCreated = false;
        boolean isActive = false;
        boolean isLocked = false;
        final List<Button> buttons = new ArrayList<>();
    }

    public WearLoopingExtraKeysView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mRepetitiveKeys = ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS;
        mLongPressTimeout = ViewConfiguration.getLongPressTimeout();
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        for (SpecialButton specialButton : getDefaultSpecialButtons())
            mSpecialButtonStates.put(specialButton.getKey(), new CarouselButtonState());

        setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));

        setAdapter(new ExtraKeysAdapter());

        // Center snap each key instead of the default LinearSnapHelper start-edge snap, so the
        // snapped key is always aligned to the middle of the watch screen.
        CenterSnapHelper snapHelper = new CenterSnapHelper();
        snapHelper.attachToRecyclerView(this);

        addOnScrollListener(new OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE)
                    recentre();
            }
        });
    }

    /** Get the {@link ExtraKeysView.IExtraKeysView} client. */
    public ExtraKeysView.IExtraKeysView getExtraKeysViewClient() {
        return mExtraKeysViewClient;
    }

    /** Called on every touch this view receives (down and move), before dispatch to the key buttons,
     * so e.g. the toolbar auto-hide timer can be reset even when a key button consumes the down event. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mOnUserInteractionRunnable != null) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
                mOnUserInteractionRunnable.run();
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Set a callback invoked when the user touches the carousel. */
    public void setOnUserInteractionRunnable(Runnable runnable) {
        mOnUserInteractionRunnable = runnable;
    }

    /** Set the {@link ExtraKeysView.IExtraKeysView} client. */
    public void setExtraKeysViewClient(ExtraKeysView.IExtraKeysView extraKeysViewClient) {
        mExtraKeysViewClient = extraKeysViewClient;
    }

    /** Get the default {@link SpecialButton}s that can have an active/locked state. */
    private static List<SpecialButton> getDefaultSpecialButtons() {
        List<SpecialButton> specialButtons = new ArrayList<>();
        specialButtons.add(SpecialButton.CTRL);
        specialButtons.add(SpecialButton.ALT);
        specialButtons.add(SpecialButton.SHIFT);
        specialButtons.add(SpecialButton.FN);
        return specialButtons;
    }

    /** Check whether a {@link ExtraKeyButton} is a {@link SpecialButton}. */
    private boolean isSpecialButton(ExtraKeyButton button) {
        return mSpecialButtonStates.containsKey(button.getKey());
    }

    @Override
    public void setButtonTextAllCaps(boolean buttonTextAllCaps) {
        if (mButtonTextAllCaps != buttonTextAllCaps) {
            mButtonTextAllCaps = buttonTextAllCaps;
            getAdapter().notifyDataSetChanged();
        }
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public void reload(ExtraKeysInfo extraKeysInfo) {
        if (extraKeysInfo == null)
            return;

        mButtons.clear();

        ExtraKeyButton[][] matrix = extraKeysInfo.getMatrix();
        if (matrix != null && matrix.length > 0 && matrix[0].length > 0) {
            mSingleRowCount = matrix[0].length;
            for (int i = 0; i < LOOP_COPY_COUNT; i++) {
                for (ExtraKeyButton key : matrix[0])
                    mButtons.add(key);
            }
        } else {
            // Empty extra keys
            mSingleRowCount = 0;
        }

        getAdapter().notifyDataSetChanged();

        // Start in the middle copy of the replicated data set so that both directions are boundless.
        if (mSingleRowCount > 0)
            getLayoutManager().scrollToPosition(mSingleRowCount);
    }

    /**
     * Recentre the scroll position back into the middle copy of the replicated data set when the
     * user nears a boundary. This fakes an infinite loop with no start/end edge.
     */
    private void recentre() {
        if (mSingleRowCount == 0) return;
        LinearLayoutManager layoutManager = (LinearLayoutManager) getLayoutManager();
        if (layoutManager == null) return;

        int firstVisiblePosition = layoutManager.findFirstVisibleItemPosition();
        int shift;
        if (firstVisiblePosition < mSingleRowCount) {
            shift = mSingleRowCount;
        } else if (firstVisiblePosition >= 2 * mSingleRowCount) {
            shift = -mSingleRowCount;
        } else {
            return;
        }

        // Preserve the exact visible viewport when shifting to the neighbouring copy of the data
        // set, otherwise the centered key would jump to a different position on screen. Without
        // the offset, scrollToPosition() anchors the shifted item at the left edge of the carousel
        // which makes the user land somewhere they did not intend.
        int offset = 0;
        View firstVisibleView = layoutManager.findViewByPosition(firstVisiblePosition);
        if (firstVisibleView != null)
            offset = firstVisibleView.getLeft();

        layoutManager.scrollToPositionWithOffset(firstVisiblePosition + shift, offset);
    }

    @Override
    @Nullable
    public Boolean readSpecialButton(SpecialButton specialButton, boolean autoSetInActive) {
        CarouselButtonState state = mSpecialButtonStates.get(specialButton.getKey());
        if (state == null) return null;

        if (!state.isCreated || !state.isActive)
            return false;

        // Disable active state only if not locked
        if (autoSetInActive && !state.isLocked) {
            state.isActive = false;
            updateSpecialButtonColors(state);
        }

        return true;
    }

    private void updateSpecialButtonColors(CarouselButtonState state) {
        for (Button button : state.buttons)
            button.setTextColor(state.isActive ? mButtonActiveTextColor : mButtonTextColor);
    }

    private void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, Button button) {
        if (mExtraKeysViewClient != null)
            mExtraKeysViewClient.onExtraKeyButtonClick(view, buttonInfo, button);
    }

    private void performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, Button button) {
        if (mExtraKeysViewClient != null) {
            // If client handled the feedback, then just return
            if (mExtraKeysViewClient.performExtraKeyButtonHapticFeedback(view, buttonInfo, button))
                return;
        }

        if (Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0) {
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void onAnyExtraKeyButtonClick(View view, @NonNull ExtraKeyButton buttonInfo, Button button) {
        if (isSpecialButton(buttonInfo)) {
            if (mLongPressTriggered) return;
            CarouselButtonState state = mSpecialButtonStates.get(buttonInfo.getKey());
            if (state == null) return;

            // Toggle active state and disable lock state if new state is not active
            state.isActive = !state.isActive;
            if (!state.isActive)
                state.isLocked = false;
            updateSpecialButtonColors(state);
        } else {
            onExtraKeyButtonClick(view, buttonInfo, button);
        }
    }

    private void startScheduledExecutors(View view, ExtraKeyButton buttonInfo, Button button) {
        stopScheduledExecutors();
        mLongPressTriggered = false;

        if (mRepetitiveKeys.contains(buttonInfo.getKey())) {
            // Auto repeat key if long pressed until ACTION_UP stops it by calling stopScheduledExecutors
            mScheduledExecutor = Executors.newSingleThreadScheduledExecutor();
            mScheduledExecutor.scheduleWithFixedDelay(() -> {
                mLongPressTriggered = true;
                onExtraKeyButtonClick(view, buttonInfo, button);
            }, mLongPressTimeout, LONG_PRESS_REPEAT_DELAY_MS, TimeUnit.MILLISECONDS);
        } else if (isSpecialButton(buttonInfo)) {
            // Lock the key if long pressed by running mSpecialButtonLongHoldRunnable after
            // waiting for mLongPressTimeout milliseconds. If user does not long press, then the
            // ACTION_UP triggered will cancel the runnable by calling stopScheduledExecutors before
            // it has a chance to run.
            CarouselButtonState state = mSpecialButtonStates.get(buttonInfo.getKey());
            if (state == null) return;
            if (mHandler == null)
                mHandler = new Handler(Looper.getMainLooper());
            mSpecialButtonLongHoldRunnable = () -> {
                // Toggle active and lock state
                mLongPressTriggered = true;
                state.isLocked = !state.isActive;
                state.isActive = !state.isActive;
                updateSpecialButtonColors(state);
            };
            mHandler.postDelayed(mSpecialButtonLongHoldRunnable, mLongPressTimeout);
        }
    }

    private void stopScheduledExecutors() {
        if (mScheduledExecutor != null) {
            mScheduledExecutor.shutdownNow();
            mScheduledExecutor = null;
        }

        if (mSpecialButtonLongHoldRunnable != null && mHandler != null) {
            mHandler.removeCallbacks(mSpecialButtonLongHoldRunnable);
            mSpecialButtonLongHoldRunnable = null;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindButton(Button button, ExtraKeyButton buttonInfo) {
        // If the button was previously bound to a different special button, remove its stale
        // reference from that state's button list so color updates do not hit recycled buttons.
        Object tag = button.getTag();
        if (tag instanceof String) {
            CarouselButtonState oldState = mSpecialButtonStates.get(tag);
            if (oldState != null)
                oldState.buttons.remove(button);
        }
        button.setTag(buttonInfo.getKey());

        button.setText(buttonInfo.getDisplay());
        button.setTextColor(mButtonTextColor);
        button.setAllCaps(mButtonTextAllCaps);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setOnClickListener(v -> {
            performExtraKeyButtonHapticFeedback(v, buttonInfo, button);
            onAnyExtraKeyButtonClick(v, buttonInfo, button);
        });
        button.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mDownRawX = event.getRawX();
                    mDownRawY = event.getRawY();
                    mLongPressTriggered = false;
                    // Do not set the active background here, otherwise every swipe press would flash
                    // the button square before the swipe is recognised. The square is only flashed
                    // on a genuine tap that executes the button.
                    // Start long press scheduled executors which will be stopped in next MotionEvent
                    startScheduledExecutors(view, buttonInfo, button);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    stopScheduledExecutors();
                    view.setBackgroundColor(mButtonBackgroundColor);
                    return true;

                case MotionEvent.ACTION_UP:
                    stopScheduledExecutors();
                    boolean isGenuineTap = !mLongPressTriggered && !isMovementBeyondTouchSlop(event);
                    if (isGenuineTap) {
                        // Flash the button square only for a genuine tap that executes the button.
                        view.setBackgroundColor(mButtonActiveBackgroundColor);
                        view.performClick();
                        view.postDelayed(() -> view.setBackgroundColor(mButtonBackgroundColor), 60);
                    } else {
                        view.setBackgroundColor(mButtonBackgroundColor);
                    }
                    return true;

                default:
                    return true;
            }
        });
    }

    /**
     * Check whether the {@link MotionEvent} moved beyond the touch slop compared to where the touch
     * started, i.e. the user dragged/swiped instead of tapping.
     */
    private boolean isMovementBeyondTouchSlop(MotionEvent event) {
        float dx = event.getRawX() - mDownRawX;
        float dy = event.getRawY() - mDownRawY;
        return (dx * dx + dy * dy) > (mTouchSlop * mTouchSlop);
    }

    private class ExtraKeysAdapter extends Adapter<ExtraKeysViewHolder> {

        @NonNull
        @Override
        public ExtraKeysViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Button button = new Button(parent.getContext(), null, android.R.attr.buttonBarButtonStyle);
            int itemWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels / (int) (KEY_WIDTH_DIVISOR + 0.5f));
            button.setLayoutParams(new RecyclerView.LayoutParams(itemWidth, ViewGroup.LayoutParams.MATCH_PARENT));
            return new ExtraKeysViewHolder(button);
        }

        @Override
        public void onBindViewHolder(@NonNull ExtraKeysViewHolder holder, int position) {
            final ExtraKeyButton buttonInfo = mButtons.get(position);
            Button button = holder.button;
            bindButton(button, buttonInfo);
            if (isSpecialButton(buttonInfo)) {
                CarouselButtonState state = mSpecialButtonStates.get(buttonInfo.getKey());
                if (state != null) {
                    state.isCreated = true;
                    if (!state.buttons.contains(button))
                        state.buttons.add(button);
                    button.setTextColor(state.isActive ? mButtonActiveTextColor : mButtonTextColor);
                }
            }
        }

        @Override
        public int getItemCount() {
            return mButtons.size();
        }
    }

    private static class ExtraKeysViewHolder extends ViewHolder {
        final Button button;

        ExtraKeysViewHolder(@NonNull View itemView) {
            super(itemView);
            this.button = (Button) itemView;
        }
    }

    /**
     * A {@link SnapHelper} that snaps the key to the center of the carousel instead of the default
     * {@link LinearSnapHelper} behaviour which snaps a child to the start edge of the carousel.
     */
    private static class CenterSnapHelper extends LinearSnapHelper {

        @Nullable
        @Override
        public int[] calculateDistanceToFinalSnap(@NonNull LayoutManager layoutManager, @NonNull View targetView) {
            int[] out = new int[2];
            if (layoutManager.canScrollHorizontally()) {
                out[0] = getDistanceToCenter(layoutManager, targetView);
            } else {
                out[0] = 0;
            }
            out[1] = 0;
            return out;
        }

        @Nullable
        @Override
        public View findSnapView(LayoutManager layoutManager) {
            if (layoutManager.canScrollHorizontally())
                return findCenterSnappedView(layoutManager);
            return null;
        }

        private int getDistanceToCenter(@NonNull LayoutManager layoutManager, @NonNull View targetView) {
            int parentCenter = getSnapCenter(layoutManager);
            int childCenter = layoutManager.getDecoratedLeft(targetView) + (layoutManager.getDecoratedMeasuredWidth(targetView) / 2);
            return childCenter - parentCenter;
        }

        private int getSnapCenter(LayoutManager layoutManager) {
            return layoutManager.getWidth() / 2;
        }

        @Nullable
        private View findCenterSnappedView(@NonNull LayoutManager layoutManager) {
            int childCount = layoutManager.getChildCount();
            if (childCount == 0)
                return null;

            View closestChild = null;
            int parentCenter = getSnapCenter(layoutManager);
            int absClosest = Integer.MAX_VALUE;

            for (int i = 0; i < childCount; i++) {
                View child = layoutManager.getChildAt(i);
                int childCenter = layoutManager.getDecoratedLeft(child) + (layoutManager.getDecoratedMeasuredWidth(child) / 2);
                int absDistance = Math.abs(childCenter - parentCenter);
                if (absDistance < absClosest) {
                    absClosest = absDistance;
                    closestChild = child;
                }
            }

            return closestChild;
        }
    }

}