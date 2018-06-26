package com.example.echo;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.widget.EditText;


public class MainActivity extends AppCompatActivity {

    private EchoServer mEchoServer;
    private EchoClient mEchoClient;

    private EditText mMsg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final int port = 9877;
        mEchoServer = new EchoServer(port);
        mEchoServer.run();
        mEchoClient = new EchoClient("localhost", port);

        mMsg = findViewById(R.id.msg);
        findViewById(R.id.send).setOnClickListener((view) -> {
            String msg = mMsg.getText().toString();
            if (TextUtils.isEmpty(msg)) {
                return;
            }
            mEchoClient.send(msg);
            mMsg.setText("");
        });
    }
}
