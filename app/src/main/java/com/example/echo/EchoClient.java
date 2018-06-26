package com.example.echo;

import android.util.Log;


/**
 * @author Jekton
 */
public class EchoClient {
    private static final String TAG = "EchoClient";

    private final LongLiveSocket mLongLiveSocket;

    public EchoClient(String host, int port) {
        mLongLiveSocket = new LongLiveSocket(
                host, port,
                (data, offset, len) -> Log.i(TAG, "EchoClient: received: " + new String(data, offset, len)),
                () -> true);
    }

    public void send(String msg) {
        mLongLiveSocket.write(msg.getBytes(), new LongLiveSocket.WritingCallback() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "onSuccess: ");
            }

            @Override
            public void onFail(byte[] data, int offset, int len) {
                Log.w(TAG, "onFail: fail to write: " + new String(data, offset, len));
            }
        });
    }
}
