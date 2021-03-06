package edu.stanford.thingengine.engine.service;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import edu.stanford.thingengine.engine.CloudAuthInfo;

/**
 * Created by gcampagn on 8/10/15.
 */
public class ControlChannel implements AutoCloseable, Closeable {
    private static final AtomicInteger count = new AtomicInteger(0);
    private final EngineService service;
    private final Reader controlReader;
    private final StringBuilder partialMsg = new StringBuilder();
    private final Writer controlWriter;
    private final LinkedList<Reply> queuedReplies = new LinkedList<>();
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    private static class Reply {
        private final String replyId;
        private final Object result;
        private final String error;

        public Reply(String replyId, Object result, String error) {
            this.replyId = replyId;
            this.result = result;
            this.error = error;
        }
    }

    public ControlChannel(EngineService ctx) throws IOException {
        service = ctx;

        LocalSocket socket = new LocalSocket();

        socket.connect(new LocalSocketAddress(ctx.getFilesDir() + "/control", LocalSocketAddress.Namespace.FILESYSTEM));
        // we would like to use UTF-16BE because that's what Java uses internally, but node only has UTF16-LE
        // so we use that and pay the byteswap, rather than paying the higher UTF-8 encoding cost
        controlReader = new BufferedReader(new InputStreamReader(socket.getInputStream(), Charset.forName("UTF-16LE")));
        controlWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), Charset.forName("UTF-16LE")));
    }

    public ExecutorService getThreadPool() {
        return threadPool;
    }

    public void close() throws IOException {
        synchronized (this) {
            controlReader.close();
        }
        synchronized (controlWriter) {
            controlWriter.close();
        }
    }

    private String sendCall(String method, Object... arguments) throws IOException {
        try {
            JSONObject call = new JSONObject();
            call.put("method", method);

            JSONArray jsonArgs = new JSONArray();
            for (int i = 0; i < arguments.length; i++) {
                jsonArgs.put(i, arguments[i]);
            }
            call.put("args", jsonArgs);

            String replyId = "reply_" + count.getAndAdd(1);
            call.put("replyId", replyId);

            synchronized (controlWriter) {
                controlWriter.write(call.toString());
                controlWriter.flush();
            }

            return replyId;
        } catch(JSONException e) {
            Log.e(EngineService.LOG_TAG, "Failed to serialize method call to control channel", e);
            throw new RuntimeException(e);
        }
    }

    private Reply readOneReply() throws IOException {
        JSONObject value = null;
        try {
            while (value == null) {
                try {
                    int newLine = partialMsg.indexOf("\n");
                    while (newLine == 0) {
                        partialMsg.deleteCharAt(0);
                        newLine = partialMsg.indexOf("\n");
                    }
                    if (newLine < 0) {
                        char[] buffer = new char[64];
                        int read = controlReader.read(buffer);
                        if (read < 0)
                            throw new EOFException("Control channel closed");
                        partialMsg.append(buffer, 0, read);
                        continue;
                    }

                    String prefix = partialMsg.substring(0, newLine);
                    JSONTokener tokener = new JSONTokener(prefix);
                    value = (JSONObject) tokener.nextValue();
                    partialMsg.delete(0, newLine+1);
                } catch (JSONException e) {
                    Log.d(EngineService.LOG_TAG, "Partial message received");
                }
            }

            if (value.has("error"))
                return new Reply(value.getString("id"), null, value.getString("error"));
            else if (value.has("reply"))
                return new Reply(value.getString("id"), value.get("reply"), null);
            else
                return new Reply(value.getString("id"), null, null);
        } catch(JSONException e) {
            Log.e(EngineService.LOG_TAG, "Failed to parse method reply on control channel", e);
            throw new RuntimeException(e);
        }
    }

    private Object expectReply(String replyId) throws Exception {
        Reply reply = null;

        while (reply == null) {
            synchronized (this) {
                Iterator<Reply> it = queuedReplies.iterator();
                while (it.hasNext()) {
                    Reply value = it.next();
                    if (value.replyId.equals(replyId)) {
                        reply = value;
                        it.remove();
                    }
                }

                if (reply == null) {
                    if (queuedReplies.size() > 0) {
                        wait();
                    } else {
                        queuedReplies.add(readOneReply());
                        notifyAll();
                    }
                } else {
                    notifyAll();
                }
            }
        }

        if (reply.error != null)
            throw new Exception(reply.error);
        else
            return reply.result;
    }

    public void sendStop() throws IOException {
        sendCall("stop");
    }

    public void sendInvokeCallback(String callback, String error, Object value) {
        try {
            expectReply(sendCall("invokeCallback", callback, error, value));
        } catch(Exception e) {
            Log.e(EngineService.LOG_TAG, "Unexpected exception in 'invokeCallback' command", e);
            throw new RuntimeException(e);
        }
    }

    public boolean sendSetCloudId(CloudAuthInfo authInfo) {
        try {
            return (Boolean)expectReply(sendCall("setCloudId", authInfo.getCloudId(), authInfo.getAuthToken()));
        } catch(Exception e) {
            Log.e(EngineService.LOG_TAG, "Unexpected exception in 'setCloudId' command", e);
            return false;
        }
    }

    public boolean sendSetServerAddress(String host, int port, String authToken) {
        try {
            return (Boolean)expectReply(sendCall("setServerAddress", host, port, authToken));
        } catch(Exception e) {
            Log.e(EngineService.LOG_TAG, "Unexpected exception in 'setServerAddress' command", e);
            return false;
        }
    }

    public boolean sendHandleOAuth2Callback(String kind, JSONObject req) throws Exception {
        return (Boolean)expectReply(sendCall("handleOAuth2Callback", kind, req));
    }

    public JSONArray sendStartOAuth2(String kind) throws Exception {
        return (JSONArray)expectReply(sendCall("startOAuth2", kind));
    }

    public boolean sendCreateDevice(JSONObject state) throws Exception {
        return (Boolean)expectReply(sendCall("createDevice", state));
    }

    public boolean sendDeleteDevice(String uniqueId) throws Exception {
        return (Boolean)expectReply(sendCall("deleteDevice", uniqueId));
    }

    public boolean sendUpgradeDevice(String kind) throws Exception {
        return (Boolean)expectReply(sendCall("upgradeDevice", kind));
    }

    public JSONArray sendGetDeviceInfos() throws Exception {
        return (JSONArray)expectReply(sendCall("getDeviceInfos"));
    }

    public JSONObject sendGetDeviceInfo(String uniqueId) throws Exception {
        return (JSONObject)expectReply(sendCall("getDeviceInfo", uniqueId));
    }

    public int sendCheckDeviceAvailable(String uniqueId) throws Exception {
        return (Integer)expectReply(sendCall("checkDeviceAvailable", uniqueId));
    }

    public JSONArray sendGetAppInfos() throws Exception {
        return (JSONArray)expectReply(sendCall("getAppInfos"));
    }

    public boolean sendDeleteApp(String uniqueId) throws Exception {
        return (Boolean)expectReply(sendCall("deleteApp", uniqueId));
    }

    public boolean sendPresentSlotFilling(String utterance, String targetJson) throws Exception {
        return (Boolean)expectReply(sendCall("presentSlotFilling", utterance, targetJson));
    }
}
