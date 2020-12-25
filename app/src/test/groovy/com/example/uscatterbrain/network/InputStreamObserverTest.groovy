package com.example.uscatterbrain.network

import com.github.davidmoten.rx2.Bytes
import io.reactivex.Flowable
import io.reactivex.Observable
import spock.lang.Specification

class InputStreamObserverTest extends Specification {
    static def BUF_CAPACITY = 524288
    InputStreamObserver inputStreamObserver

    def getBytes(val) {
        1..val
    }

    def setup() {
        inputStreamObserver = new InputStreamObserver()
    }

    def "test multiple read"() {
        when:
        def ret = -2
        def result = new byte[data.length]
        try {
            Bytes.from(new ByteArrayInputStream(data), 20)
                    .toObservable()
                    .subscribe(inputStreamObserver)

             ret = inputStreamObserver.read(result)
        } catch (IOException e) {
            e.printStackTrace()
        }

        then:
        ret == data.length
        result == data

        where:
        data << [1..(BUF_CAPACITY) as byte[]]
    }

    def "test close"() {
        when:
        byte[] data = [1,2,3,4,5,6,7,8]
        def result = new byte[data.length]
        def observable = Observable.just(data)
        observable.subscribe(inputStreamObserver)
        inputStreamObserver.close()
        inputStreamObserver.read(result)

        then:
        thrown(IOException)
    }

    def "test ring buffer"() {
        when:
        def bufsize = 17
        def smol = new InputStreamObserver(bufsize)
        def data = new byte[15]
        def result = new byte[15]
        new Random().nextBytes(data)
        for(int i=0;i<2;i++) {
            Bytes.from(new ByteArrayInputStream(data), 4)
                    .toObservable()
                    .blockingSubscribe(smol)

            smol.read(result)
        }

        then:
        Arrays.equals(result, data)

    }

    def "test multiple subscribes"() {
        when:
        def ret = -2
        def result = new byte[data.length]
        int half = data.length/2
        try {
            Bytes.from(new ByteArrayInputStream(Arrays.copyOfRange(data, 0, half)), 20)
                    .toObservable()
                    .subscribe(inputStreamObserver)

            Bytes.from(new ByteArrayInputStream(Arrays.copyOfRange(data, half, data.length)), 20)
                    .toObservable()
                    .subscribe(inputStreamObserver)

            ret = inputStreamObserver.read(result)
        } catch (IOException e) {
            e.printStackTrace()
        }

        then:
        ret == data.length
        result == data

        where:
        data << [1..(BUF_CAPACITY) as byte[]]
    }
}