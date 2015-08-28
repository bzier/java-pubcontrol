//        PubControlClient.java
//        ~~~~~~~~~
//        This module implements the PubControlClient class.
//        :authors: Konstantin Bokarius.
//        :copyright: (c) 2015 by Fanout, Inc.
//        :license: MIT, see LICENSE for more details.

package org.fanout.pubcontrol;

import java.util.concurrent.locks.*;
import java.util.*;
import java.io.UnsupportedEncodingException;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;

import java.security.Key;
import javax.xml.bind.DatatypeConverter;
import javax.crypto.spec.SecretKeySpec;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.impl.crypto.MacProvider;
import com.google.gson.Gson;

// The PubControlClient class allows consumers to publish either synchronously
// or asynchronously to an endpoint of their choice. The consumer wraps a Format
// class instance in an Item class instance and passes that to the publish
// methods. The async publish method has an optional callback parameter that
// is called after the publishing is complete to notify the consumer of the
// result.
public class PubControlClient implements Runnable {
    private String uri;
    private final Lock lock = new ReentrantLock();
    private final Lock pubWorkerLock = new ReentrantLock();
    private final Condition pubWorkerCond = this.pubWorkerLock.newCondition();
    private Thread pubWorker;
    private Deque<Object[]> reqQueue = new LinkedList<Object[]>();
    private String authBasicUser;
    private String authBasicPass;;
    private Map<String, Object> authJwtClaim;
    private byte[] authJwtKey;

    // Initialize this class with a URL representing the publishing endpoint.
    public PubControlClient(String uri) {
        this.uri = uri;
    }

    // Call this method and pass a username and password to use basic
    // authentication with the configured endpoint.
    public void setAuthBasic(String userName, String password) {
        this.lock.lock();
        this.authBasicUser = userName;
        this.authBasicPass = password;
        this.lock.unlock();
    }

    // Call this method and pass a claim and key to use JWT authentication
    // with the configured endpoint.
    public void setAuthJwt(Map<String, Object> claims, byte[] key) {
        this.lock.lock();
        this.authJwtClaim = claims;
        this.authJwtKey = key;
        this.lock.unlock();
    }

    // The synchronous publish method for publishing the specified item to the
    // specified channel on the configured endpoint.
    public void publish(List<String> channels, Item item)
            throws PublishFailedException {
        List<Map<String, Object>> exports = new ArrayList<Map<String, Object>>();
        for (String channel : channels) {
            Map<String, Object> export = item.export();
            export.put("channel", channel);
            exports.add(export);
        }
        String uri = null;
        String auth = null;
        this.lock.lock();
        uri = this.uri;
        auth = this.genAuthHeader();
        this.lock.unlock();
        this.pubCall(uri, auth, exports);
    }

    // The asynchronous publish method for publishing the specified item to the
    // specified channel on the configured endpoint. The callback method is
    // optional and will be passed the publishing results after publishing is
    // complete.
    public void publishAsync(List<String> channels, Item item, PublishCallback callback) {
        List<Map<String, Object>> exports = new ArrayList<Map<String, Object>>();
        for (String channel : channels) {
            Map<String, Object> export = item.export();
            export.put("channel", channel);
            exports.add(export);
        }
        String uri = null;
        String auth = null;
        this.lock.lock();
        uri = this.uri;
        auth = this.genAuthHeader();
        this.ensureThread();
        this.lock.unlock();
        Object[] req = {"pub", uri, auth, exports, callback};
        this.queueReq(req);
    }

    // The finish method is a blocking method that ensures that all asynchronous
    // publishing is complete prior to returning and allowing the consumer to
    // proceed.
    public void finish() {
        this.lock.lock();
        if (this.pubWorker != null) {
            Object[] req = {"stop"};
            this.queueReq(req);
            try {
                this.pubWorker.join();
            } catch (InterruptedException exception) { }
            this.pubWorker = null;
        }
        this.lock.unlock();
    }

    // An internal method that ensures that asynchronous publish calls are
    // properly processed. This method initializes the required class fields,
    // starts the pubworker worker thread, and is meant to execute only when
    // the consumer makes an asynchronous publish call.
    private void ensureThread() {
        if (this.pubWorker == null) {
            this.pubWorker = new Thread(this);
            this.pubWorker.start();
        }
    }

    // An internal method for adding an asynchronous publish request to the
    // publishing queue. This method will also activate the pubworker worker
    // thread to make sure that it process any and all requests added to
    // the queue.
    private void queueReq(Object[] req) {
        this.pubWorkerLock.lock();
        this.reqQueue.addLast(req);
        this.pubWorkerCond.signal();
        this.pubWorkerLock.unlock();
    }

    // An internal method used to generate an authorization header. The
    // authorization header is generated based on whether basic or JWT
    // authorization information was provided via the publicly accessible
    // 'set_*_auth' methods defined above.
    private String genAuthHeader() {
        if (this.authBasicUser != null && this.authBasicPass != null) {
            try {
                return Base64.getEncoder().encodeToString(
                        (this.authBasicUser + ":" +
                        this.authBasicPass).getBytes("utf-8"));
            } catch (UnsupportedEncodingException exception) { }
        }
        else if (this.authJwtClaim != null) {
            Key decodedKey = new SecretKeySpec(this.authJwtKey,
                    SignatureAlgorithm.HS256.getJcaName());
            Map<String, Object> claims = new HashMap<String, Object>();
            claims.putAll(this.authJwtClaim);
            if (this.authJwtClaim.get("exp") == null)
                claims.put("exp", new Date((new Date()).getTime() + (3600 * 1000)));
            String token = Jwts.builder().setClaims(claims).
                    signWith(SignatureAlgorithm.HS256, decodedKey).compact();
            return "Bearer " + token;
        }

        return null;
    }

