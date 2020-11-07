package com.example.uscatterbrain.network.wifidirect;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.Observable;
import io.reactivex.subjects.BehaviorSubject;

public class InterceptableServerSocket extends ServerSocket {

    private final Set<Socket> socketSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final BehaviorSubject<Socket> socketBehaviorSubject = BehaviorSubject.create();

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

    @Override
    public Socket accept() throws IOException {
        try {
            Socket socket = super.accept();
            socketBehaviorSubject.onNext(socket);
            socketSet.add(socket);
            return socket;
        } catch (IOException e) {
            socketBehaviorSubject.onError(e);
            throw e;
        }
    }

    public Observable<Socket> observeConnections() {
        return socketBehaviorSubject;
    }

    public Set<Socket> getSockets() {
        return socketSet;
    }
}
