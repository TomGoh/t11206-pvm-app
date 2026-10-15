/*
 * Copyright 2026 KylinSoft Co., Ltd.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.kylinos.xkernelvm.control;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kylinos.xkernelvm.ConsoleOutput;
import com.kylinos.xkernelvm.IXKernelVmCallback;
import com.kylinos.xkernelvm.IXKernelVmConsoleCallback;
import com.kylinos.xkernelvm.IXKernelVmControl;
import com.kylinos.xkernelvm.VmState;
import com.kylinos.xkernelvm.VmStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <p>The bottom half is the guest's serial console, which the service keeps: attaching returns
 * the output kept so far, and later output arrives addressed by instance and offset, so a
 * duplicate is dropped and a gap makes the app attach again. Input goes to the guest as typed
 * (a line is sent with a newline); the guest echoes it. Input is only enabled while the VM runs;
 * the output of an instance that ended stays visible until the next start.
 */
public class MainActivity extends Activity {
    private static final String TAG = "XKernelVmControl";
    private static final String SERVICE_NAME = "com.kylinos.xkernelvm.IXKernelVmControl/default";
    /** The service takes at most this much input per write. */
    private static final int MAX_INPUT = 4096;
    private static final long RENDER_DELAY_MS = 60;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** Binder calls run here, in order, off the main thread. */
    private final ExecutorService mCalls = Executors.newSingleThreadExecutor();

    private TextView mState;
    private TextView mMessage;
    private TextView mDetails;
    private Button mStart;
    private Button mStop;
    private Button mReconnect;

    private View mConsolePane;
    private TextView mConsoleTitle;
    private ScrollView mTerminalScroll;
    private TextView mTerminalView;
    private TextView mConsoleNote;
    private EditText mInput;
    private Button mSend;
    private ViewGroupKeys mKeys;
    private TerminalBuffer mTerminal;
    /** The history of the current attachment has been applied. */
    private boolean mConsoleSynced;
    /** Pieces that arrived before the history they follow. */
    private final ArrayList<ConsoleOutput> mConsolePending = new ArrayList<>();
    private long mConsoleInstance = -1;
    /** Offset just past the output shown. */
    private long mConsoleEnd;
    private boolean mRenderScheduled;
    /** Keep the view at the newest output (the user has not scrolled back). */
    private boolean mStickToEnd = true;

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

    private final IXKernelVmConsoleCallback mConsoleCallback = new IXKernelVmConsoleCallback.Stub() {
        @Override
        public void onConsoleOutput(ConsoleOutput output) {
            mMain.post(() -> onConsoleOutputPiece(output));
        }
    };

