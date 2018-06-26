package com.example.echo;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;


/**
 * @author jekton
 */
public final class LongLiveSocket {
    private static final String TAG = "LongLiveSocket";

    private static final long RETRY_INTERVAL_MILLIS = 3 * 1000;
    private static final long HEART_BEAT_INTERVAL_MILLIS = 5 * 1000;

    /**
     * 错误回调
     */
    public interface ErrorCallback {
        /**
         * 如果需要重连，返回 true
         */
        boolean onError();
    }


    /**
     * 读数据回调
     */
    public interface DataCallback {
        void onData(byte[] data, int offset, int len);
    }


    /**
     * 写数据回调
     */
    public interface WritingCallback {
        void onSuccess();
        void onFail(byte[] data, int offset, int len);
    }


    private final String mHost;
    private final int mPort;
    private final DataCallback mDataCallback;
    private final ErrorCallback mErrorCallback;

    private final AtomicReference<Socket> mSocketRef = new AtomicReference<>();
    private final HandlerThread mWriterThread;
    private final Handler mWriterHandler;

    private final Runnable mHeartBeatTask = new Runnable() {
        private byte[] mHeartBeat = new byte[0];

        @Override
        public void run() {
            write(mHeartBeat, new WritingCallback() {
                @Override
                public void onSuccess() {
                    mWriterHandler.postDelayed(mHeartBeatTask, HEART_BEAT_INTERVAL_MILLIS);
                }

                @Override
                public void onFail(byte[] data, int offset, int len) {
                    // nop
                }
            });
        }
    };


    public LongLiveSocket(String host, int port,
                          DataCallback dataCallback, ErrorCallback errorCallback) {
        mHost = host;
        mPort = port;
        mDataCallback = dataCallback;
        mErrorCallback = errorCallback;

        mWriterThread = new HandlerThread("socket-writer");
        mWriterThread.start();
        mWriterHandler = new Handler(mWriterThread.getLooper());
        mWriterHandler.post(this::initSocket);
    }

    private void initSocket() {
        while (true) {
            try {
                Socket socket = new Socket(mHost, mPort);
                mSocketRef.set(socket);
                Thread reader = new Thread(new ReaderTask(socket));
                reader.start();
                mWriterHandler.post(mHeartBeatTask);
                break;
            } catch (IOException e) {
                Log.e(TAG, "initSocket: ", e);
                if (!mErrorCallback.onError()) {
                    break;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_INTERVAL_MILLIS);
                } catch (InterruptedException e1) {
                    // interrupt writer-thread to quit
                    break;
                }
            }
        }
    }

    public void write(byte[] data, WritingCallback callback) {
        write(data, 0, data.length, callback);
    }

    public void write(byte[] data, int offset, int len, WritingCallback callback) {
        mWriterHandler.post(() -> {
            Socket socket = mSocketRef.get();
            if (socket == null) {
                throw new IllegalStateException("Socket not initialized");
            }
            try {
                OutputStream outputStream = socket.getOutputStream();
                DataOutputStream out = new DataOutputStream(outputStream);
                out.writeInt(len);
                out.write(data, offset, len);
                callback.onSuccess();
            } catch (IOException e) {
                Log.e(TAG, "write: ", e);
                closeSocket();
                callback.onFail(data, offset, len);
                if (mErrorCallback.onError()) {
                    initSocket();
                }
            }
        });
    }

    private void closeSocket() {
        Socket socket = mSocketRef.getAndSet(null);
        if (socket != null) {
            silentlyClose(socket);
            mWriterHandler.removeCallbacks(mHeartBeatTask);
        }
    }

    public void close() {
        mWriterThread.quit();
        closeSocket();
        mWriterThread.interrupt();
    }


    private static void silentlyClose(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                Log.e(TAG, "silentlyClose: ", e);
                // error ignored
            }
        }
    }


    private class ReaderTask implements Runnable {

        private final Socket mSocket;

        public ReaderTask(Socket socket) {
            mSocket = socket;
        }

        @Override
        public void run() {
            try {
                readResponse();
            } catch (IOException e) {
                Log.e(TAG, "ReaderTask#run: ", e);
            }
        }

        private void readResponse() throws IOException {
            // For simplicity, assume that a msg will not exceed 1024-byte
            byte[] buffer = new byte[1024];
            InputStream inputStream = mSocket.getInputStream();
            DataInputStream in = new DataInputStream(inputStream);
            while (true) {
                int nbyte = in.readInt();
                if (nbyte == 0) {
                    Log.i(TAG, "readResponse: heart beat received");
                    continue;
                }

                if (nbyte > buffer.length) {
                    throw new IllegalStateException("Receive message with len " + nbyte +
                                    " which exceeds limit " + buffer.length);
                }

                if (readn(in, buffer, nbyte) != 0) {
                    // Socket might be closed twice but it does no harm
                    silentlyClose(mSocket);
                    // Socket will be re-connected by writer-thread if you want
                    break;
                }
                mDataCallback.onData(buffer, 0, nbyte);
            }
        }

        private int readn(InputStream in, byte[] buffer, int n) throws IOException {
            int offset = 0;
            while (n > 0) {
                int readBytes = in.read(buffer, offset, n);
                if (readBytes < 0) {
                    // EOF
                    break;
                }
                n -= readBytes;
                offset += readBytes;
            }
            return n;
        }
    }
}