    // An internal method for publishing a batch of requests. The requests are
    // parsed for the URI, authorization header, and each request is published
    // to the endpoint. After all publishing is complete, each callback
    // corresponding to each request is called (if a callback was originally
    // provided for that request) and passed a result indicating whether that
    // request was successfully published.
    @SuppressWarnings({"unchecked"})
    private void pubBatch(List<Object[]> reqs) {
        if (reqs.size() == 0)
            return;
        String authHeader = (String)reqs.get(0)[1];
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        List<PublishCallback> callbacks = new ArrayList<PublishCallback>();
        for (Object[] req : reqs) {
            items.addAll((List<Map<String, Object>>)req[2]);
            callbacks.add((PublishCallback)req[3]);
        }
        boolean result = true;
        String message = null;
        try {
            this.pubCall(uri, authHeader, items);
        } catch (Exception exception) {
            message = exception.getMessage();
        }
        for (PublishCallback callback : callbacks)
            if (callback != null)
                callback.completed(result, message);
    }

    // An internal method for preparing the HTTP POST request for publishing
    // data to the endpoint. This method accepts the URI endpoint, authorization
    // header, and a list of items to publish.
    private void pubCall(String uri, String authHeader,
            List<Map<String, Object>> items) throws PublishFailedException {
        URL url = null;
        try {
            url = new URL(uri + "/publish/");
        } catch (MalformedURLException exception) {
            throw new PublishFailedException("failed to publish: bad uri");
        }
        Map<String, Object> content = new HashMap<String, Object>();
        content.put("items", items);
        String jsonContent = new Gson().toJson(content);
        if (url.getProtocol() == "https")
            makeHttpsRequest(url, authHeader, jsonContent);
        else
            makeHttpRequest(url, authHeader, jsonContent);
    }

    // Make an HTTP request to publish the specified items.
    private void makeHttpRequest(URL url, String authHeader,
            String jsonContent) throws PublishFailedException {
        HttpURLConnection connection = null;
        int responseCode = 0;
        StringBuilder response = new StringBuilder();
        try {
            connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("POST");
            if (authHeader != null)
                connection.setRequestProperty("Authorization", authHeader);
            connection.setRequestProperty("Content-Type",
                    "application/json");
            connection.setRequestProperty("Content-Length",
                    Integer.toString(jsonContent.getBytes().length));
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            DataOutputStream dataOutputStream = new DataOutputStream (
            connection.getOutputStream());
            dataOutputStream.writeBytes(jsonContent);
            dataOutputStream.close();
            InputStream inputStream = connection.getInputStream();
            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(inputStream));
            String line;
            while((line = bufferedReader.readLine()) != null) {
                response.append(line);
                response.append('\r');
            }
            bufferedReader.close();
            responseCode = connection.getResponseCode();
        } catch (Exception exception) {
            throw new PublishFailedException("failed to publish: " +
                    exception.getMessage());
        } finally {
            if (connection != null)
                connection.disconnect();
        }
        if (responseCode < 200 || responseCode >= 300)
            throw new PublishFailedException("failed to publish: " +
                    Integer.toString(responseCode) + " " +
                    response.toString());
    }

    // Make an HTTPS request to publish the specified items.
    private void makeHttpsRequest(URL url, String authHeader,
            String jsonContent) throws PublishFailedException {
        HttpsURLConnection connection = null;
        int responseCode = 0;
        StringBuilder response = new StringBuilder();
        try {
            connection = (HttpsURLConnection)url.openConnection();
            connection.setRequestMethod("POST");
            if (authHeader != null)
                connection.setRequestProperty("Authorization", authHeader);
            connection.setRequestProperty("Content-Type",
                    "application/json");
            connection.setRequestProperty("Content-Length",
                    Integer.toString(jsonContent.getBytes().length));
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            DataOutputStream dataOutputStream = new DataOutputStream (
            connection.getOutputStream());
            dataOutputStream.writeBytes(jsonContent);
            dataOutputStream.close();
            InputStream inputStream = connection.getInputStream();
            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(inputStream));
            String line;
            while((line = bufferedReader.readLine()) != null) {
                response.append(line);
                response.append('\r');
            }
            bufferedReader.close();
            responseCode = connection.getResponseCode();
        } catch (Exception exception) {
            throw new PublishFailedException("failed to publish: " +
                    exception.getMessage());
        } finally {
            if (connection != null)
                connection.disconnect();
        }
        if (responseCode < 200 || responseCode >= 300)
            throw new PublishFailedException("failed to publish: " +
                    Integer.toString(responseCode) + " " +
                    response.toString());
    }

    // An internal class that is meant to run as a separate thread and process
    // asynchronous publishing requests. The method runs continously and
    // publishes requests in batches containing a maximum of 10 requests. The
    // method completes and the thread is terminated only when a 'stop' command
    // is provided in the request queue.
    public void run() {
        boolean quit = false;
        while (!quit) {
            this.pubWorkerLock.lock();
            if (this.reqQueue.size() == 0) {
                try {
                    this.pubWorkerCond.await();
                    if (this.reqQueue.size() == 0) {
                        this.pubWorkerLock.unlock();
                        continue;
                    }
                } catch (InterruptedException exception) {
                    this.pubWorkerLock.unlock();
                    continue;
                }
            }
            List<Object[]> reqs = new ArrayList<Object[]>();
            while (this.reqQueue.size() > 0 && reqs.size() < 10) {
                Object[] m = this.reqQueue.removeFirst();
                if (m[0] == "stop") {
                    quit = true;
                    break;
                }
                Object[] req = {m[1], m[2], m[3], m[4]};
                reqs.add(req);
            }
            this.pubWorkerLock.unlock();
            if (reqs.size() > 0)
                this.pubBatch(reqs);
        }
    }
}