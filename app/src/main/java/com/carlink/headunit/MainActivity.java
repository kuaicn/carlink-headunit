package com.carlink.headunit;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.carlink.headunit.net.Protocol;

/**
 * Connection screen: phone IP / control port inputs, connect button, status line.
 * The last entered values are persisted in SharedPreferences.
 */
public class MainActivity extends Activity {

    private static final String TAG = "CarLinkHeadunit";
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
    private Button btnConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        editIp = findViewById(R.id.edit_ip);
        editPort = findViewById(R.id.edit_port);
        textStatus = findViewById(R.id.text_status);
        btnConnect = findViewById(R.id.btn_connect);

        editIp.setText(prefs.getString(PREF_IP, DEFAULT_IP));
        editPort.setText(String.valueOf(prefs.getInt(PREF_PORT, Protocol.DEFAULT_CONTROL_PORT)));

        btnConnect.setOnClickListener(v -> startProjection());
        // IME "done" on the port field connects directly, without reaching for the button
        editPort.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                startProjection();
                return true;
            }
            return false;
        });
    }

    private void startProjection() {
        String ip = editIp.getText().toString().trim();
        if (!isValidIpv4(ip)) {
            Log.d(TAG, "invalid IP input: \"" + ip + "\"");
            textStatus.setText(R.string.error_invalid_ip);
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(editPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            Log.d(TAG, "invalid port input: \"" + editPort.getText().toString().trim() + "\"");
            textStatus.setText(R.string.error_invalid_port);
            return;
        }
        if (port <= 0 || port > 65535) {
            Log.d(TAG, "port out of range: " + port);
            textStatus.setText(R.string.error_invalid_port);
            return;
        }

        prefs.edit().putString(PREF_IP, ip).putInt(PREF_PORT, port).apply();

        // Disable until the projection returns: a double tap must not stack two
        // ProjectionActivity instances racing for the phone's single session slot
        btnConnect.setEnabled(false);
        textStatus.setText(R.string.status_connecting);
        Log.i(TAG, "connecting to " + ip + ":" + port);
        Intent intent = new Intent(this, ProjectionActivity.class);
        intent.putExtra(EXTRA_IP, ip);
        intent.putExtra(EXTRA_PORT, port);
        startActivityForResult(intent, REQUEST_PROJECTION);
    }

    /** Basic dotted-quad validation: four numeric octets, each within 0-255. */
    private static boolean isValidIpv4(String ip) {
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int i = 0; i < octet.length(); i++) {
                char c = octet.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROJECTION) {
            btnConnect.setEnabled(true);
            String reason = data != null ? data.getStringExtra(RESULT_EXTRA_DISCONNECT_REASON) : null;
            if (reason != null && !reason.isEmpty()) {
                Log.i(TAG, "projection ended, reason: " + reason);
                textStatus.setText(getString(R.string.status_disconnected_with_reason, reason));
            } else {
                Log.i(TAG, "projection ended by the user");
                textStatus.setText(R.string.status_disconnected);
            }
        }
    }
}
