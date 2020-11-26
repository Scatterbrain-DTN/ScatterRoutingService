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


    def "test single read"() {
        byte[] data = [1,2,3,5,6,7,8,9]
        def result = new byte[data.length]
        def flowable = Flowable.just(data).repeat(2)

    }
}