    private final Runnable mRenderTerminal = this::renderTerminal;

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
        setUpConsole();
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
        attachConsole(control);
    }

    private void disconnect() {
        IXKernelVmControl control = mControl;
        if (control != null) {
            mCalls.execute(() -> {
                try {
                    control.unregisterCallback(mCallback);
                    control.detachConsole(mConsoleCallback);
                } catch (RemoteException | RuntimeException e) {
                    Log.i(TAG, "unregisterCallback failed", e);
                }
            });
        }
        mConsoleSynced = false;
        mConsolePending.clear();
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
        mConsoleSynced = false;
        mConsolePending.clear();
        renderConsoleControls();
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
        line(d, R.string.label_launcher,
                getString(s.viaAvf ? R.string.launcher_avf : R.string.launcher_direct));
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
        renderConsoleControls();
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

    // ---- Serial console ----------------------------------------------------------------------

    /** A handle on the key buttons so they enable together. */
    private static final class ViewGroupKeys {
        final View[] views;

        ViewGroupKeys(View... views) {
            this.views = views;
        }

        void setEnabled(boolean enabled) {
            for (View v : views) {
                v.setEnabled(enabled);
            }
        }
    }

    private void setUpConsole() {
        mConsolePane = findViewById(R.id.console_pane);
        mConsoleTitle = findViewById(R.id.console_title);
        mTerminalScroll = findViewById(R.id.terminal_scroll);
        mTerminalView = findViewById(R.id.terminal);
        mConsoleNote = findViewById(R.id.console_note);
        mInput = findViewById(R.id.input);
        mSend = findViewById(R.id.send);
        mTerminal = new TerminalBuffer(this::sendToGuest);

        mSend.setOnClickListener(v -> sendLine());
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) {
                    sendLine();
                }
                return true;
            }
            return false;
        });
        Button ctrlC = findViewById(R.id.key_ctrl_c);
        Button tab = findViewById(R.id.key_tab);
        Button up = findViewById(R.id.key_up);
        Button down = findViewById(R.id.key_down);
        Button ctrlD = findViewById(R.id.key_ctrl_d);
        Button clear = findViewById(R.id.key_clear);
        // Ctrl-C and Ctrl-D drop what is typed but not sent; Tab sends it for completion.
        ctrlC.setOnClickListener(v -> {
            mInput.setText("");
            sendToGuest(new byte[] {0x03});
        });
        ctrlD.setOnClickListener(v -> {
            mInput.setText("");
            sendToGuest(new byte[] {0x04});
        });
        tab.setOnClickListener(v -> sendTyped("\t"));
        up.setOnClickListener(v -> sendToGuest("\033[A".getBytes(StandardCharsets.US_ASCII)));
        down.setOnClickListener(v -> sendToGuest("\033[B".getBytes(StandardCharsets.US_ASCII)));
        clear.setOnClickListener(v -> {
            // Only this view: the guest and the kept output are unchanged.
            mTerminal.reset();
            mStickToEnd = true;
            renderTerminal();
        });
        mKeys = new ViewGroupKeys(ctrlC, tab, up, down, ctrlD, mSend, mInput);

        mTerminalScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> mStickToEnd = atEnd());
        mTerminalView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (mStickToEnd) {
                scrollToEnd();
            }
        });

        // Edge to edge: keep clear of the system bars, and of the keyboard, which then takes
        // space from the state pane rather than from the console.
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            setWeight(mConsolePane, ime.bottom > 0 ? 2f : 1f);
            return WindowInsets.CONSUMED;
        });
        renderConsoleControls();
    }

    private static void setWeight(View view, float weight) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) view.getLayoutParams();
        if (lp.weight != weight) {
            lp.weight = weight;
            view.setLayoutParams(lp);
        }
    }

    private void attachConsole(IXKernelVmControl control) {
        mConsoleSynced = false;
        mConsolePending.clear();
        mCalls.execute(() -> {
            try {
                ConsoleOutput history = control.attachConsole(mConsoleCallback);
                mMain.post(() -> onConsoleHistory(control, history));
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "attachConsole failed", e);
                mMain.post(() -> showConsoleNote(getString(R.string.console_attach_failed,
                        describe(e))));
            }
        });
    }

    private void onConsoleHistory(IXKernelVmControl control, ConsoleOutput history) {
        if (control != mControl) {
            return;
        }
        mTerminal.reset();
        // Replayed output asked its questions long ago: nothing is answered from it.
        mTerminal.feed(history.data, 0, false);
        mConsoleInstance = history.instance;
        mConsoleEnd = history.offset + history.data.length;
        mConsoleSynced = true;
        mStickToEnd = true;
        ArrayList<ConsoleOutput> pending = new ArrayList<>(mConsolePending);
        mConsolePending.clear();
        for (ConsoleOutput piece : pending) {
            onConsoleOutputPiece(piece);
        }
        renderConsoleControls();
        scheduleRender();
    }

    private void onConsoleOutputPiece(ConsoleOutput piece) {
        if (mControl == null) {
            return;
        }
        if (!mConsoleSynced) {
            mConsolePending.add(piece);
            return;
        }
        if (piece.instance != mConsoleInstance) {
            if (piece.instance < mConsoleInstance) {
                return; // An earlier instance's late output.
            }
            if (piece.offset != 0) {
                attachConsole(mControl); // Missed the start of a newer instance.
                return;
            }
            mTerminal.reset();
            mConsoleInstance = piece.instance;
            mConsoleEnd = 0;
            mStickToEnd = true;
            renderConsoleControls();
        }
        long end = piece.offset + piece.data.length;
        if (end <= mConsoleEnd) {
            if (piece.data.length == 0) {
                scheduleRender();
            }
            return;
        }
        if (piece.offset > mConsoleEnd) {
            Log.i(TAG, "console gap at " + mConsoleEnd + ", attaching again");
            attachConsole(mControl);
            return;
        }
        mTerminal.feed(piece.data, (int) (mConsoleEnd - piece.offset), true);
        mConsoleEnd = end;
        scheduleRender();
    }

    private void scheduleRender() {
        if (!mRenderScheduled) {
            mRenderScheduled = true;
            mMain.postDelayed(mRenderTerminal, RENDER_DELAY_MS);
        }
    }

    private void renderTerminal() {
        mRenderScheduled = false;
        mTerminalView.setText(mTerminal.render());
        if (mStickToEnd) {
            scrollToEnd();
        }
    }

    private boolean atEnd() {
        View child = mTerminalScroll.getChildAt(0);
        int slack = mTerminalView.getLineHeight() * 2;
        return child.getBottom() - (mTerminalScroll.getScrollY() + mTerminalScroll.getHeight())
                <= slack;
    }

    private void scrollToEnd() {
        // ScrollView clamps to its content (a huge value overflows its clamp, so pass the content
        // height); fullScroll() would move the focus off the input.
        mTerminalScroll.post(() -> mTerminalScroll.scrollTo(0, mTerminalView.getHeight()));
    }

    private void renderConsoleControls() {
        VmStatus s = mStatus;
        boolean running = mControl != null && isRunning(s);
        mKeys.setEnabled(running);
        mInput.setHint(running ? R.string.console_hint : R.string.console_hint_not_running);
        if (mControl == null || s == null) {
            mConsoleTitle.setText(R.string.console_title_disconnected);
        } else if (mConsoleInstance <= 0) {
            mConsoleTitle.setText(R.string.console_title_none);
        } else if (running && s.instance == mConsoleInstance) {
            mConsoleTitle.setText(getString(R.string.console_title_running, mConsoleInstance));
        } else {
            mConsoleTitle.setText(getString(R.string.console_title_ended, mConsoleInstance));
        }
        if (running) {
            mConsoleNote.setVisibility(View.GONE);
        }
    }

    private void sendLine() {
        sendTyped("\n");
    }

    /** Sends what is typed, then {@code end}, and empties the input line. */
    private void sendTyped(String end) {
        String text = mInput.getText().toString();
        mInput.setText("");
        sendToGuest((text + end).getBytes(StandardCharsets.UTF_8));
    }

    private void sendToGuest(byte[] data) {
        IXKernelVmControl control = mControl;
        if (control == null || !isRunning(mStatus)) {
            return;
        }
        mStickToEnd = true;
        mCalls.execute(() -> {
            try {
                for (int i = 0; i < data.length; i += MAX_INPUT) {
                    control.writeConsole(
                            Arrays.copyOfRange(data, i, Math.min(data.length, i + MAX_INPUT)));
                }
            } catch (ServiceSpecificException e) {
                String note = e.errorCode == IXKernelVmControl.ERROR_NOT_RUNNING
                        ? getString(R.string.console_not_running)
                        : e.errorCode == IXKernelVmControl.ERROR_BUSY
                                ? getString(R.string.console_busy, describe(e))
                                : getString(R.string.console_failed, describe(e));
                mMain.post(() -> showConsoleNote(note));
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "writeConsole failed", e);
                mMain.post(() -> showConsoleNote(getString(R.string.console_failed,
                        describe(e))));
            }
        });
    }

    private void showConsoleNote(String note) {
        mConsoleNote.setText(note);
        mConsoleNote.setVisibility(View.VISIBLE);
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
