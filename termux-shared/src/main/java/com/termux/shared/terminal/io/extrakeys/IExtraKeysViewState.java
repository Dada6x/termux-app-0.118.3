package com.termux.shared.terminal.io.extrakeys;

import androidx.annotation.Nullable;

/**
 * An interface for a {@link android.view.View} that displays extra keys and maintains the state of
 * the {@link SpecialButton}s (CTRL, ALT, SHIFT, FN). It is implemented by both the grid based
 * {@link ExtraKeysView} used on phones, tablets and other devices and the looping carousel based
 * {@link com.termux.app.terminal.io.WearLoopingExtraKeysView} used on Wear OS (watch) devices.
 */
public interface IExtraKeysViewState {

    /**
     * Reload this instance with the info passed in {@code extraKeysInfo}.
     *
     * @param extraKeysInfo The {@link ExtraKeysInfo} that defines the necessary info for the extra keys.
     */
    void reload(ExtraKeysInfo extraKeysInfo);

    /**
     * Set whether the text for the extra keys buttons should be all capitalized automatically.
     *
     * @param buttonTextAllCaps Whether the text should be all caps.
     */
    void setButtonTextAllCaps(boolean buttonTextAllCaps);

    /**
     * Read whether {@link SpecialButton} registered is active or not.
     *
     * @param specialButton The {@link SpecialButton} to read.
     * @param autoSetInActive Set to {@code true} if the active state should be set {@code false} if
     *                        button is not locked.
     * @return Returns {@code null} if button does not exist. If button exists, then returns
     *         {@code true} if the button is created and is active, otherwise {@code false}.
     */
    @Nullable
    Boolean readSpecialButton(SpecialButton specialButton, boolean autoSetInActive);

}