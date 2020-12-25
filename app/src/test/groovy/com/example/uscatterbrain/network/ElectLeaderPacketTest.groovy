package com.example.uscatterbrain.network

import com.example.uscatterbrain.ScatterProto
import spock.lang.Specification


class ElectLeaderPacketTest extends Specification {
    static def tiebreaker = UUID.randomUUID()

    def setup() {

    }


    /*

    def "hashing works"() {
        when:
        def unhashedpacket = ElectLeaderPacket.newBuilder()
        .setProvides(ScatterProto.Advertise.Provides.BLE)
        .setTiebreaker(tiebreaker)
        .build()

        def hashedpacket = ElectLeaderPacket.newBuilder()
        .setProvides(ScatterProto.Advertise.Provides.BLE)
        .setTiebreaker(tiebreaker)
        .enableHashing()
        .build()

        def hashedresult = ElectLeaderPacket.parseFrom(hashedpacket.writeToStream(20)).blockingGet()
        def unhashedresult = ElectLeaderPacket.parseFrom(unhashedpacket.writeToStream(20)).blockingGet()

        then:
        hashedpacket.verifyHash(unhashedpacket)
        unhashedpacket.verifyHash(hashedpacket)
        hashedresult.verifyHash(unhashedresult)
        unhashedresult.verifyHash(hashedresult)
    }

     */
}