package com.example.uscatterbrain.network

import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import spock.lang.Specification

import java.util.concurrent.TimeUnit

class SerializationTest extends Specification {
    LuidPacket luidPacket
    def luid = UUID.randomUUID()

    def setup() {
        luidPacket = LuidPacket.newBuilder()
        .setLuid(luid)
        .build()
    }


    def "luidpacket same luid from bytes"() {
        when:
        def bytes = luidPacket.getBytes()
        def is = new ByteArrayInputStream(bytes)
        def newpacket = LuidPacket.parseFrom(is).blockingGet()

        then:
        newpacket.getLuid().compareTo(luid) == 0
    }


    def "luidpacket same luid from flowable"() {
        when:
        def flowable = luidPacket.writeToStream(20)

        def newpacket = LuidPacket.parseFrom(flowable).blockingGet()

        then:
        newpacket.getLuid() == luid
    }

    Completable testFunc() {
        return luidPacket.writeToStream(20)
                .concatMapCompletable({bytes ->
                    System.out.println("bytes " + bytes.length)
                    return Completable.complete()
                })
    }

    Completable immediate() {
        return Completable.complete()
    }

    def "concatmap"() {
        when:
        immediate().andThen(testFunc()).subscribe(
                {-> System.out.println("complete")},
                {err-> System.out.println(err)}
        )

        then:
        true
    }

    def "luid packet from slow flowable"() {
        when:
        def flowable = luidPacket
                .writeToStream(20)
                .zipWith(
                        Flowable.interval(1, TimeUnit.SECONDS),
                        {data, integer ->
                            return data
                        }
                )

        def newpacket = LuidPacket.parseFrom(flowable).blockingGet()

        then:
        newpacket.getLuid() == luid
    }


    def "luid packet parsed from infinite stream"() {
        when:
        def flowable =
                Flowable.concatArray(
                        luidPacket.writeToStream(20),
                        Flowable.just(new byte[20]).repeat()
                )
                        .zipWith(
                                Flowable.interval(1, TimeUnit.SECONDS),
                                {data, integer ->
                                    return data
                                }
                        )
                        .doOnNext({b -> System.out.println("bytes: " + b.length)})

        def newpacket = LuidPacket.parseFrom(flowable).blockingGet()

        then:
        newpacket.getLuid() == luid
    }

    def "luid packet parsed from nonterminatring stream"() {
        when:
        def flowable =
                Observable.concatArray(
                        luidPacket.writeToStream(20).toObservable(),
                        Observable.never()
                )
                        .zipWith(
                                Observable.interval(1, TimeUnit.SECONDS),
                                {data, integer ->
                                    return data
                                }
                        )
                .doOnComplete({ -> System.out.println("complete (nonterminating)")})
        .doOnNext({b -> System.out.println("bytes (nonterminating): " + b.length)})

        def newpacket = LuidPacket.parseFrom(flowable).blockingGet()

        then:
        newpacket.getLuid() == luid
        flowable.test().assertNotComplete()
    }


    def "reproduce thread deadlock"() {
        when:
        def infinite = Observable.concatArray(
                luidPacket.writeToStream(5).toObservable(),
                Observable.never()
        )
                .zipWith(
                        Observable.interval(1, TimeUnit.SECONDS),
                        {data, integer ->
                            return data
                        }
                )
        def packet = Observable.fromCallable({->
            return infinite
        })
        .flatMapSingle({obs ->
            return LuidPacket.parseFrom(obs)
        })

        then:
        packet.blockingFirst().getLuid() == luid
        infinite.test().assertNotComplete()

    }

}