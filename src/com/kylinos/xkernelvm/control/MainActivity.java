/*
 * Copyright 2026 KylinSoft Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.kylinos.xkernelvm.control;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.kylinos.xkernelvm.IXKernelVmCallback;
import com.kylinos.xkernelvm.IXKernelVmControl;
import com.kylinos.xkernelvm.VmState;
import com.kylinos.xkernelvm.VmStatus;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts and stops the x-kernel VM through the control service and shows its real state.
 *
 * <p>The app never holds the VM: the control service owns it, so leaving, swiping away or
 * killing the app leaves the VM as it is, and opening the app again shows the same instance.
 * Opening the app or reading the state never starts anything.
 *
 * <p>While the screen is visible the app subscribes to the service's status callbacks and
 * unsubscribes when it is not; it never polls. The only timer is the local uptime display.
 * Snapshots carry the service session and a sequence number, so a late or duplicate callback
 * never overwrites a newer state.
 */
public class MainActivity extends Activity {
    private static final String TAG = "XKernelVmControl";
    private static final String SERVICE_NAME = "com.kylinos.xkernelvm.IXKernelVmControl/default";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** Binder calls run here, in order, off the main thread. */
    private final ExecutorService mCalls = Executors.newSingleThreadExecutor();

    private TextView mState;
    private TextView mMessage;
    private TextView mDetails;
    private Button mStart;
    private Button mStop;
    private Button mReconnect;

    private IXKernelVmControl mControl;
    private IBinder mBinder;
    private VmStatus mStatus;
    /** A request is in flight; buttons stay disabled until its answer or the next status. */
    private boolean mRequestPending;
    private boolean mResumed;

    private final IBinder.DeathRecipient mServiceDeath = () -> mMain.post(this::onServiceDied);

    private final IXKernelVmCallback mCallback = new IXKernelVmCallback.Stub() {
        @Override
        public void onStatusChanged(VmStatus status) {
            mMain.post(() -> apply(status));
        }
    };

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            render();
            if (mResumed && isRunning(mStatus)) {
                mMain.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mState = findViewById(R.id.state);
        mMessage = findViewById(R.id.message);
        mDetails = findViewById(R.id.details);
        mStart = findViewById(R.id.start);
        mStop = findViewById(R.id.stop);
        mReconnect = findViewById(R.id.reconnect);
        mStart.setOnClickListener(v -> request(true));
        mStop.setOnClickListener(v -> request(false));
        mReconnect.setOnClickListener(v -> connect());
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        connect();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mResumed = false;
        mMain.removeCallbacks(mTick);
        disconnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCalls.shutdown();
    }

