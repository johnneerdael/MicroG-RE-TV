package org.microg.gms.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.gms.BuildConfig;
import com.google.android.gms.R;
import com.google.android.material.appbar.CollapsingToolbarLayout;

import org.microg.gms.common.Constants;
import org.microg.gms.ui.settings.SettingsProvider;
import org.microg.gms.ui.updater.AppUpdater;
import org.microg.gms.ui.updater.UpdateCheckScheduler;

import static org.microg.gms.ui.UtilsKt.buildAlertDialog;
import static org.microg.gms.ui.settings.SettingsProviderKt.getAllSettingsProviders;

public class MainSettingsActivity extends AppCompatActivity {
    private AppBarConfiguration appBarConfiguration;

    private static final String FIRST_RUN_MASTER = "org.microg.gms_firstRun";
    private static final String FIRST_RUN_PREF = "as_run";

    private NavController getNavController() {
        return ((NavHostFragment)getSupportFragmentManager().findFragmentById(R.id.navhost)).getNavController();
    }

    private long lastKeyEventTime = 0;

    /**
     * With a D-pad (Android TV) the system would otherwise put initial focus on the toolbar's
     * back or menu button, where an OK press leaves the screen. Start on the screen content.
     */
    private void focusContentOnNavigation() {
        getSupportFragmentManager().findFragmentById(R.id.navhost).getChildFragmentManager()
                .registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
                        View content = f.getView();
                        if (content == null || content.isInTouchMode()) return;
                        focusContent(content, SystemClock.uptimeMillis(), 10);
                    }
                }, false);
    }

    // Some screens fill or rebuild their list asynchronously, which can drop focus back onto the
    // toolbar, so keep checking for a moment until the user starts navigating.
    private void focusContent(View content, long since, int checks) {
        content.postDelayed(() -> {
            if (!content.isAttachedToWindow() || lastKeyEventTime > since) return;
            View current = getCurrentFocus();
            if (current == null || !isDescendant(content, current)) content.requestFocus(View.FOCUS_DOWN);
            if (checks > 1) focusContent(content, since, checks - 1);
        }, 100);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        lastKeyEventTime = event.getEventTime();
        return super.dispatchKeyEvent(event);
    }

    private static boolean isDescendant(View parent, View view) {
        while (view != null) {
            if (view == parent) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    private void showDialogIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(FIRST_RUN_MASTER, MODE_PRIVATE);
        if (BuildConfig.APPLICATION_ID == Constants.USER_MICROG_PACKAGE_NAME &&
                prefs.getBoolean(FIRST_RUN_PREF, true)) {
            buildAlertDialog(this)
                    .setMessage(R.string.limited_services_dialog_information)
                    .setTitle(R.string.limited_services_app_name)
                    .setPositiveButton(R.string.limited_services_dialog_information_ack, (dialog, id) -> {
                        prefs.edit().putBoolean(FIRST_RUN_PREF, false).apply();
                    })
                    .create()
                    .show();
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        for (SettingsProvider settingsProvider : getAllSettingsProviders(this)) {
            settingsProvider.preProcessSettingsIntent(intent);
        }

        setContentView(R.layout.settings_root_activity);

        Toolbar toolbar = findViewById(R.id.toolbar);
        CollapsingToolbarLayout toolbarLayout = findViewById(R.id.collapsing_toolbar);
        setSupportActionBar(toolbar);

        for (SettingsProvider settingsProvider : getAllSettingsProviders(this)) {
            settingsProvider.extendNavigation(getNavController());
        }

        appBarConfiguration = new AppBarConfiguration.Builder(getNavController().getGraph()).build();
        NavigationUI.setupWithNavController(toolbarLayout, toolbar, getNavController(), appBarConfiguration);

        // Fix navigating into a submenu does not show the menu from the top.
        getNavController().addOnDestinationChangedListener((controller, destination, arguments) -> {
            NestedScrollView nestedScrollView = findViewById(R.id.nested_scroll_view);
            if (nestedScrollView != null) {
                nestedScrollView.scrollTo(0, 0);
            }
        });

        focusContentOnNavigation();

        showDialogIfNeeded();

        UpdateCheckScheduler.schedule(this);
        AppUpdater.checkOnLaunch(this);
        // The daily update notification needs POST_NOTIFICATIONS on Android 13+; a
        // background receiver can never prompt, so ask while the settings screen is open.
        AppUpdater.ensureUpdateNotificationPermission(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Tapping the update notification can deliver the check extra to an already-running activity.
        setIntent(intent);
        AppUpdater.checkOnLaunch(this);
    }

    @Override
    public boolean onSupportNavigateUp() {
        return NavigationUI.navigateUp(getNavController(), appBarConfiguration) || super.onSupportNavigateUp();
    }
}
