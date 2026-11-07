package com.a1.donut;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements VncView.Listener {
    private static final int BG = 0xFF0E1114;
    private static final int BAR = 0xE6000000;
    private static final int DIM = 0xFF9AA4AE;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService keys = Executors.newSingleThreadExecutor();

    private VncView vnc;
    private LinearLayout startPanel;
    private LinearLayout keysBar;
    private Button startButton;
    private Button stopButton;
    private TextView status;
    private TextView hint;
    private TextView diag;
    private long hintUntil;
    private boolean started;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            String phase = VmService.phase;
            if (phase != null && phase.startsWith("failed")) {
                started = false;
                vnc.disconnect();
                showStartUi();
                status.setText(phase);
                return;
            }
            hint.setText(describe(phase));
            boolean showHint = System.currentTimeMillis() < hintUntil && !VncView.guestSettled();
            hint.setVisibility(showHint ? View.VISIBLE : View.GONE);
            String st = VncView.status();
            String tail = st.startsWith("connected ") ? "" : VmService.serialTail(70);
            diag.setText("vnc: " + st + (tail.isEmpty() ? "" : "  |  " + tail));
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        if (VmService.running) {
            started = true;
            showRunningUi();
            hint.setText(describe(VmService.phase));
            hintUntil = System.currentTimeMillis() + 20000;
            vnc.connect();
            ui.postDelayed(poll, 2000);
        } else {
            showStartUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started) vnc.connect();
    }

    @Override
    protected void onDestroy() {
        vnc.disconnect();
        ui.removeCallbacks(poll);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (started) {
            keys.execute(new Runnable() {
                @Override
                public void run() {
                    KeyInjector.send("input keyevent 4");
                }
            });
        } else {
            super.onBackPressed();
        }
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        vnc = new VncView(this, this);
        int barHeight = dp(54);
        FrameLayout.LayoutParams vncParams = new FrameLayout.LayoutParams(-1, -1);
        vncParams.bottomMargin = barHeight;
        root.addView(vnc, vncParams);

        keysBar = new LinearLayout(this);
        keysBar.setOrientation(LinearLayout.HORIZONTAL);
        keysBar.setBackgroundColor(BAR);
        keysBar.setVisibility(View.GONE);
        keysBar.addView(keyButton("Back", 4), weight());
        keysBar.addView(keyButton("Menu", 82), weight());
        keysBar.addView(keyButton("Home", 3), weight());
        root.addView(keysBar, new FrameLayout.LayoutParams(-1, barHeight, Gravity.BOTTOM));

        stopButton = new Button(this);
        stopButton.setText("Stop");
        stopButton.setAllCaps(false);
        stopButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        stopButton.setTextColor(Color.WHITE);
        stopButton.setBackgroundColor(0x99000000);
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopVm();
            }
        });
        FrameLayout.LayoutParams stopParams = new FrameLayout.LayoutParams(dp(76), dp(38), Gravity.TOP | Gravity.RIGHT);
        stopParams.topMargin = dp(8);
        stopParams.rightMargin = dp(8);
        root.addView(stopButton, stopParams);

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0x99000000);
        hint.setPadding(dp(10), dp(5), dp(10), dp(5));
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintParams.topMargin = dp(10);
        root.addView(hint, hintParams);

        diag = new TextView(this);
        diag.setTextColor(0xFFB0BEC5);
        diag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        diag.setBackgroundColor(0x99000000);
        diag.setPadding(dp(6), dp(3), dp(6), dp(3));
        diag.setSingleLine(true);
        diag.setEllipsize(android.text.TextUtils.TruncateAt.END);
        diag.setMaxWidth(dp(330));
        diag.setVisibility(View.GONE);
        FrameLayout.LayoutParams diagParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        diagParams.bottomMargin = barHeight + dp(6);
        diagParams.leftMargin = dp(8);
        root.addView(diag, diagParams);

        startPanel = new LinearLayout(this);
        startPanel.setOrientation(LinearLayout.VERTICAL);
        startPanel.setGravity(Gravity.CENTER);
        startPanel.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("Android 1.6");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setGravity(Gravity.CENTER);

        TextView sub = new TextView(this);
        sub.setText("Donut \u00b7 QEMU \u00b7 VNC");
        sub.setTextColor(DIM);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4), 0, dp(20));

        status = new TextView(this);
        status.setTextColor(DIM);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), 0, dp(24), dp(12));

        startButton = new Button(this);
        startButton.setText("Start");
        startButton.setAllCaps(false);
        startButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVm();
            }
        });

        startPanel.addView(title);
        startPanel.addView(sub);
        startPanel.addView(startButton);
        startPanel.addView(status);
        root.addView(startPanel, new FrameLayout.LayoutParams(-1, -1));

        return root;
    }

    private Button keyButton(String label, final int code) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keys.execute(new Runnable() {
                    @Override
                    public void run() {
                        KeyInjector.send("input keyevent " + code);
                    }
                });
            }
        });
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -1, 1f);
    }

    private void startVm() {
        started = true;
        startButton.setEnabled(false);
        Intent intent = new Intent(this, VmService.class);
        intent.setAction(VmService.ACTION_START);
        startService(intent);
        vnc.connect();
        showRunningUi();
        hint.setText("starting\u2026");
        hint.setVisibility(View.VISIBLE);
        hintUntil = System.currentTimeMillis() + 240000;
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 500);
    }

    private void stopVm() {
        started = false;
        ui.removeCallbacks(poll);
        vnc.disconnect();
        Intent intent = new Intent(this, VmService.class);
        intent.setAction(VmService.ACTION_STOP);
        startService(intent);
        showStartUi();
    }

    private void showStartUi() {
        startPanel.setVisibility(View.VISIBLE);
        keysBar.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
        diag.setVisibility(View.GONE);
        startButton.setEnabled(true);
        status.setText("stopped");
    }

    private void showRunningUi() {
        startPanel.setVisibility(View.GONE);
        keysBar.setVisibility(View.VISIBLE);
        stopButton.setVisibility(View.VISIBLE);
        diag.setVisibility(View.VISIBLE);
    }

    @Override
    public void onVncConnected() {
        if (started) showRunningUi();
    }

    @Override
    public void onVncDisconnected() {
        // The view retries on its own; keep the last frame visible.
    }

    private static String describe(String phase) {
        if (phase == null) return "";
        if (phase.equals("preparing")) return "unpacking system files\u2026 (first run only)";
        if (phase.equals("booting")) return "starting QEMU\u2026";
        if (phase.equals("running")) return "guest booting \u00b7 can take several minutes";
        if (phase.equals("stopped")) return "stopped";
        return phase;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