    private void connect() {
        disconnect();
        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        if (binder == null) {
            showDisconnected(getString(R.string.service_missing));
            return;
        }
        try {
            binder.linkToDeath(mServiceDeath, 0);
        } catch (RemoteException e) {
            showDisconnected(getString(R.string.service_died));
            return;
        }
        mBinder = binder;
        mControl = IXKernelVmControl.Stub.asInterface(binder);
        IXKernelVmControl control = mControl;
        mCalls.execute(() -> {
            try {
                VmStatus initial = control.registerCallback(mCallback);
                mMain.post(() -> apply(initial));
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "registerCallback failed", e);
                mMain.post(() -> showError(e));
            }
        });
    }

    private void disconnect() {
        IXKernelVmControl control = mControl;
        if (control != null) {
            mCalls.execute(() -> {
                try {
                    control.unregisterCallback(mCallback);
                } catch (RemoteException | RuntimeException e) {
                    Log.i(TAG, "unregisterCallback failed", e);
                }
            });
        }
        if (mBinder != null) {
            mBinder.unlinkToDeath(mServiceDeath, 0);
        }
        mBinder = null;
        mControl = null;
    }

    private void onServiceDied() {
        mBinder = null;
        mControl = null;
        mStatus = null;
        showDisconnected(getString(R.string.service_died));
    }

    private void showDisconnected(String message) {
        mStatus = null;
        mRequestPending = false;
        mState.setText(message);
        mMessage.setVisibility(View.GONE);
        mDetails.setText("");
        mStart.setEnabled(false);
        mStop.setEnabled(false);
        mReconnect.setVisibility(View.VISIBLE);
    }

    /** Accepts a snapshot unless it is older than the one shown. */
    private void apply(VmStatus status) {
        if (mControl == null) {
            return;
        }
        boolean sameSession = mStatus != null && status.sessionId.equals(mStatus.sessionId);
        if (sameSession && status.seq <= mStatus.seq) {
            return;
        }
        mStatus = status;
        mRequestPending = false;
        mReconnect.setVisibility(View.GONE);
        render();
        mMain.removeCallbacks(mTick);
        if (mResumed && isRunning(status)) {
            mMain.postDelayed(mTick, 1000);
        }
    }

    private void request(boolean start) {
        IXKernelVmControl control = mControl;
        if (control == null || mRequestPending) {
            return;
        }
        mRequestPending = true;
        mMessage.setVisibility(View.GONE);
        render();
        mCalls.execute(() -> {
            try {
                long operation = start ? control.requestStart() : control.requestStop();
                Log.i(TAG, (start ? "start" : "stop") + " accepted as operation " + operation);
                if (operation == 0) {
                    // Nothing to do; no status change will follow.
                    mMain.post(() -> {
                        mRequestPending = false;
                        render();
                    });
                }
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, (start ? "start" : "stop") + " refused", e);
                mMain.post(() -> {
                    mRequestPending = false;
                    showError(e);
                    render();
                });
            }
        });
    }

    private void showError(Exception e) {
        String detail = e.getMessage() == null ? e.toString() : e.getMessage();
        String text;
        if (e instanceof ServiceSpecificException) {
            int code = ((ServiceSpecificException) e).errorCode;
            if (code == IXKernelVmControl.ERROR_UNAVAILABLE) {
                text = getString(R.string.error_unavailable, detail);
            } else if (code == IXKernelVmControl.ERROR_BUSY) {
                text = getString(R.string.error_busy, detail);
            } else if (code == IXKernelVmControl.ERROR_RECLAIM_PENDING) {
                text = getString(R.string.error_reclaim_pending, detail);
            } else {
                text = getString(R.string.error_other, detail);
            }
        } else if (e instanceof SecurityException) {
            text = getString(R.string.error_security, detail);
        } else {
            text = getString(R.string.error_other, detail);
        }
        mMessage.setText(text);
        mMessage.setVisibility(View.VISIBLE);
    }

    private static boolean isRunning(VmStatus s) {
        return s != null
                && (s.state == VmState.RUNNING_NOT_READY || s.state == VmState.READY);
    }

    private String stateLabel(int state) {
        switch (state) {
            case VmState.UNAVAILABLE: return getString(R.string.state_unavailable);
            case VmState.STOPPED: return getString(R.string.state_stopped);
            case VmState.STARTING: return getString(R.string.state_starting);
            case VmState.RUNNING_NOT_READY: return getString(R.string.state_running_not_ready);
            case VmState.READY: return getString(R.string.state_ready);
            case VmState.STOPPING: return getString(R.string.state_stopping);
            case VmState.RECLAIM_UNKNOWN: return getString(R.string.state_reclaim_unknown);
            case VmState.FAILED: return getString(R.string.state_failed);
            default: return getString(R.string.state_unknown, state);
        }
    }

    private void render() {
        VmStatus s = mStatus;
        if (s == null) {
            return;
        }
        mState.setText(getString(R.string.label_state) + "：" + stateLabel(s.state));

        StringBuilder d = new StringBuilder();
        String reason = s.reason;
        if ((reason == null || reason.isEmpty()) && s.state == VmState.RUNNING_NOT_READY
                && !s.readinessConfigured) {
            reason = getString(R.string.not_ready_no_probe);
        }
        if (reason != null && !reason.isEmpty()) {
            line(d, R.string.label_reason, reason);
        }
        line(d, R.string.label_protection, getString(!s.protectedVm
                ? R.string.protection_none
                : s.withoutFirmware ? R.string.protection_without_firmware
                        : R.string.protection_with_firmware));
        line(d, R.string.label_dice,
                getString(s.diceAvailable ? R.string.dice_enabled : R.string.dice_disabled));
        line(d, R.string.label_memory, getString(R.string.memory_value, s.memoryMib));
        line(d, R.string.label_cpus, getString(R.string.cpus_value, s.cpuCount));
        if (isRunning(s) && s.startedAtElapsedMs > 0) {
            long seconds = Math.max(0, SystemClock.elapsedRealtime() - s.startedAtElapsedMs) / 1000;
            line(d, R.string.label_uptime,
                    String.format("%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60));
        }
        // While an instance starts or runs, "reclaim" would describe the current one, which is
        // not reclaimed by definition; the line is about the previous instance.
        if (s.state != VmState.STARTING && !isRunning(s)) {
            line(d, R.string.label_reclaim, getString(
                    s.reclaimConfirmed ? R.string.reclaim_confirmed : R.string.reclaim_unconfirmed));
        }
        line(d, R.string.label_last_exit, exitLabel(s.lastExit));
        mDetails.setText(d.toString());

        boolean idle = !mRequestPending && mControl != null;
        mStart.setEnabled(idle && (s.state == VmState.STOPPED || s.state == VmState.FAILED));
        boolean stoppable = s.state == VmState.STARTING || isRunning(s)
                || s.state == VmState.RECLAIM_UNKNOWN;
        mStop.setEnabled(idle && stoppable);
        mStop.setText(s.state == VmState.RECLAIM_UNKNOWN ? R.string.recheck : R.string.stop);
    }

    /** The service's words for how an instance ended; unknown ones are shown as they are. */
    private String exitLabel(String exit) {
        if (exit == null || exit.isEmpty()) {
            return getString(R.string.none);
        }
        switch (exit) {
            case "stopped on request": return getString(R.string.exit_stopped);
            case "guest shut down": return getString(R.string.exit_guest_shutdown);
            case "killed": return getString(R.string.exit_killed);
            default: return exit;
        }
    }

    private void line(StringBuilder b, int label, String value) {
        b.append(getString(label)).append("：").append(value).append('\n');
    }
}
