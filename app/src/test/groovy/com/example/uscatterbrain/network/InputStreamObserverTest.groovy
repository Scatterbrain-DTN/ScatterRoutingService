package com.example.uscatterbrain.network

import io.reactivex.Observable
import spock.lang.Specification

class InputStreamObserverTest extends Specification {
    InputStreamObserver inputStreamObserver
    byte[] data = [1, 2, 3, 4, 5, 6, 7, 8]
    def result = new byte[8]


    def setup() {
        inputStreamObserver = new InputStreamObserver()
    }

    def "test on Subscribe"() {
        when:
        Observable.just(data)
                .subscribe(inputStreamObserver)
        inputStreamObserver.read(result)

        then:
        result == data
    }
}