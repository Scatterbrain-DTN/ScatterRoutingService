package net.ballmerlabs.uscatterbrain.network.wifidirect;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.subjects.BehaviorSubject;

public class InterceptableServerSocket extends ServerSocket {

    private boolean closed = false;

    public static class SocketConnection {
        private final Socket socket;

        public SocketConnection(Socket socket) {
            this.socket = socket;
        }
        public Socket getSocket() {
            return socket;
        }
    }

    public static class InterceptableServerSocketFactory {
        private InterceptableServerSocket serverSocket = null;
        public Single<InterceptableServerSocket> create(int port) {
            return Single.fromCallable(() -> {
                if (serverSocket == null) {
                    serverSocket = new InterceptableServerSocket(port);
                }
                Log.v(WifiDirectRadioModule.TAG, "creating server socket");
                return serverSocket;
            });


        }
    }

    private final Set<Socket> socketSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final BehaviorSubject<SocketConnection> socketBehaviorSubject = BehaviorSubject.create();

    public InterceptableServerSocket() throws IOException {
    }

    public InterceptableServerSocket(int port) throws IOException {
        super(port);
    }

    public InterceptableServerSocket(int port, int backlog) throws IOException {
        super(port, backlog);
    }

    public InterceptableServerSocket(int port, int backlog, InetAddress bindAddr) throws IOException {
        super(port, backlog, bindAddr);
    }


    public Observable<SocketConnection> acceptLoop() {
        return Observable.fromCallable(() -> new SocketConnection(accept()))
                .doOnError(err -> Log.e(WifiDirectRadioModule.TAG, "error on socket accept: " + err))
                .repeatUntil(() -> closed);
    }

    @Override
    public Socket accept() throws IOException {
        try {
            Socket socket = super.accept();
            socketBehaviorSubject.onNext(new SocketConnection(socket));
            socketSet.add(socket);
            return socket;
        } catch (IOException e) {
            throw e;
        }
    }


    @Override
    public void close() throws IOException {
        closed = true;
        super.close();
    }

    public Set<Socket> getSockets() {
        socketSet.removeIf(socket -> isClosed());
        return socketSet;
    }

    public Observable<SocketConnection> observeConnections() {
        return socketBehaviorSubject
                .doOnSubscribe(disp -> Log.v("debug", "subscribed to server sockets"));
    }
}
