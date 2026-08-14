package com.carlink.headunit;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.carlink.headunit.net.Protocol;

/**
 * Connection screen: phone IP / control port inputs, connect button, status line.
 * The last entered values are persisted in SharedPreferences.
 */
public class MainActivity extends Activity {

    static final String EXTRA_IP = "com.carlink.headunit.extra.IP";
    static final String EXTRA_PORT = "com.carlink.headunit.extra.PORT";
    static final String RESULT_EXTRA_DISCONNECT_REASON = "com.carlink.headunit.extra.DISCONNECT_REASON";

    private static final int REQUEST_PROJECTION = 1;

    private static final String PREFS_NAME = "carlink";
    private static final String PREF_IP = "last_ip";
    private static final String PREF_PORT = "last_port";

    /** Phone hotspot default gateway address. */
    private static final String DEFAULT_IP = "192.168.43.1";

    private SharedPreferences prefs;
    private EditText editIp;
    private EditText editPort;
    private TextView textStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        editIp = findViewById(R.id.edit_ip);
        editPort = findViewById(R.id.edit_port);
        textStatus = findViewById(R.id.text_status);
        Button btnConnect = findViewById(R.id.btn_connect);

        editIp.setText(prefs.getString(PREF_IP, DEFAULT_IP));
        editPort.setText(String.valueOf(prefs.getInt(PREF_PORT, Protocol.DEFAULT_CONTROL_PORT)));

        btnConnect.setOnClickListener(v -> startProjection());
    }

    private void startProjection() {
        String ip = editIp.getText().toString().trim();
        if (ip.isEmpty()) {
            textStatus.setText(R.string.error_invalid_ip);
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(editPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            textStatus.setText(R.string.error_invalid_port);
            return;
        }
        if (port <= 0 || port > 65535) {
            textStatus.setText(R.string.error_invalid_port);
            return;
        }

        prefs.edit().putString(PREF_IP, ip).putInt(PREF_PORT, port).apply();

        textStatus.setText(R.string.status_connecting);
        Intent intent = new Intent(this, ProjectionActivity.class);
        intent.putExtra(EXTRA_IP, ip);
        intent.putExtra(EXTRA_PORT, port);
        startActivityForResult(intent, REQUEST_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROJECTION) {
            String reason = data != null ? data.getStringExtra(RESULT_EXTRA_DISCONNECT_REASON) : null;
            if (reason != null && !reason.isEmpty()) {
                textStatus.setText(getString(R.string.status_disconnected_with_reason, reason));
            } else {
                textStatus.setText(R.string.status_disconnected);
            }
        }
    }
}
