package com.example.uscatterbrain.network

import io.reactivex.Observable
import spock.lang.Specification

class InputStreamObserverTest extends Specification {
    static def BUF_CAPACITY = 524288;
    InputStreamCallback inputStreamObserver

    def getBytes(val) {
        1..val
    }

    def setup() {
        inputStreamObserver = new InputStreamObserver()
    }

    def "test multiple read"() {
        when:
        def result = new byte[data.length]
        Observable.just(data)
                .subscribe(inputStreamObserver)
        inputStreamObserver.read(result)

        then:
        result == data

        where:
        data << [1..8 as byte[], 1..BUF_CAPACITY as byte[], 1..(BUF_CAPACITY*3) as byte[]]
    }

    def "test close"() {
        when:
        byte[] data = [1,2,3,4,5,6,7,8]
        def result = new byte[data.length]
        def observable = Observable.just(data);
        observable.subscribe(inputStreamObserver)
        inputStreamObserver.close()
        inputStreamObserver.read(result)

        then:
        thrown(IOException)
    }
}